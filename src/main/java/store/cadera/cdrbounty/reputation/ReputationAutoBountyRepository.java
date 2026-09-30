package store.cadera.cdrbounty.reputation;

import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ReputationAutoBountyRepository implements AutoCloseable {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public ReputationAutoBountyRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CdrBounty-Reputation-SQLite");
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reputation_auto_bounty_state (
                      target_uuid TEXT PRIMARY KEY,
                      last_threshold INTEGER NOT NULL,
                      last_reputation INTEGER NOT NULL,
                      last_contract_id TEXT,
                      updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    public CompletableFuture<EvaluationResult> evaluateAndIssue(
            UUID targetUuid,
            int reputation,
            int resetThreshold,
            List<ReputationAutoBountyPolicy.Rule> rules,
            Instant now
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return inTransaction(() -> evaluateAndIssueTx(targetUuid, reputation, resetThreshold, rules, now));
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private EvaluationResult evaluateAndIssueTx(
            UUID targetUuid,
            int reputation,
            int resetThreshold,
            List<ReputationAutoBountyPolicy.Rule> rules,
            Instant now
    ) throws Exception {
        int lastThreshold = readLastThreshold(targetUuid);
        ReputationAutoBountyPolicy.Decision decision = ReputationAutoBountyPolicy.evaluate(
                reputation, lastThreshold, resetThreshold, rules);

        if (decision.reset()) {
            upsertState(targetUuid, 0, reputation, null, now);
            return EvaluationResult.reset(reputation);
        }
        if (!decision.issue()) {
            if (stateExists(targetUuid)) updateLastReputation(targetUuid, reputation, now);
            return EvaluationResult.none(reputation, lastThreshold);
        }

        BigDecimal amount = MoneyMath.normalize(decision.amount(), settings.decimalScale());
        if (amount.compareTo(settings.maximumBounty()) > 0) amount = settings.maximumBounty();
        if (amount.signum() <= 0) return EvaluationResult.none(reputation, lastThreshold);

        UUID contributionId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(settings.durationSeconds());
        String systemIssuer = BountyMaintenanceRepository.SYSTEM_ISSUER.toString();

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bounty_contributions
                (id,target_uuid,issuer_uuid,gross_amount,escrow_amount,fee_amount,state,created_at,expires_at,updated_at,claim_id)
                VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,NULL)
                """)) {
            ps.setString(1, contributionId.toString());
            ps.setString(2, targetUuid.toString());
            ps.setString(3, systemIssuer);
            ps.setString(4, amount.toPlainString());
            ps.setString(5, amount.toPlainString());
            ps.setString(6, BigDecimal.ZERO.setScale(settings.decimalScale()).toPlainString());
            ps.setLong(7, now.toEpochMilli());
            ps.setLong(8, expiresAt.toEpochMilli());
            ps.setLong(9, now.toEpochMilli());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contracts
                (id,contribution_id,target_uuid,issuer_uuid,reward_amount,flags,state,created_at,activated_at,
                 expires_at,reservation_limit,settlement_claim_id,last_error)
                VALUES(?,?,?,?,?,'PUBLIC','OPEN',?,?,?,?,NULL,NULL)
                """)) {
            ps.setString(1, contractId.toString());
            ps.setString(2, contributionId.toString());
            ps.setString(3, targetUuid.toString());
            ps.setString(4, systemIssuer);
            ps.setString(5, amount.toPlainString());
            ps.setLong(6, now.toEpochMilli());
            ps.setLong(7, now.toEpochMilli());
            ps.setLong(8, expiresAt.toEpochMilli());
            ps.setInt(9, settings.publicReservationLimit());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contract_history(id,contract_id,actor_uuid,action,details,created_at)
                VALUES(?,?,NULL,'REPUTATION_AUTO_BOUNTY',?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, contractId.toString());
            ps.setString(3, "reputation=" + reputation + "; threshold=" + decision.nextThreshold()
                    + "; amount=" + amount.toPlainString());
            ps.setLong(4, now.toEpochMilli());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO audit_log(id,actor_uuid,action,target_uuid,amount,details,created_at)
                VALUES(?,NULL,'REPUTATION_AUTO_BOUNTY',?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, targetUuid.toString());
            ps.setString(3, amount.toPlainString());
            ps.setString(4, "reputation=" + reputation + "; threshold=" + decision.nextThreshold());
            ps.setLong(5, now.toEpochMilli());
            ps.executeUpdate();
        }

        upsertState(targetUuid, decision.nextThreshold(), reputation, contractId, now);
        return EvaluationResult.issued(contractId, amount, reputation, decision.nextThreshold());
    }

    private int readLastThreshold(UUID targetUuid) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_threshold FROM reputation_auto_bounty_state WHERE target_uuid=?")) {
            ps.setString(1, targetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean stateExists(UUID targetUuid) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM reputation_auto_bounty_state WHERE target_uuid=?")) {
            ps.setString(1, targetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void updateLastReputation(UUID targetUuid, int reputation, Instant now) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE reputation_auto_bounty_state SET last_reputation=?,updated_at=? WHERE target_uuid=?")) {
            ps.setInt(1, reputation);
            ps.setLong(2, now.toEpochMilli());
            ps.setString(3, targetUuid.toString());
            ps.executeUpdate();
        }
    }

    private void upsertState(UUID targetUuid, int threshold, int reputation, UUID contractId, Instant now) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO reputation_auto_bounty_state(target_uuid,last_threshold,last_reputation,last_contract_id,updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(target_uuid) DO UPDATE SET
                  last_threshold=excluded.last_threshold,
                  last_reputation=excluded.last_reputation,
                  last_contract_id=excluded.last_contract_id,
                  updated_at=excluded.updated_at
                """)) {
            ps.setString(1, targetUuid.toString());
            ps.setInt(2, threshold);
            ps.setInt(3, reputation);
            ps.setString(4, contractId == null ? null : contractId.toString());
            ps.setLong(5, now.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlSupplier<T> supplier) throws Exception {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T value = supplier.get();
            connection.commit();
            return value;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (connection != null) try { connection.close(); } catch (Exception ignored) { }
    }

    public record EvaluationResult(Action action, UUID contractId, BigDecimal amount,
                                   int reputation, int threshold) {
        public enum Action { NONE, RESET, ISSUED }
        public static EvaluationResult none(int reputation, int threshold) {
            return new EvaluationResult(Action.NONE, null, BigDecimal.ZERO, reputation, threshold);
        }
        public static EvaluationResult reset(int reputation) {
            return new EvaluationResult(Action.RESET, null, BigDecimal.ZERO, reputation, 0);
        }
        public static EvaluationResult issued(UUID contractId, BigDecimal amount, int reputation, int threshold) {
            return new EvaluationResult(Action.ISSUED, contractId, amount, reputation, threshold);
        }
        public boolean issued() { return action == Action.ISSUED; }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> { T get() throws Exception; }
}
