package store.cadera.cdrbounty.storage;

import store.cadera.cdrbounty.bounty.BountyState;
import store.cadera.cdrbounty.config.PluginSettings;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SQLiteMaintenanceRepository implements BountyMaintenanceRepository {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public SQLiteMaintenanceRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CdrBounty-Maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void initialize() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + settings.sqliteFile());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + settings.sqliteBusyTimeoutMs());
            statement.execute("PRAGMA journal_mode = " + settings.sqliteJournalMode());
            statement.execute("PRAGMA synchronous = " + settings.sqliteSynchronous());
        }
    }

    @Override
    public CompletableFuture<List<RefundCandidate>> findExpired(Instant now, int limit) {
        return supplyAsync(() -> readCandidates("state='ACTIVE' AND expires_at<=?", ps -> {
            ps.setLong(1, now.toEpochMilli());
            ps.setInt(2, Math.max(1, limit));
        }, Math.max(1, limit)));
    }

    @Override
    public CompletableFuture<List<RefundCandidate>> activeContributions(UUID targetUuid) {
        return supplyAsync(() -> {
            List<RefundCandidate> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id,issuer_uuid,escrow_amount,expires_at
                    FROM bounty_contributions
                    WHERE target_uuid=? AND state='ACTIVE'
                    ORDER BY created_at ASC
                    """)) {
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(candidate(rs));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<Void> reserveRefund(UUID contributionId, BountyState intermediateState,
                                                 UUID operationId, BigDecimal balanceBefore, Instant now) {
        return runAsync(() -> inTransaction(() -> {
            if (intermediateState != BountyState.EXPIRED && intermediateState != BountyState.CANCELLED) {
                throw new IllegalArgumentException("Refund intermediate state must be EXPIRED or CANCELLED");
            }
            BountyState.ACTIVE.requireTransitionTo(intermediateState);

            UUID issuer;
            BigDecimal amount;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT issuer_uuid,escrow_amount,state FROM bounty_contributions WHERE id=?
                    """)) {
                ps.setString(1, contributionId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Contribution not found: " + contributionId);
                    if (!"ACTIVE".equals(rs.getString("state"))) throw new SQLException("Contribution is not ACTIVE: " + contributionId);
                    issuer = UUID.fromString(rs.getString("issuer_uuid"));
                    amount = new BigDecimal(rs.getString("escrow_amount"));
                }
            }
            if (SYSTEM_ISSUER.equals(issuer)) throw new SQLException("System bounty cannot be refunded to a player account");

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE bounty_contributions SET state=?,updated_at=? WHERE id=? AND state='ACTIVE'
                    """)) {
                ps.setString(1, intermediateState.name());
                ps.setLong(2, now.toEpochMilli());
                ps.setString(3, contributionId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Contribution refund reservation race: " + contributionId);
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO economy_operations
                    (id,type,player_uuid,contribution_id,claim_id,amount,balance_before,status,created_at,updated_at)
                    VALUES(?,'REFUND',?,?,NULL,?,?,'INTENT',?,?)
                    """)) {
                ps.setString(1, operationId.toString());
                ps.setString(2, issuer.toString());
                ps.setString(3, contributionId.toString());
                ps.setString(4, amount.toPlainString());
                ps.setString(5, balanceBefore.toPlainString());
                ps.setLong(6, now.toEpochMilli());
                ps.setLong(7, now.toEpochMilli());
                ps.executeUpdate();
            }
        }));
    }

    @Override
    public CompletableFuture<Void> completeRefund(UUID contributionId, UUID operationId,
                                                  BigDecimal balanceAfter, Instant now) {
        return runAsync(() -> inTransaction(() -> {
            BountyState current;
            try (PreparedStatement ps = connection.prepareStatement("SELECT state FROM bounty_contributions WHERE id=?")) {
                ps.setString(1, contributionId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Contribution not found: " + contributionId);
                    current = BountyState.valueOf(rs.getString("state"));
                }
            }
            if (current != BountyState.EXPIRED && current != BountyState.CANCELLED) {
                throw new SQLException("Contribution is not awaiting refund: " + contributionId + " state=" + current);
            }
            current.requireTransitionTo(BountyState.REFUNDED);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE bounty_contributions SET state='REFUNDED',updated_at=? WHERE id=? AND state=?")) {
                ps.setLong(1, now.toEpochMilli());
                ps.setString(2, contributionId.toString());
                ps.setString(3, current.name());
                if (ps.executeUpdate() != 1) throw new SQLException("Refund completion race: " + contributionId);
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE economy_operations SET status='COMMITTED',balance_after=?,updated_at=?
                    WHERE id=? AND status='INTENT'
                    """)) {
                ps.setString(1, balanceAfter.toPlainString());
                ps.setLong(2, now.toEpochMilli());
                ps.setString(3, operationId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Refund operation is not INTENT: " + operationId);
            }
        }));
    }

    @Override
    public CompletableFuture<Void> voidContribution(UUID contributionId, BountyState intermediateState,
                                                    UUID actorUuid, String reason, Instant now) {
        return runAsync(() -> inTransaction(() -> {
            if (intermediateState != BountyState.EXPIRED && intermediateState != BountyState.CANCELLED) {
                throw new IllegalArgumentException("Void intermediate state must be EXPIRED or CANCELLED");
            }
            BountyState.ACTIVE.requireTransitionTo(intermediateState);
            intermediateState.requireTransitionTo(BountyState.VOIDED);
            BigDecimal amount;
            UUID target;
            try (PreparedStatement ps = connection.prepareStatement("SELECT target_uuid,escrow_amount FROM bounty_contributions WHERE id=? AND state='ACTIVE'")) {
                ps.setString(1, contributionId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Contribution is not ACTIVE: " + contributionId);
                    target = UUID.fromString(rs.getString("target_uuid"));
                    amount = new BigDecimal(rs.getString("escrow_amount"));
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE bounty_contributions SET state='VOIDED',updated_at=? WHERE id=? AND state='ACTIVE'")) {
                ps.setLong(1, now.toEpochMilli());
                ps.setString(2, contributionId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Contribution void race: " + contributionId);
            }
            insertAudit(actorUuid, "BOUNTY_VOID_" + intermediateState.name(), target, amount, reason, now);
        }));
    }

    @Override
    public CompletableFuture<UUID> adminAdd(UUID targetUuid, UUID actorUuid, BigDecimal amount,
                                            Instant createdAt, Instant expiresAt) {
        return supplyAsync(() -> inTransactionResult(() -> {
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bounty_contributions
                    (id,target_uuid,issuer_uuid,gross_amount,escrow_amount,fee_amount,state,created_at,expires_at,updated_at,claim_id)
                    VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,NULL)
                    """)) {
                ps.setString(1, id.toString());
                ps.setString(2, targetUuid.toString());
                ps.setString(3, SYSTEM_ISSUER.toString());
                ps.setString(4, amount.toPlainString());
                ps.setString(5, amount.toPlainString());
                ps.setString(6, BigDecimal.ZERO.toPlainString());
                ps.setLong(7, createdAt.toEpochMilli());
                ps.setLong(8, expiresAt.toEpochMilli());
                ps.setLong(9, createdAt.toEpochMilli());
                ps.executeUpdate();
            }
            insertAudit(actorUuid, "ADMIN_ADD", targetUuid, amount, "System-funded bounty", createdAt);
            return id;
        }));
    }

    @Override
    public CompletableFuture<List<ClaimHistory>> claimHistory(UUID targetUuid, int limit) {
        return supplyAsync(() -> {
            List<ClaimHistory> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id,killer_uuid,total_amount,world,status,created_at,paid_at
                    FROM claims WHERE target_uuid=? ORDER BY created_at DESC LIMIT ?
                    """)) {
                ps.setString(1, targetUuid.toString());
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long paid = rs.getLong("paid_at");
                        result.add(new ClaimHistory(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("killer_uuid")),
                                new BigDecimal(rs.getString("total_amount")),
                                rs.getString("world"),
                                rs.getString("status"),
                                Instant.ofEpochMilli(rs.getLong("created_at")),
                                rs.wasNull() || paid == 0 ? null : Instant.ofEpochMilli(paid)
                        ));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    private List<RefundCandidate> readCandidates(String where, Binder binder, int limit) throws Exception {
        List<RefundCandidate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id,issuer_uuid,escrow_amount,expires_at
                FROM bounty_contributions WHERE """ + where + " ORDER BY expires_at ASC LIMIT ?")) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(candidate(rs));
            }
        }
        return List.copyOf(result);
    }

    private static RefundCandidate candidate(ResultSet rs) throws SQLException {
        return new RefundCandidate(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("issuer_uuid")),
                new BigDecimal(rs.getString("escrow_amount")),
                Instant.ofEpochMilli(rs.getLong("expires_at"))
        );
    }

    private void insertAudit(UUID actor, String action, UUID target, BigDecimal amount, String details, Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO audit_log(id,actor_uuid,action,target_uuid,amount,details,created_at)
                VALUES(?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, actor == null ? null : actor.toString());
            ps.setString(3, action);
            ps.setString(4, target == null ? null : target.toString());
            ps.setString(5, amount == null ? null : amount.toPlainString());
            ps.setString(6, details);
            ps.setLong(7, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try { runnable.run(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    private void inTransaction(SqlRunnable runnable) throws Exception {
        boolean old = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            runnable.run();
            connection.commit();
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(old);
        }
    }

    private <T> T inTransactionResult(SqlSupplier<T> supplier) throws Exception {
        boolean old = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T value = supplier.get();
            connection.commit();
            return value;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(old);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { }
        }
    }

    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws Exception; }
}
