package store.cadera.cdrbounty.approval;

import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.BountyContract;
import store.cadera.cdrbounty.contract.ContractCondition;
import store.cadera.cdrbounty.contract.ContractConditionType;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.contract.ContractState;

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

public final class ApprovalRepository implements AutoCloseable {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public ApprovalRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CdrBounty-Approval-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

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

    public CompletableFuture<Void> submit(UUID contractId, BigDecimal rewardAmount, Instant submittedAt) {
        return runAsync(() -> inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state='PENDING_APPROVAL',reward_amount=?,activated_at=NULL,last_error=NULL
                    WHERE id=? AND state='DRAFT'
                    """)) {
                ps.setString(1, rewardAmount.toPlainString());
                ps.setString(2, contractId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Contract is not DRAFT: " + contractId);
            }
            history(contractId, null, "SUBMITTED_FOR_APPROVAL", "reward=" + rewardAmount.toPlainString(), submittedAt);
        }));
    }

    public CompletableFuture<List<ApprovalRequest>> listPending(int limit) {
        return supplyAsync(() -> {
            List<ApprovalRequest> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.* FROM contracts c
                    JOIN bounty_contributions b ON b.id=c.contribution_id
                    WHERE c.state='PENDING_APPROVAL' AND b.state='ACTIVE'
                    ORDER BY c.created_at ASC LIMIT ?
                    """)) {
                ps.setInt(1, Math.max(1, Math.min(limit, 200)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BountyContract contract = mapContract(rs);
                        result.add(new ApprovalRequest(contract, conditions(contract.id())));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<ApprovalRequest> getPending(UUID contractId) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.* FROM contracts c
                    JOIN bounty_contributions b ON b.id=c.contribution_id
                    WHERE c.id=? AND c.state='PENDING_APPROVAL' AND b.state='ACTIVE'
                    """)) {
                ps.setString(1, contractId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    BountyContract contract = mapContract(rs);
                    return new ApprovalRequest(contract, conditions(contract.id()));
                }
            }
        });
    }

    public CompletableFuture<ActionResult> approve(UUID contractId, UUID actorUuid, Instant now, Instant expiresAt) {
        return supplyAsync(() -> inTransactionResult(() -> {
            UUID contributionId = contributionId(contractId, ContractState.PENDING_APPROVAL);
            if (contributionId == null) return ActionResult.fail("NOT_PENDING");

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE bounty_contributions SET expires_at=?,updated_at=?
                    WHERE id=? AND state='ACTIVE'
                    """)) {
                ps.setLong(1, expiresAt.toEpochMilli());
                ps.setLong(2, now.toEpochMilli());
                ps.setString(3, contributionId.toString());
                if (ps.executeUpdate() != 1) return ActionResult.fail("ESCROW_NOT_ACTIVE");
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state='OPEN',activated_at=?,expires_at=?,last_error=NULL
                    WHERE id=? AND state='PENDING_APPROVAL'
                    """)) {
                ps.setLong(1, now.toEpochMilli());
                ps.setLong(2, expiresAt.toEpochMilli());
                ps.setString(3, contractId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Approval race: " + contractId);
            }
            history(contractId, actorUuid, "APPROVED", "expires=" + expiresAt, now);
            return ActionResult.ok();
        }));
    }

    public CompletableFuture<RejectPreparation> reserveRejection(UUID contractId, UUID actorUuid,
                                                                  UUID operationId, BigDecimal balanceBefore,
                                                                  Instant now) {
        return supplyAsync(() -> inTransactionResult(() -> {
            UUID contributionId = contributionId(contractId, ContractState.PENDING_APPROVAL);
            if (contributionId == null) throw new IllegalStateException("NOT_PENDING");

            UUID issuerUuid;
            BigDecimal grossAmount;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT issuer_uuid,gross_amount,state FROM bounty_contributions WHERE id=?
                    """)) {
                ps.setString(1, contributionId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Contribution not found: " + contributionId);
                    if (!"ACTIVE".equals(rs.getString("state"))) throw new IllegalStateException("ESCROW_NOT_ACTIVE");
                    issuerUuid = UUID.fromString(rs.getString("issuer_uuid"));
                    grossAmount = new BigDecimal(rs.getString("gross_amount"));
                }
            }

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE bounty_contributions SET state='CANCELLED',updated_at=? WHERE id=? AND state='ACTIVE'
                    """)) {
                ps.setLong(1, now.toEpochMilli());
                ps.setString(2, contributionId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Rejection refund reservation race: " + contributionId);
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO economy_operations
                    (id,type,player_uuid,contribution_id,claim_id,amount,balance_before,status,created_at,updated_at)
                    VALUES(?,'REFUND',?,?,NULL,?,?,'INTENT',?,?)
                    """)) {
                ps.setString(1, operationId.toString());
                ps.setString(2, issuerUuid.toString());
                ps.setString(3, contributionId.toString());
                ps.setString(4, grossAmount.toPlainString());
                ps.setString(5, balanceBefore.toPlainString());
                ps.setLong(6, now.toEpochMilli());
                ps.setLong(7, now.toEpochMilli());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET last_error='REJECTION_REFUND_PENDING'
                    WHERE id=? AND state='PENDING_APPROVAL'
                    """)) {
                ps.setString(1, contractId.toString());
                ps.executeUpdate();
            }
            history(contractId, actorUuid, "REJECTION_REQUESTED", "refund=" + grossAmount.toPlainString(), now);
            return new RejectPreparation(contributionId, issuerUuid, grossAmount);
        }));
    }

    public CompletableFuture<Void> completeRejection(UUID contractId, UUID actorUuid, String reason, Instant now) {
        return runAsync(() -> inTransaction(() -> {
            String contributionState;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT b.state FROM contracts c JOIN bounty_contributions b ON b.id=c.contribution_id WHERE c.id=?
                    """)) {
                ps.setString(1, contractId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Contract not found: " + contractId);
                    contributionState = rs.getString(1);
                }
            }
            if (!"REFUNDED".equals(contributionState)) {
                throw new IllegalStateException("REFUND_NOT_COMPLETED");
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state='REJECTED',last_error=?
                    WHERE id=? AND state='PENDING_APPROVAL'
                    """)) {
                ps.setString(1, truncate(reason));
                ps.setString(2, contractId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Contract is not pending: " + contractId);
            }
            history(contractId, actorUuid, "REJECTED", reason, now);
        }));
    }

    public CompletableFuture<Integer> reconcile() {
        return supplyAsync(() -> inTransactionResult(() -> {
            int changed = 0;
            List<UUID> drafts = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.id FROM contracts c JOIN bounty_contributions b ON b.id=c.contribution_id
                    WHERE c.state='DRAFT' AND b.state='ACTIVE'
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) drafts.add(UUID.fromString(rs.getString(1)));
                }
            }
            for (UUID id : drafts) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE contracts SET state='PENDING_APPROVAL',last_error=NULL
                        WHERE id=? AND state='DRAFT'
                        """)) {
                    ps.setString(1, id.toString());
                    changed += ps.executeUpdate();
                }
                history(id, null, "APPROVAL_RECOVERED", "Recovered funded draft", Instant.now());
            }

            List<UUID> refunded = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.id FROM contracts c JOIN bounty_contributions b ON b.id=c.contribution_id
                    WHERE c.state='PENDING_APPROVAL' AND b.state='REFUNDED'
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) refunded.add(UUID.fromString(rs.getString(1)));
                }
            }
            for (UUID id : refunded) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE contracts SET state='REJECTED',last_error='Recovered completed rejection refund'
                        WHERE id=? AND state='PENDING_APPROVAL'
                        """)) {
                    ps.setString(1, id.toString());
                    changed += ps.executeUpdate();
                }
                history(id, null, "REJECTED_RECOVERED", null, Instant.now());
            }
            return changed;
        }));
    }

    public CompletableFuture<Integer> pendingCount() {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM contracts WHERE state='PENDING_APPROVAL'")) {
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        });
    }

    private UUID contributionId(UUID contractId, ContractState requiredState) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT contribution_id FROM contracts WHERE id=? AND state=?")) {
            ps.setString(1, contractId.toString());
            ps.setString(2, requiredState.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    private List<ContractCondition> conditions(UUID contractId) throws SQLException {
        List<ContractCondition> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT type,value FROM contract_conditions WHERE contract_id=? ORDER BY position
                """)) {
            ps.setString(1, contractId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new ContractCondition(
                        ContractConditionType.valueOf(rs.getString("type")), rs.getString("value")));
            }
        }
        return List.copyOf(result);
    }

    private BountyContract mapContract(ResultSet rs) throws SQLException {
        long activated = rs.getLong("activated_at");
        boolean activatedNull = rs.wasNull();
        String claimRaw = rs.getString("settlement_claim_id");
        return new BountyContract(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("contribution_id")),
                UUID.fromString(rs.getString("target_uuid")),
                UUID.fromString(rs.getString("issuer_uuid")),
                new BigDecimal(rs.getString("reward_amount")),
                ContractFlag.parse(rs.getString("flags")),
                ContractState.valueOf(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                activatedNull || activated == 0L ? null : Instant.ofEpochMilli(activated),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                rs.getInt("reservation_limit"),
                claimRaw == null ? null : UUID.fromString(claimRaw),
                rs.getString("last_error")
        );
    }

    private void history(UUID contractId, UUID actorUuid, String action, String details, Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contract_history(id,contract_id,actor_uuid,action,details,created_at)
                VALUES(?,?,?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, contractId.toString());
            ps.setString(3, actorUuid == null ? null : actorUuid.toString());
            ps.setString(4, action);
            ps.setString(5, details == null ? null : truncate(details));
            ps.setLong(6, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try { runnable.run(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    private void inTransaction(SqlRunnable runnable) throws Exception {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            runnable.run();
            connection.commit();
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private <T> T inTransactionResult(SqlSupplier<T> supplier) throws Exception {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T value = supplier.get();
            connection.commit();
            return value;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    @Override
    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }

    public record ApprovalRequest(BountyContract contract, List<ContractCondition> conditions) {}
    public record RejectPreparation(UUID contributionId, UUID issuerUuid, BigDecimal refundAmount) {}
    public record ActionResult(boolean success, String reason) {
        public static ActionResult ok() { return new ActionResult(true, "OK"); }
        public static ActionResult fail(String reason) { return new ActionResult(false, reason); }
    }

    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}
