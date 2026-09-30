package store.cadera.cdrbounty;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.antifarm.AntiFarmService;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.bounty.BountyRefundService;
import store.cadera.cdrbounty.claim.BountyListener;
import store.cadera.cdrbounty.claim.ContractClaimService;
import store.cadera.cdrbounty.command.AdminCommand;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.ContractGuiService;
import store.cadera.cdrbounty.contract.ContractRepository;
import store.cadera.cdrbounty.contract.ContractService;
import store.cadera.cdrbounty.contract.SQLiteContractRepository;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.core.RecoveryService;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.npc.BountyNpcBindingService;
import store.cadera.cdrbounty.npc.BountyNpcService;
import store.cadera.cdrbounty.npc.BountyPlacementWizard;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;
import store.cadera.cdrbounty.storage.BountyRepository;
import store.cadera.cdrbounty.storage.SQLiteBountyRepository;
import store.cadera.cdrbounty.storage.SQLiteMaintenanceRepository;

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
    private BountyNpcBindingService npcBinding;

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
            contractRepository = new SQLiteContractRepository(settings);
            contractRepository.initialize();

            AntiFarmService antiFarm = new AntiFarmService(repository, maintenance, this::settings);
            BountyPlacementService placement = new BountyPlacementService(this, repository, economy, this::settings);
            BountyRefundService refunds = new BountyRefundService(this, maintenance, economy, this::settings);
            ContractService contracts = new ContractService(this, contractRepository, placement, this::settings);
            ContractClaimService claim = new ContractClaimService(
                    this, repository, contractRepository, economy, antiFarm, this::settings);
            ContractGuiService gui = new ContractGuiService(this, contracts, economy);
            npcBinding = new BountyNpcBindingService(this);
            BountyPlacementWizard wizard = new BountyPlacementWizard(this, contracts, economy, this::settings);
            BountyNpcService npcService = new BountyNpcService(this, npcBinding, gui, wizard);

            PluginCommand admin = Objects.requireNonNull(getCommand("cdrbounty"), "cdrbounty command missing from plugin.yml");
            admin.setExecutor(new AdminCommand(this, repository, maintenance, refunds, messages, economy, npcBinding));

            getServer().getPluginManager().registerEvents(
                    new BountyListener(this, repository, claim, messages, economy), this);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(wizard, this);
            getServer().getPluginManager().registerEvents(npcService, this);

            new RecoveryService(this, repository, maintenance, economy, this::settings)
                    .recover()
                    .thenCompose(ignored -> contractRepository.reconcile())
                    .thenCompose(reconciled -> contractRepository.syncTerminalStates(Instant.now()))
                    .thenRun(() -> MainThread.run(this, () -> {
                        getLogger().info("Economy + contract recovery scan completed.");
                        refunds.startExpirationTask();
                        refunds.scanExpired();
                        contracts.startMaintenance();
                    }))
                    .exceptionally(ex -> {
                        getLogger().severe("Recovery scan failed; maintenance was not started: " + rootMessage(ex));
                        return null;
                    });

            getLogger().info("CdrBounty " + getPluginMeta().getVersion()
                    + " enabled with NPC-only player access + SQLite + Vault + Contract Engine.");
        } catch (Exception ex) {
            getLogger().severe("CdrBounty failed to start safely: " + rootMessage(ex));
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (contractRepository != null) contractRepository.close();
        if (maintenance != null) maintenance.close();
        if (repository != null) repository.close();
    }

    public PluginSettings settings() { return settings; }

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
        if (npcBinding != null) npcBinding.load();
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
