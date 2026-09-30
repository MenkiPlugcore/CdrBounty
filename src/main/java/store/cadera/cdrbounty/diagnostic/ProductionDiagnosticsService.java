package store.cadera.cdrbounty.diagnostic;

import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.npc.BountyNpcBindingService;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Production health checks that do not mutate gameplay state. */
public final class ProductionDiagnosticsService {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final BountyRepository repository;
    private final BountyNpcBindingService npcBinding;

    public ProductionDiagnosticsService(JavaPlugin plugin, PluginSettings settings,
                                        BountyRepository repository, BountyNpcBindingService npcBinding) {
        this.plugin = plugin;
        this.settings = settings;
        this.repository = repository;
        this.npcBinding = npcBinding;
    }

    public CompletableFuture<Report> run() {
        CompletableFuture<DbReport> database = CompletableFuture.supplyAsync(this::inspectDatabase);
        CompletableFuture<Integer> unresolved = repository.unresolvedEconomyOperations().thenApply(List::size);
        return database.thenCombine(unresolved, (db, unresolvedOps) -> new Report(
                db.integrity(), db.orphanContracts(), db.orphanHunters(), db.orphanConditions(),
                db.orphanQuestLinks(), unresolvedOps, npcBinding.info(),
                installed("Citizens"), installed("Vault"), installed("CdrReputation"),
                installed("BetonQuest"), installed("CdrQuestJournal"), installed("floodgate") || installed("Floodgate"),
                db.notes()));
    }

    public void logStartupReport() {
        run().thenAccept(report -> {
            if (report.healthy()) {
                plugin.getLogger().info("Production diagnostics: OK (SQLite=" + report.integrity() + ", NPC=" + report.npc() + ")");
            } else {
                plugin.getLogger().warning("Production diagnostics found issues: " + report.summary());
            }
        }).exceptionally(error -> {
            plugin.getLogger().warning("Production diagnostics failed: " + root(error));
            return null;
        });
    }

    private DbReport inspectDatabase() {
        List<String> notes = new ArrayList<>();
        String integrity = "UNKNOWN";
        int orphanContracts = -1;
        int orphanHunters = -1;
        int orphanConditions = -1;
        int orphanQuestLinks = -1;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + settings.sqliteFile())) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                integrity = rs.next() ? rs.getString(1) : "NO_RESULT";
            }
            orphanContracts = count(connection, """
                    SELECT COUNT(*) FROM contracts c
                    LEFT JOIN bounty_contributions b ON b.id=c.contribution_id
                    WHERE b.id IS NULL
                    """);
            orphanHunters = count(connection, """
                    SELECT COUNT(*) FROM contract_hunters h
                    LEFT JOIN contracts c ON c.id=h.contract_id
                    WHERE c.id IS NULL
                    """);
            orphanConditions = count(connection, """
                    SELECT COUNT(*) FROM contract_conditions cc
                    LEFT JOIN contracts c ON c.id=cc.contract_id
                    WHERE c.id IS NULL
                    """);
            try {
                orphanQuestLinks = count(connection, """
                        SELECT COUNT(*) FROM quest_bounty_links q
                        LEFT JOIN contracts c ON c.id=q.contract_id
                        WHERE c.id IS NULL
                        """);
            } catch (Exception ex) {
                orphanQuestLinks = 0;
                notes.add("quest_bounty_links unavailable: " + root(ex));
            }
        } catch (Exception ex) {
            notes.add("database inspection failed: " + root(ex));
        }
        return new DbReport(integrity, orphanContracts, orphanHunters, orphanConditions, orphanQuestLinks, List.copyOf(notes));
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean installed(String name) {
        return plugin.getServer().getPluginManager().getPlugin(name) != null;
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record DbReport(String integrity, int orphanContracts, int orphanHunters,
                            int orphanConditions, int orphanQuestLinks, List<String> notes) {}

    public record Report(String integrity, int orphanContracts, int orphanHunters, int orphanConditions,
                         int orphanQuestLinks, int unresolvedEconomyOperations, String npc,
                         boolean citizens, boolean vault, boolean reputation, boolean betonQuest,
                         boolean questJournal, boolean floodgate, List<String> notes) {
        public boolean healthy() {
            return "ok".equalsIgnoreCase(integrity)
                    && orphanContracts == 0 && orphanHunters == 0 && orphanConditions == 0
                    && orphanQuestLinks == 0 && unresolvedEconomyOperations == 0
                    && npc != null && npc.contains("status=OK");
        }

        public String summary() {
            return "integrity=" + integrity
                    + ", orphanContracts=" + orphanContracts
                    + ", orphanHunters=" + orphanHunters
                    + ", orphanConditions=" + orphanConditions
                    + ", orphanQuestLinks=" + orphanQuestLinks
                    + ", unresolvedEconomy=" + unresolvedEconomyOperations
                    + ", npc=" + npc;
        }
    }
}
