package store.cadera.cdrbounty;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.antifarm.AntiFarmService;
import store.cadera.cdrbounty.api.CdrBountyShopApi;
import store.cadera.cdrbounty.approval.ApprovalAdminGui;
import store.cadera.cdrbounty.approval.ApprovalRepository;
import store.cadera.cdrbounty.approval.BountyApprovalService;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.bounty.BountyRefundService;
import store.cadera.cdrbounty.claim.BountyListener;
import store.cadera.cdrbounty.claim.ContractClaimService;
import store.cadera.cdrbounty.command.AdminCommand;
import store.cadera.cdrbounty.command.RootAdminCommand;
import store.cadera.cdrbounty.config.ConfigMigrationService;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.ContractGuiService;
import store.cadera.cdrbounty.contract.ContractRepository;
import store.cadera.cdrbounty.contract.ContractService;
import store.cadera.cdrbounty.contract.SQLiteContractRepository;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.core.RecoveryService;
import store.cadera.cdrbounty.diagnostic.ProductionDiagnosticsService;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.integration.betonquest.BetonQuestBootstrap;
import store.cadera.cdrbounty.npc.BountyNpcBindingService;
import store.cadera.cdrbounty.npc.BountyNpcService;
import store.cadera.cdrbounty.npc.BountyPlacementWizard;
import store.cadera.cdrbounty.quest.QuestBountyRepository;
import store.cadera.cdrbounty.quest.QuestBountyService;
import store.cadera.cdrbounty.reputation.ReputationAutoBountyRepository;
import store.cadera.cdrbounty.reputation.ReputationAutoBountyService;
import store.cadera.cdrbounty.shop.WantedShopService;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;
import store.cadera.cdrbounty.storage.BountyRepository;
import store.cadera.cdrbounty.storage.SQLiteBountyRepository;
import store.cadera.cdrbounty.storage.SQLiteMaintenanceRepository;
import store.cadera.cdrbounty.tracking.BountyTrackerService;
import store.cadera.cdrbounty.tracking.TrackingRepository;

import java.io.File;
import java.time.Instant;
import java.util.Objects;

public final class CdrBountyPlugin extends JavaPlugin {
    private volatile PluginSettings settings;
    private MessageService messages;
    private VaultEconomyAdapter economy;
    private BountyRepository repository;
    private BountyMaintenanceRepository maintenance;
    private ContractRepository contractRepository;
    private ApprovalRepository approvalRepository;
    private TrackingRepository trackingRepository;
    private ReputationAutoBountyRepository reputationBountyRepository;
    private QuestBountyRepository questBountyRepository;
    private BountyNpcBindingService npcBinding;
    private WantedShopService wantedShopService;
    private ProductionDiagnosticsService diagnostics;

    @Override
    public void onEnable() {
        try {
            installDefaultResources();
            ConfigMigrationService.Result migration = new ConfigMigrationService(this).migrate();
            if (migration.migrated()) {
                getLogger().info("Config migration v" + migration.fromVersion() + " -> v" + migration.toVersion()
                        + (migration.backupFile() == null ? "" : "; backup=" + migration.backupFile().getName()));
            }

            settings = PluginSettings.load(this);
            messages = new MessageService(this);
            economy = VaultEconomyAdapter.hook(this);

            repository = new SQLiteBountyRepository(settings);
            repository.initialize();
            maintenance = new SQLiteMaintenanceRepository(settings);
            maintenance.initialize();
            contractRepository = new SQLiteContractRepository(settings);
            contractRepository.initialize();
            approvalRepository = new ApprovalRepository(settings);
            approvalRepository.initialize();
            trackingRepository = new TrackingRepository(settings);
            trackingRepository.initialize();
            reputationBountyRepository = new ReputationAutoBountyRepository(settings);
            reputationBountyRepository.initialize();
            questBountyRepository = new QuestBountyRepository(settings);
            questBountyRepository.initialize();
            wantedShopService = new WantedShopService(this, contractRepository);
            getServer().getServicesManager().register(CdrBountyShopApi.class, wantedShopService, this, ServicePriority.Normal);

            AntiFarmService antiFarm = new AntiFarmService(repository, maintenance, this::settings);
            BountyPlacementService placement = new BountyPlacementService(this, repository, economy, this::settings);
            BountyRefundService refunds = new BountyRefundService(this, maintenance, economy, this::settings);
            BountyApprovalService approvals = new BountyApprovalService(
                    this, approvalRepository, maintenance, economy, this::settings);
            ContractService contracts = new ContractService(
                    this, contractRepository, placement, approvals, this::settings);
            ContractClaimService claim = new ContractClaimService(
                    this, repository, contractRepository, economy, antiFarm, this::settings);
            BountyTrackerService trackers = new BountyTrackerService(this, trackingRepository);
            ReputationAutoBountyService reputationBounties = new ReputationAutoBountyService(this, reputationBountyRepository);
            QuestBountyService questBounties = new QuestBountyService(this, questBountyRepository, trackers, this::settings);
            ContractGuiService gui = new ContractGuiService(this, contracts, economy, trackers);
            ApprovalAdminGui approvalGui = new ApprovalAdminGui(this, approvals, economy);
            npcBinding = new BountyNpcBindingService(this);
            diagnostics = new ProductionDiagnosticsService(this, settings, repository, npcBinding);
            BountyPlacementWizard wizard = new BountyPlacementWizard(this, contracts, economy, this::settings);
            BountyNpcService npcService = new BountyNpcService(this, npcBinding, gui, wizard);

            PluginCommand admin = Objects.requireNonNull(getCommand("cdrbounty"), "cdrbounty command missing from plugin.yml");
            AdminCommand legacyAdmin = new AdminCommand(this, repository, maintenance, refunds, messages, economy, npcBinding);
            admin.setExecutor(new RootAdminCommand(legacyAdmin, approvalGui, diagnostics));

            getServer().getPluginManager().registerEvents(
                    new BountyListener(this, repository, claim, messages, economy), this);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(wizard, this);
            getServer().getPluginManager().registerEvents(npcService, this);
            getServer().getPluginManager().registerEvents(approvalGui, this);
            getServer().getPluginManager().registerEvents(trackers, this);

            boolean questIntegrationRegistered = false;
            if (getServer().getPluginManager().getPlugin("BetonQuest") != null) {
                try {
                    questIntegrationRegistered = BetonQuestBootstrap.register(this, questBounties);
                } catch (LinkageError error) {
                    getLogger().warning("BetonQuest found but quest integration API is incompatible. CdrBounty continues without quest hooks: "
                            + error.getMessage());
                }
            } else {
                getLogger().info("BetonQuest not found; quest bounty hooks are disabled.");
            }

            if (questIntegrationRegistered) {
                getLogger().info("BetonQuest integration registered: cdrbounty_create, cdrbounty_cancel, cdrbounty_has, cdrbounty_active, cdrbounty_completed.");
            }

            new RecoveryService(this, repository, maintenance, economy, this::settings)
                    .recover()
                    .thenCompose(ignored -> approvals.reconcile())
                    .thenCompose(ignored -> contractRepository.reconcile())
                    .thenCompose(reconciled -> contractRepository.syncTerminalStates(Instant.now()))
                    .thenRun(() -> MainThread.run(this, () -> {
                        getLogger().info("Economy + approval + contract recovery scan completed.");
                        trackers.start();
                        reputationBounties.start();
                        wantedShopService.start();
                        refunds.startExpirationTask();
                        contracts.startMaintenance();
                        diagnostics.logStartupReport();
                        approvals.pendingCount().thenAccept(count -> {
                            if (count > 0) getLogger().info(count + " bounty request(s) waiting for admin approval.");
                        });
                    }))
                    .exceptionally(ex -> {
                        getLogger().severe("Recovery scan failed; maintenance was not started: " + rootMessage(ex));
                        return null;
                    });

            boolean floodgate = getServer().getPluginManager().getPlugin("floodgate") != null
                    || getServer().getPluginManager().getPlugin("Floodgate") != null;
            getLogger().info("Crossplay mode: inventory/chat NPC flow enabled; Floodgate detected=" + floodgate + ".");
            getLogger().info("CdrBounty " + getPluginMeta().getVersion()
                    + " enabled with NPC-only access + Approval + Tracking + Reputation + Quest + Shop API + Production Hardening + SQLite + Vault.");
        } catch (Exception ex) {
            getLogger().severe("CdrBounty failed to start safely: " + rootMessage(ex));
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (questBountyRepository != null) questBountyRepository.close();
        if (reputationBountyRepository != null) reputationBountyRepository.close();
        if (trackingRepository != null) trackingRepository.close();
        if (approvalRepository != null) approvalRepository.close();
        if (contractRepository != null) contractRepository.close();
        if (maintenance != null) maintenance.close();
        if (repository != null) repository.close();
    }

    public PluginSettings settings() { return settings; }

    public void reloadRuntimeConfiguration() {
        PluginSettings previous = settings;
        new ConfigMigrationService(this).migrate();
        PluginSettings next = PluginSettings.load(this);
        if (previous != null && (!previous.sqliteFile().equals(next.sqliteFile())
                || previous.sqliteBusyTimeoutMs() != next.sqliteBusyTimeoutMs()
                || !previous.sqliteJournalMode().equals(next.sqliteJournalMode())
                || !previous.sqliteSynchronous().equals(next.sqliteSynchronous()))) {
            throw new IllegalStateException("Storage settings changed. Restart the server to apply storage.yml safely.");
        }
        settings = next;
        messages.reload();
        if (npcBinding != null) npcBinding.load();
        if (wantedShopService != null) wantedShopService.reloadConfiguration();
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
