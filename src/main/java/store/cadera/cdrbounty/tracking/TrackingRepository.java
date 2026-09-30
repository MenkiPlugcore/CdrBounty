package store.cadera.cdrbounty.tracking;

import store.cadera.cdrbounty.config.PluginSettings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tracking-specific persistence layered on top of the existing CdrBounty SQLite schema.
 * It owns only the pause heartbeat table and never duplicates contract ownership.
 */
public final class TrackingRepository implements AutoCloseable {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public TrackingRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CdrBounty-Tracking-SQLite");
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
                    CREATE TABLE IF NOT EXISTS contract_tracking_pause (
                      contract_id TEXT PRIMARY KEY,
                      target_uuid TEXT NOT NULL,
                      last_tick_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracking_pause_target ON contract_tracking_pause(target_uuid)");
        }
    }

    public CompletableFuture<List<TrackingContract>> activeForHunter(UUID hunterUuid, Instant now) {
        return supplyAsync(() -> {
            List<TrackingContract> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.id,c.target_uuid,c.expires_at,
                           CASE WHEN p.contract_id IS NULL THEN 0 ELSE 1 END AS paused
                    FROM contracts c
                    JOIN contract_hunters h ON h.contract_id=c.id
                    LEFT JOIN contract_tracking_pause p ON p.contract_id=c.id
                    WHERE h.hunter_uuid=? AND h.status='ACTIVE'
                      AND c.state IN ('OPEN','RESERVED') AND c.expires_at>?
                    ORDER BY h.accepted_at ASC
                    """)) {
                ps.setString(1, hunterUuid.toString());
                ps.setLong(2, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new TrackingContract(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("target_uuid")),
                                Instant.ofEpochMilli(rs.getLong("expires_at")),
                                rs.getInt("paused") == 1
                        ));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<List<UUID>> activeTargets() {
        return supplyAsync(() -> {
            List<UUID> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT DISTINCT target_uuid FROM contracts
                    WHERE state IN ('OPEN','RESERVED')
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(UUID.fromString(rs.getString(1)));
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * Applies one pause heartbeat. While a target remains unavailable the absolute deadline is
     * pushed forward by the elapsed heartbeat duration. This keeps both the contract and its
     * escrow contribution from expiring while the target is un-huntable.
     */
    public CompletableFuture<Integer> applyAvailability(Map<UUID, Boolean> availability, Instant now) {
        Map<UUID, Boolean> safe = Map.copyOf(availability);
        return supplyAsync(() -> inTransactionResult(() -> {
            int changed = 0;
            try (PreparedStatement cleanup = connection.prepareStatement("""
                    DELETE FROM contract_tracking_pause
                    WHERE contract_id NOT IN (
                      SELECT id FROM contracts WHERE state IN ('OPEN','RESERVED')
                    )
                    """)) {
                changed += cleanup.executeUpdate();
            }

            for (Map.Entry<UUID, Boolean> entry : safe.entrySet()) {
                for (ContractRow row : activeContractsForTarget(entry.getKey())) {
                    Long lastTick = pauseTick(row.contractId());
                    if (!entry.getValue()) {
                        if (lastTick == null) {
                            insertPause(row.contractId(), row.targetUuid(), now.toEpochMilli());
                            history(row.contractId(), null, "TIMER_PAUSED", "target unavailable", now);
                            changed++;
                        } else {
                            long delta = Math.max(0L, now.toEpochMilli() - lastTick);
                            if (delta > 0L) {
                                extendDeadlines(row, delta, now.toEpochMilli());
                                updatePauseTick(row.contractId(), now.toEpochMilli());
                                changed++;
                            }
                        }
                    } else if (lastTick != null) {
                        long delta = Math.max(0L, now.toEpochMilli() - lastTick);
                        if (delta > 0L) extendDeadlines(row, delta, now.toEpochMilli());
                        deletePause(row.contractId());
                        history(row.contractId(), null, "TIMER_RESUMED", "target available", now);
                        changed++;
                    }
                }
            }
            return changed;
        }));
    }

    private List<ContractRow> activeContractsForTarget(UUID targetUuid) throws SQLException {
        List<ContractRow> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id,contribution_id,target_uuid FROM contracts
                WHERE target_uuid=? AND state IN ('OPEN','RESERVED')
                """)) {
            ps.setString(1, targetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ContractRow(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("contribution_id")),
                            UUID.fromString(rs.getString("target_uuid"))
                    ));
                }
            }
        }
        return result;
    }

    private Long pauseTick(UUID contractId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_tick_at FROM contract_tracking_pause WHERE contract_id=?")) {
            ps.setString(1, contractId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertPause(UUID contractId, UUID targetUuid, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO contract_tracking_pause(contract_id,target_uuid,last_tick_at)
                VALUES(?,?,?)
                """)) {
            ps.setString(1, contractId.toString());
            ps.setString(2, targetUuid.toString());
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private void updatePauseTick(UUID contractId, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE contract_tracking_pause SET last_tick_at=? WHERE contract_id=?")) {
            ps.setLong(1, now);
            ps.setString(2, contractId.toString());
            ps.executeUpdate();
        }
    }

    private void deletePause(UUID contractId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM contract_tracking_pause WHERE contract_id=?")) {
            ps.setString(1, contractId.toString());
            ps.executeUpdate();
        }
    }

    private void extendDeadlines(ContractRow row, long deltaMillis, long nowMillis) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE contracts SET expires_at=expires_at+?
                WHERE id=? AND state IN ('OPEN','RESERVED')
                """)) {
            ps.setLong(1, deltaMillis);
            ps.setString(2, row.contractId().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bounty_contributions SET expires_at=expires_at+?,updated_at=?
                WHERE id=? AND state='ACTIVE'
                """)) {
            ps.setLong(1, deltaMillis);
            ps.setLong(2, nowMillis);
            ps.setString(3, row.contributionId().toString());
            ps.executeUpdate();
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

    private <T> T inTransactionResult(SqlSupplier<T> supplier) throws Exception {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = supplier.get();
            connection.commit();
            return result;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previous);
        }
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

    public record TrackingContract(UUID contractId, UUID targetUuid, Instant expiresAt, boolean paused) {}

    private record ContractRow(UUID contractId, UUID contributionId, UUID targetUuid) {}

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
