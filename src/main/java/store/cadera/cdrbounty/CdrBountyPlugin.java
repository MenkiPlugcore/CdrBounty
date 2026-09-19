package store.cadera.cdrbounty;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.antifarm.AntiFarmService;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.bounty.BountyRefundService;
import store.cadera.cdrbounty.claim.BountyClaimService;
import store.cadera.cdrbounty.claim.BountyListener;
import store.cadera.cdrbounty.command.AdminCommand;
import store.cadera.cdrbounty.command.BountyCommand;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.core.RecoveryService;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;
import store.cadera.cdrbounty.storage.BountyRepository;
import store.cadera.cdrbounty.storage.SQLiteBountyRepository;
import store.cadera.cdrbounty.storage.SQLiteMaintenanceRepository;

import java.io.File;
import java.util.Objects;

public final class CdrBountyPlugin extends JavaPlugin {
    private volatile PluginSettings settings;
    private MessageService messages;
    private VaultEconomyAdapter economy;
    private BountyRepository repository;
    private BountyMaintenanceRepository maintenance;

    @Override
    public void onEnable() {
        try {
            installDefaultResources();
            settings = PluginSettings.load(this);
            messages = new MessageService(this);
            economy = VaultEconomyAdapter.hook(this);

            repository = new SQLiteBountyRepository(settings);
            repository.initialize();
            maintenance = new SQLiteMaintenanceRepository(settings);
            maintenance.initialize();

            AntiFarmService antiFarm = new AntiFarmService(repository, maintenance, this::settings);
            BountyPlacementService placement = new BountyPlacementService(this, repository, economy, this::settings);
            BountyClaimService claim = new BountyClaimService(this, repository, economy, antiFarm, this::settings);
            BountyRefundService refunds = new BountyRefundService(this, maintenance, economy, this::settings);

            PluginCommand bounty = Objects.requireNonNull(getCommand("bounty"), "bounty command missing from plugin.yml");
            bounty.setExecutor(new BountyCommand(this, repository, placement, messages, economy));

            PluginCommand admin = Objects.requireNonNull(getCommand("cdrbounty"), "cdrbounty command missing from plugin.yml");
            admin.setExecutor(new AdminCommand(this, repository, maintenance, refunds, messages, economy));

            getServer().getPluginManager().registerEvents(
                    new BountyListener(this, repository, claim, messages, economy), this);

            new RecoveryService(this, repository, maintenance, economy, this::settings)
                    .recover()
                    .thenRun(() -> MainThread.run(this, () -> {
                        getLogger().info("Economy recovery scan completed.");
                        refunds.startExpirationTask();
                        refunds.scanExpired();
                    }))
                    .exceptionally(ex -> {
                        getLogger().severe("Economy recovery scan failed; expiration processing was not started: " + rootMessage(ex));
                        return null;
                    });

            getLogger().info("CdrBounty 0.1.0-beta.1 enabled with SQLite + Vault economy.");
        } catch (Exception ex) {
            getLogger().severe("CdrBounty failed to start safely: " + rootMessage(ex));
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (maintenance != null) maintenance.close();
        if (repository != null) repository.close();
    }

    public PluginSettings settings() {
        return settings;
    }

    public void reloadRuntimeConfiguration() {
        PluginSettings previous = settings;
        PluginSettings next = PluginSettings.load(this);
        if (previous != null && (!previous.sqliteFile().equals(next.sqliteFile())
                || previous.sqliteBusyTimeoutMs() != next.sqliteBusyTimeoutMs()
                || !previous.sqliteJournalMode().equals(next.sqliteJournalMode())
                || !previous.sqliteSynchronous().equals(next.sqliteSynchronous()))) {
            throw new IllegalStateException("Storage settings changed. Restart the server to apply storage.yml safely.");
        }
        settings = next;
        messages.reload();
    }

    private void installDefaultResources() {
        saveDefaultConfig();
        saveIfMissing("messages.yml");
        saveIfMissing("storage.yml");
        saveIfMissing("gui.yml");
    }

    private void saveIfMissing(String resource) {
        File target = new File(getDataFolder(), resource);
        if (!target.exists()) saveResource(resource, false);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
