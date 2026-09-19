package store.cadera.cdrbounty.storage;

import store.cadera.cdrbounty.bounty.BountyContribution;
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

public final class SQLiteBountyRepository implements BountyRepository {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public SQLiteBountyRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CdrBounty-SQLite");
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

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY,
                      last_name TEXT NOT NULL,
                      last_seen INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bounty_contributions (
                      id TEXT PRIMARY KEY,
                      target_uuid TEXT NOT NULL,
                      issuer_uuid TEXT NOT NULL,
                      gross_amount TEXT NOT NULL,
                      escrow_amount TEXT NOT NULL,
                      fee_amount TEXT NOT NULL,
                      state TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      claim_id TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_operations (
                      id TEXT PRIMARY KEY,
                      type TEXT NOT NULL,
                      player_uuid TEXT NOT NULL,
                      contribution_id TEXT,
                      claim_id TEXT,
                      amount TEXT NOT NULL,
                      balance_before TEXT NOT NULL,
                      balance_after TEXT,
                      status TEXT NOT NULL,
                      error TEXT,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS claims (
                      id TEXT PRIMARY KEY,
                      target_uuid TEXT NOT NULL,
                      killer_uuid TEXT NOT NULL,
                      total_amount TEXT NOT NULL,
                      world TEXT NOT NULL,
                      status TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      paid_at INTEGER,
                      error TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS antifarm_pairs (
                      killer_uuid TEXT NOT NULL,
                      victim_uuid TEXT NOT NULL,
                      last_claim_at INTEGER NOT NULL,
                      window_start INTEGER NOT NULL,
                      window_claims INTEGER NOT NULL,
                      PRIMARY KEY (killer_uuid, victim_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                      id TEXT PRIMARY KEY,
                      actor_uuid TEXT,
                      action TEXT NOT NULL,
                      target_uuid TEXT,
                      amount TEXT,
                      details TEXT,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_target_state ON bounty_contributions(target_uuid, state, expires_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_claim ON bounty_contributions(claim_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_claim_killer_target ON claims(killer_uuid, target_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_economy_status ON economy_operations(status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_log(target_uuid, created_at)");
        }
    }

    @Override
    public CompletableFuture<Void> upsertPlayer(UUID uuid, String lastName, Instant seenAt) {
        return runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO players(uuid, last_name, last_seen) VALUES(?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name, last_seen=excluded.last_seen
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, lastName);
                ps.setLong(3, seenAt.toEpochMilli());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> createPendingPlacement(BountyContribution c, UUID operationId, BigDecimal balanceBefore) {
        return runAsync(() -> inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bounty_contributions
                    (id,target_uuid,issuer_uuid,gross_amount,escrow_amount,fee_amount,state,created_at,expires_at,updated_at,claim_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,NULL)
                    """)) {
                ps.setString(1, c.id().toString());
                ps.setString(2, c.targetUuid().toString());
                ps.setString(3, c.issuerUuid().toString());
                ps.setString(4, c.grossAmount().toPlainString());
                ps.setString(5, c.escrowAmount().toPlainString());
                ps.setString(6, c.feeAmount().toPlainString());
                ps.setString(7, BountyState.PENDING.name());
                ps.setLong(8, c.createdAt().toEpochMilli());
                ps.setLong(9, c.expiresAt().toEpochMilli());
                ps.setLong(10, c.createdAt().toEpochMilli());
                ps.executeUpdate();
            }
            insertEconomyOperation(operationId, RecoveryOperation.Type.WITHDRAWAL, c.issuerUuid(), c.id(), null,
                    c.grossAmount(), balanceBefore, c.createdAt());
        }));
    }

    @Override
    public CompletableFuture<Void> activatePlacement(UUID contributionId, UUID operationId, BigDecimal balanceAfter) {
        return runAsync(() -> inTransaction(() -> activatePlacementTx(contributionId, operationId, balanceAfter)));
    }

    @Override
    public CompletableFuture<Void> failPlacement(UUID contributionId, UUID operationId, String reason) {
        return runAsync(() -> inTransaction(() -> {
            requireStateTransition(contributionId, BountyState.PENDING, BountyState.VOIDED);
            updateOperation(operationId, "FAILED", null, reason);
        }));
    }

    @Override
    public CompletableFuture<BigDecimal> activeTotal(UUID targetUuid, Instant now) {
        return supplyAsync(() -> {
            BigDecimal total = BigDecimal.ZERO;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT escrow_amount FROM bounty_contributions
                    WHERE target_uuid=? AND state='ACTIVE' AND expires_at>?
                    """)) {
                ps.setString(1, targetUuid.toString());
                ps.setLong(2, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) total = total.add(decimal(rs, "escrow_amount"));
                }
            }
            return total;
        });
    }

    @Override
    public CompletableFuture<List<TargetTotal>> listActiveTotals(Instant now, int limit) {
        return supplyAsync(() -> {
            List<TargetTotal> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT target_uuid, escrow_amount FROM bounty_contributions
                    WHERE state='ACTIVE' AND expires_at>?
                    ORDER BY target_uuid
                    """)) {
                ps.setLong(1, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    UUID current = null;
                    BigDecimal total = BigDecimal.ZERO;
                    while (rs.next()) {
                        UUID target = UUID.fromString(rs.getString("target_uuid"));
                        if (current != null && !current.equals(target)) {
                            result.add(new TargetTotal(current, total));
                            if (result.size() >= Math.max(1, limit)) break;
                            total = BigDecimal.ZERO;
                        }
                        current = target;
                        total = total.add(decimal(rs, "escrow_amount"));
                    }
                    if (current != null && result.size() < Math.max(1, limit)) result.add(new TargetTotal(current, total));
                }
            }
            result.sort((a, b) -> b.amount().compareTo(a.amount()));
            if (result.size() > limit) return List.copyOf(result.subList(0, limit));
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<PairHistory> pairHistory(UUID killerUuid, UUID victimUuid) {
        return supplyAsync(() -> readPairHistory(killerUuid, victimUuid));
    }

    @Override
    public CompletableFuture<ClaimPreparation> prepareClaim(UUID claimId, UUID payoutOperationId, UUID targetUuid,
                                                             UUID killerUuid, String world, Instant now,
                                                             BigDecimal killerBalanceBefore) {
        return supplyAsync(() -> inTransactionResult(() -> {
            BigDecimal total = BigDecimal.ZERO;
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT escrow_amount FROM bounty_contributions
                    WHERE target_uuid=? AND state='ACTIVE' AND expires_at>?
                    """)) {
                ps.setString(1, targetUuid.toString());
                ps.setLong(2, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total = total.add(decimal(rs, "escrow_amount"));
                        count++;
                    }
                }
            }
            if (count == 0 || total.signum() <= 0) {
                throw new IllegalStateException("NO_ACTIVE_BOUNTY");
            }

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE bounty_contributions SET state='CLAIMING', claim_id=?, updated_at=?
                    WHERE target_uuid=? AND state='ACTIVE' AND expires_at>?
                    """)) {
                ps.setString(1, claimId.toString());
                ps.setLong(2, now.toEpochMilli());
                ps.setString(3, targetUuid.toString());
                ps.setLong(4, now.toEpochMilli());
                int updated = ps.executeUpdate();
                if (updated != count) throw new SQLException("Concurrent bounty mutation detected while preparing claim");
            }

            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO claims(id,target_uuid,killer_uuid,total_amount,world,status,created_at)
                    VALUES(?,?,?,?,?,'PREPARED',?)
                    """)) {
                ps.setString(1, claimId.toString());
                ps.setString(2, targetUuid.toString());
                ps.setString(3, killerUuid.toString());
                ps.setString(4, total.toPlainString());
                ps.setString(5, world);
                ps.setLong(6, now.toEpochMilli());
                ps.executeUpdate();
            }

            insertEconomyOperation(payoutOperationId, RecoveryOperation.Type.PAYOUT, killerUuid, null, claimId,
                    total, killerBalanceBefore, now);
            return new ClaimPreparation(claimId, targetUuid, killerUuid, total, count);
        }));
    }

    @Override
    public CompletableFuture<Void> completeClaim(UUID claimId, UUID payoutOperationId, BigDecimal killerBalanceAfter,
                                                  Instant completedAt, long repeatedPairWindowSeconds) {
        return runAsync(() -> inTransaction(() -> completeClaimTx(claimId, payoutOperationId, killerBalanceAfter,
                completedAt, repeatedPairWindowSeconds)));
    }

    @Override
    public CompletableFuture<Void> failClaim(UUID claimId, UUID payoutOperationId, String reason) {
        return runAsync(() -> inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE bounty_contributions SET state='ACTIVE', claim_id=NULL, updated_at=?
                    WHERE claim_id=? AND state='CLAIMING'
                    """)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, claimId.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE claims SET status='FAILED', error=? WHERE id=? AND status='PREPARED'")) {
                ps.setString(1, truncate(reason));
                ps.setString(2, claimId.toString());
                ps.executeUpdate();
            }
            updateOperation(payoutOperationId, "FAILED", null, reason);
        }));
    }

    @Override
    public CompletableFuture<List<RecoveryOperation>> unresolvedEconomyOperations() {
        return supplyAsync(() -> {
            List<RecoveryOperation> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id,type,player_uuid,contribution_id,claim_id,amount,balance_before,created_at
                    FROM economy_operations WHERE status='INTENT' ORDER BY created_at ASC
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String contribution = rs.getString("contribution_id");
                        String claim = rs.getString("claim_id");
                        result.add(new RecoveryOperation(
                                UUID.fromString(rs.getString("id")),
                                RecoveryOperation.Type.valueOf(rs.getString("type")),
                                UUID.fromString(rs.getString("player_uuid")),
                                contribution == null ? null : UUID.fromString(contribution),
                                claim == null ? null : UUID.fromString(claim),
                                decimal(rs, "amount"),
                                decimal(rs, "balance_before"),
                                Instant.ofEpochMilli(rs.getLong("created_at"))
                        ));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<Void> recoverPlacementAsActive(UUID contributionId, UUID operationId, BigDecimal balanceAfter) {
        return activatePlacement(contributionId, operationId, balanceAfter);
    }

    @Override
    public CompletableFuture<Void> recoverPlacementAsNotWithdrawn(UUID contributionId, UUID operationId) {
        return failPlacement(contributionId, operationId, "Recovery confirmed withdrawal did not occur");
    }

    @Override
    public CompletableFuture<Void> recoverClaimAsPaid(UUID claimId, UUID operationId, BigDecimal balanceAfter,
                                                       Instant completedAt, long pairWindowSeconds) {
        return completeClaim(claimId, operationId, balanceAfter, completedAt, pairWindowSeconds);
    }

    @Override
    public CompletableFuture<Void> markOperationAmbiguous(UUID operationId, String reason) {
        return runAsync(() -> updateOperation(operationId, "AMBIGUOUS", null, reason));
    }

    @Override
    public CompletableFuture<Void> audit(UUID actorUuid, String action, UUID targetUuid, BigDecimal amount,
                                         String details, Instant at) {
        return runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO audit_log(id,actor_uuid,action,target_uuid,amount,details,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, actorUuid == null ? null : actorUuid.toString());
                ps.setString(3, action);
                ps.setString(4, targetUuid == null ? null : targetUuid.toString());
                ps.setString(5, amount == null ? null : amount.toPlainString());
                ps.setString(6, truncate(details));
                ps.setLong(7, at.toEpochMilli());
                ps.executeUpdate();
            }
        });
    }

    private void activatePlacementTx(UUID contributionId, UUID operationId, BigDecimal balanceAfter) throws Exception {
        requireStateTransition(contributionId, BountyState.PENDING, BountyState.ACTIVE);
        updateOperation(operationId, "COMMITTED", balanceAfter, null);
    }

    private void completeClaimTx(UUID claimId, UUID payoutOperationId, BigDecimal balanceAfter,
                                 Instant completedAt, long pairWindowSeconds) throws Exception {
        UUID targetUuid;
        UUID killerUuid;
        try (PreparedStatement ps = connection.prepareStatement("SELECT target_uuid,killer_uuid,status FROM claims WHERE id=?")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Claim not found: " + claimId);
                if (!"PREPARED".equals(rs.getString("status"))) throw new SQLException("Claim is not PREPARED: " + claimId);
                targetUuid = UUID.fromString(rs.getString("target_uuid"));
                killerUuid = UUID.fromString(rs.getString("killer_uuid"));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bounty_contributions SET state='CLAIMED', updated_at=?
                WHERE claim_id=? AND state='CLAIMING'
                """)) {
            ps.setLong(1, completedAt.toEpochMilli());
            ps.setString(2, claimId.toString());
            if (ps.executeUpdate() <= 0) throw new SQLException("No CLAIMING contributions found for claim " + claimId);
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE claims SET status='PAID', paid_at=? WHERE id=? AND status='PREPARED'")) {
            ps.setLong(1, completedAt.toEpochMilli());
            ps.setString(2, claimId.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Claim completion race for " + claimId);
        }
        updateOperation(payoutOperationId, "COMMITTED", balanceAfter, null);
        updatePairHistory(killerUuid, targetUuid, completedAt, pairWindowSeconds);
    }

    private void updatePairHistory(UUID killer, UUID victim, Instant at, long windowSeconds) throws SQLException {
        PairHistory previous = readPairHistory(killer, victim);
        Instant windowStart = previous.windowStart();
        int count;
        if (windowStart == null || at.isAfter(windowStart.plusSeconds(windowSeconds))) {
            windowStart = at;
            count = 1;
        } else {
            count = previous.windowClaims() + 1;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO antifarm_pairs(killer_uuid,victim_uuid,last_claim_at,window_start,window_claims)
                VALUES(?,?,?,?,?)
                ON CONFLICT(killer_uuid,victim_uuid) DO UPDATE SET
                  last_claim_at=excluded.last_claim_at,
                  window_start=excluded.window_start,
                  window_claims=excluded.window_claims
                """)) {
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            ps.setLong(3, at.toEpochMilli());
            ps.setLong(4, windowStart.toEpochMilli());
            ps.setInt(5, count);
            ps.executeUpdate();
        }
    }

    private PairHistory readPairHistory(UUID killer, UUID victim) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT last_claim_at,window_start,window_claims FROM antifarm_pairs
                WHERE killer_uuid=? AND victim_uuid=?
                """)) {
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return PairHistory.empty();
                return new PairHistory(
                        Instant.ofEpochMilli(rs.getLong("last_claim_at")),
                        Instant.ofEpochMilli(rs.getLong("window_start")),
                        rs.getInt("window_claims")
                );
            }
        }
    }

    private void requireStateTransition(UUID contributionId, BountyState expected, BountyState next) throws Exception {
        expected.requireTransitionTo(next);
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bounty_contributions SET state=?, updated_at=? WHERE id=? AND state=?
                """)) {
            ps.setString(1, next.name());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, contributionId.toString());
            ps.setString(4, expected.name());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Contribution state transition rejected for " + contributionId + ": expected " + expected);
            }
        }
    }

    private void insertEconomyOperation(UUID id, RecoveryOperation.Type type, UUID playerUuid, UUID contributionId,
                                        UUID claimId, BigDecimal amount, BigDecimal balanceBefore, Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO economy_operations
                (id,type,player_uuid,contribution_id,claim_id,amount,balance_before,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'INTENT',?,?)
                """)) {
            ps.setString(1, id.toString());
            ps.setString(2, type.name());
            ps.setString(3, playerUuid.toString());
            ps.setString(4, contributionId == null ? null : contributionId.toString());
            ps.setString(5, claimId == null ? null : claimId.toString());
            ps.setString(6, amount.toPlainString());
            ps.setString(7, balanceBefore.toPlainString());
            ps.setLong(8, at.toEpochMilli());
            ps.setLong(9, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private void updateOperation(UUID operationId, String status, BigDecimal balanceAfter, String error) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE economy_operations SET status=?, balance_after=?, error=?, updated_at=?
                WHERE id=? AND status='INTENT'
                """)) {
            ps.setString(1, status);
            ps.setString(2, balanceAfter == null ? null : balanceAfter.toPlainString());
            ps.setString(3, truncate(error));
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, operationId.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Economy operation is not unresolved: " + operationId);
        }
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        return new BigDecimal(rs.getString(column));
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private void inTransaction(SqlRunnable runnable) throws Exception {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            runnable.run();
            connection.commit();
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private <T> T inTransactionResult(SqlSupplier<T> supplier) throws Exception {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = supplier.get();
            connection.commit();
            return result;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
