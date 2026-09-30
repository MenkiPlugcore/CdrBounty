package store.cadera.cdrbounty.quest;

import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class QuestBountyRepository implements AutoCloseable {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public QuestBountyRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CdrBounty-Quest-SQLite");
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
                    CREATE TABLE IF NOT EXISTS quest_bounty_links (
                      id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      quest_key TEXT NOT NULL,
                      contract_id TEXT NOT NULL UNIQUE,
                      target_uuid TEXT NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_quest_bounty_player_key ON quest_bounty_links(player_uuid,quest_key,created_at DESC)");
        }
    }

    public CompletableFuture<CreateResult> createOrReuse(UUID hunterUuid, String questKey, UUID targetUuid,
                                                           BigDecimal rawAmount, Instant now, long durationSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return inTransaction(() -> createOrReuseTx(hunterUuid, questKey, targetUuid, rawAmount, now, durationSeconds));
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    public CompletableFuture<CancelResult> cancel(UUID hunterUuid, String questKey, Instant now) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return inTransaction(() -> cancelTx(hunterUuid, questKey, now));
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    public CompletableFuture<StatusResult> status(UUID hunterUuid, String questKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readStatus(hunterUuid, questKey);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private CreateResult createOrReuseTx(UUID hunterUuid, String questKey, UUID targetUuid,
                                          BigDecimal rawAmount, Instant now, long durationSeconds) throws Exception {
        StatusResult latest = readStatus(hunterUuid, questKey);
        if (latest.status() == QuestStatus.ACTIVE) {
            return new CreateResult(CreateAction.REUSED_ACTIVE, latest.contractId(), latest.targetUuid(), BigDecimal.ZERO);
        }

        BigDecimal amount = MoneyMath.normalize(rawAmount, settings.decimalScale());
        if (amount.compareTo(settings.minimumBounty()) < 0) {
            throw new IllegalArgumentException("Quest bounty amount is below economy.minimum-bounty");
        }
        if (amount.compareTo(settings.maximumBounty()) > 0) {
            throw new IllegalArgumentException("Quest bounty amount exceeds economy.maximum-bounty");
        }

        UUID contributionId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID systemIssuer = BountyMaintenanceRepository.SYSTEM_ISSUER;
        Instant expiresAt = now.plusSeconds(Math.max(60L, durationSeconds));
        BigDecimal zero = BigDecimal.ZERO.setScale(settings.decimalScale());

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bounty_contributions
                (id,target_uuid,issuer_uuid,gross_amount,escrow_amount,fee_amount,state,created_at,expires_at,updated_at,claim_id)
                VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,NULL)
                """)) {
            ps.setString(1, contributionId.toString());
            ps.setString(2, targetUuid.toString());
            ps.setString(3, systemIssuer.toString());
            ps.setString(4, amount.toPlainString());
            ps.setString(5, amount.toPlainString());
            ps.setString(6, zero.toPlainString());
            ps.setLong(7, now.toEpochMilli());
            ps.setLong(8, expiresAt.toEpochMilli());
            ps.setLong(9, now.toEpochMilli());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contracts
                (id,contribution_id,target_uuid,issuer_uuid,reward_amount,flags,state,created_at,activated_at,
                 expires_at,reservation_limit,settlement_claim_id,last_error)
                VALUES(?,?,?,?,?,?,'RESERVED',?,?,?,?,NULL,NULL)
                """)) {
            ps.setString(1, contractId.toString());
            ps.setString(2, contributionId.toString());
            ps.setString(3, targetUuid.toString());
            ps.setString(4, systemIssuer.toString());
            ps.setString(5, amount.toPlainString());
            ps.setString(6, ContractFlag.serialize(Set.of(ContractFlag.PRIVATE, ContractFlag.EXCLUSIVE)));
            ps.setLong(7, now.toEpochMilli());
            ps.setLong(8, now.toEpochMilli());
            ps.setLong(9, expiresAt.toEpochMilli());
            ps.setInt(10, 1);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO contract_allowlist(contract_id,hunter_uuid) VALUES(?,?)")) {
            ps.setString(1, contractId.toString());
            ps.setString(2, hunterUuid.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contract_hunters(contract_id,hunter_uuid,status,accepted_at,abandoned_at)
                VALUES(?,?,'ACTIVE',?,NULL)
                """)) {
            ps.setString(1, contractId.toString());
            ps.setString(2, hunterUuid.toString());
            ps.setLong(3, now.toEpochMilli());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO quest_bounty_links(id,player_uuid,quest_key,contract_id,target_uuid,created_at)
                VALUES(?,?,?,?,?,?)
                """)) {
            ps.setString(1, linkId.toString());
            ps.setString(2, hunterUuid.toString());
            ps.setString(3, questKey);
            ps.setString(4, contractId.toString());
            ps.setString(5, targetUuid.toString());
            ps.setLong(6, now.toEpochMilli());
            ps.executeUpdate();
        }
        history(contractId, hunterUuid, "QUEST_BOUNTY_CREATED",
                "quest=" + questKey + "; reward=" + amount.toPlainString(), now);
        audit(hunterUuid, targetUuid, amount, "QUEST_BOUNTY_CREATED quest=" + questKey, now);

        return new CreateResult(CreateAction.CREATED, contractId, targetUuid, amount);
    }

    private CancelResult cancelTx(UUID hunterUuid, String questKey, Instant now) throws Exception {
        StatusResult latest = readStatus(hunterUuid, questKey);
        if (latest.status() == QuestStatus.NONE) return new CancelResult(false, "NOT_FOUND", null);
        if (latest.status() == QuestStatus.COMPLETED) return new CancelResult(false, "ALREADY_COMPLETED", latest.contractId());
        if (latest.status() != QuestStatus.ACTIVE) return new CancelResult(false, "NOT_ACTIVE", latest.contractId());

        String contractState;
        UUID contributionId;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT state,contribution_id FROM contracts WHERE id=?")) {
            ps.setString(1, latest.contractId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new CancelResult(false, "CONTRACT_NOT_FOUND", latest.contractId());
                contractState = rs.getString("state");
                contributionId = UUID.fromString(rs.getString("contribution_id"));
            }
        }
        if ("CLAIMING".equals(contractState)) return new CancelResult(false, "CONTRACT_SETTLING", latest.contractId());

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE bounty_contributions SET state='CANCELLED',updated_at=? WHERE id=? AND state='ACTIVE'")) {
            ps.setLong(1, now.toEpochMilli());
            ps.setString(2, contributionId.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE contracts SET state='CANCELLED',last_error='Cancelled by quest integration' WHERE id=? AND state IN ('OPEN','RESERVED')")) {
            ps.setString(1, latest.contractId().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE contract_hunters SET status='CANCELLED',abandoned_at=?
                WHERE contract_id=? AND hunter_uuid=? AND status='ACTIVE'
                """)) {
            ps.setLong(1, now.toEpochMilli());
            ps.setString(2, latest.contractId().toString());
            ps.setString(3, hunterUuid.toString());
            ps.executeUpdate();
        }
        history(latest.contractId(), hunterUuid, "QUEST_BOUNTY_CANCELLED", "quest=" + questKey, now);
        audit(hunterUuid, latest.targetUuid(), null, "QUEST_BOUNTY_CANCELLED quest=" + questKey, now);
        return new CancelResult(true, "OK", latest.contractId());
    }

    private StatusResult readStatus(UUID hunterUuid, String questKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT q.contract_id,q.target_uuid,c.state AS contract_state,h.status AS hunter_state
                FROM quest_bounty_links q
                LEFT JOIN contracts c ON c.id=q.contract_id
                LEFT JOIN contract_hunters h ON h.contract_id=q.contract_id AND h.hunter_uuid=q.player_uuid
                WHERE q.player_uuid=? AND q.quest_key=?
                ORDER BY q.created_at DESC LIMIT 1
                """)) {
            ps.setString(1, hunterUuid.toString());
            ps.setString(2, questKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new StatusResult(QuestStatus.NONE, null, null);
                UUID contractId = UUID.fromString(rs.getString("contract_id"));
                UUID targetUuid = UUID.fromString(rs.getString("target_uuid"));
                String contractState = rs.getString("contract_state");
                String hunterState = rs.getString("hunter_state");
                if ("COMPLETED".equals(contractState) && "COMPLETED".equals(hunterState)) {
                    return new StatusResult(QuestStatus.COMPLETED, contractId, targetUuid);
                }
                if (("OPEN".equals(contractState) || "RESERVED".equals(contractState) || "CLAIMING".equals(contractState))
                        && "ACTIVE".equals(hunterState)) {
                    return new StatusResult(QuestStatus.ACTIVE, contractId, targetUuid);
                }
                return new StatusResult(QuestStatus.TERMINAL, contractId, targetUuid);
            }
        }
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
            ps.setString(5, details);
            ps.setLong(6, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private void audit(UUID actorUuid, UUID targetUuid, BigDecimal amount, String details, Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO audit_log(id,actor_uuid,action,target_uuid,amount,details,created_at)
                VALUES(?,?,'QUEST_BOUNTY',?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, actorUuid == null ? null : actorUuid.toString());
            ps.setString(3, targetUuid == null ? null : targetUuid.toString());
            ps.setString(4, amount == null ? null : amount.toPlainString());
            ps.setString(5, details);
            ps.setLong(6, at.toEpochMilli());
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

    public enum QuestStatus { NONE, ACTIVE, COMPLETED, TERMINAL }
    public enum CreateAction { CREATED, REUSED_ACTIVE }

    public record CreateResult(CreateAction action, UUID contractId, UUID targetUuid, BigDecimal amount) {}
    public record CancelResult(boolean success, String reason, UUID contractId) {}
    public record StatusResult(QuestStatus status, UUID contractId, UUID targetUuid) {}

    @FunctionalInterface
    private interface SqlSupplier<T> { T get() throws Exception; }
}
