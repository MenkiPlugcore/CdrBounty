package store.cadera.cdrbounty.contract;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.approval.BountyApprovalService;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ContractService {
    private final JavaPlugin plugin;
    private final ContractRepository repository;
    private final BountyPlacementService placement;
    private final BountyApprovalService approvals;
    private final Supplier<PluginSettings> settings;

    public ContractService(JavaPlugin plugin, ContractRepository repository, BountyPlacementService placement,
                           BountyApprovalService approvals, Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.placement = placement;
        this.approvals = approvals;
        this.settings = settings;
    }

    public CompletableFuture<CreateResult> create(Player issuer, OfflinePlayer target, BigDecimal amount,
                                                   Set<ContractFlag> flags, Set<UUID> allowedHunters,
                                                   List<ContractCondition> conditions) {
        EnumSet<ContractFlag> normalized = EnumSet.of(ContractFlag.PUBLIC);
        if (flags != null && flags.contains(ContractFlag.EXCLUSIVE)) normalized.add(ContractFlag.EXCLUSIVE);
        if (flags != null && flags.contains(ContractFlag.ANONYMOUS)) normalized.add(ContractFlag.ANONYMOUS);
        Set<ContractFlag> safeFlags = Set.copyOf(normalized);
        Set<UUID> safeAllowed = Set.of();
        List<ContractCondition> safeConditions = conditions == null ? List.of() : List.copyOf(conditions);

        if (safeFlags.contains(ContractFlag.ANONYMOUS) && !issuer.hasPermission("cdrbounty.contract.anonymous")) {
            return CompletableFuture.completedFuture(CreateResult.fail("NO_PERMISSION_ANONYMOUS"));
        }
        if (safeFlags.contains(ContractFlag.EXCLUSIVE) && !issuer.hasPermission("cdrbounty.contract.exclusive")) {
            return CompletableFuture.completedFuture(CreateResult.fail("NO_PERMISSION_EXCLUSIVE"));
        }
        if (safeConditions.size() > 16) {
            return CompletableFuture.completedFuture(CreateResult.fail("TOO_MANY_CONDITIONS"));
        }

        PluginSettings cfg = settings.get();
        BigDecimal gross;
        BigDecimal escrow;
        try {
            gross = MoneyMath.normalize(amount, cfg.decimalScale());
            BigDecimal fee = MoneyMath.fee(gross, cfg.placementFeePercent(), cfg.decimalScale());
            escrow = MoneyMath.escrow(gross, fee, cfg.decimalScale());
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(CreateResult.fail("INVALID_AMOUNT"));
        }

        Instant now = Instant.now();
        UUID contributionId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        int reservationLimit = safeFlags.contains(ContractFlag.EXCLUSIVE) ? 1 : cfg.publicReservationLimit();
        BountyContract draft = new BountyContract(
                contractId, contributionId,
                target.getUniqueId(), issuer.getUniqueId(), escrow,
                safeFlags, ContractState.DRAFT,
                now, null, now.plusSeconds(cfg.durationSeconds()),
                reservationLimit, null, null
        );

        return repository.createDraft(draft, safeAllowed, safeConditions)
                .thenCompose(ignored -> placement.placeWithContributionId(issuer, target, gross, contributionId))
                .thenCompose(result -> {
                    if (!result.success()) {
                        return repository.voidDraft(contractId, result.reason())
                                .handle((ignored, error) -> CreateResult.fail(result.reason()));
                    }
                    Instant activatedAt = Instant.now();
                    return repository.open(contractId, result.rewardAmount(), activatedAt)
                            .handle((ignored, error) -> {
                                if (error != null) {
                                    plugin.getLogger().warning("Contract " + contractId
                                            + " funded but direct OPEN is pending recovery: " + rootMessage(error));
                                    return CreateResult.recoveryPending(contractId, result.rewardAmount());
                                }
                                MainThread.run(plugin, () -> {
                                    if (issuer.isOnline()) {
                                        issuer.sendMessage("§aBounty langsung aktif dan tersedia di Bounty Board.");
                                    }
                                });
                                return CreateResult.opened(contractId, result.rewardAmount());
                            });
                });
    }

    public CompletableFuture<ContractRepository.ActionResult> accept(Player hunter, UUID contractId) {
        return repository.accept(contractId, hunter.getUniqueId(), Instant.now(), settings.get().maxActiveContractsPerHunter());
    }

    public CompletableFuture<ContractRepository.ActionResult> abandon(Player hunter, UUID contractId) {
        return repository.abandon(contractId, hunter.getUniqueId(), Instant.now());
    }

    public CompletableFuture<List<ContractRepository.ContractView>> browse(UUID viewerUuid, boolean admin, int limit) {
        return repository.listVisible(viewerUuid, admin, Instant.now(), limit);
    }

    public CompletableFuture<java.util.Optional<ContractRepository.ContractView>> get(UUID contractId, UUID viewerUuid, boolean admin) {
        return repository.get(contractId, viewerUuid, admin);
    }

    public CompletableFuture<BigDecimal> visibleTotal(UUID targetUuid, UUID viewerUuid, boolean admin) {
        return repository.visibleTotal(targetUuid, viewerUuid, admin, Instant.now());
    }

    public CompletableFuture<List<ContractRepository.VisibleTargetTotal>> visibleTotals(UUID viewerUuid, boolean admin, int limit) {
        return repository.visibleTargetTotals(viewerUuid, admin, Instant.now(), limit);
    }

    public CompletableFuture<Integer> reconcile() { return repository.reconcile(); }

    public void startMaintenance() {
        long ticks = Math.max(20L, settings.get().contractSyncSeconds() * 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () ->
                repository.syncTerminalStates(Instant.now())
                        .thenCompose(ignored -> approvals.reconcile())
                        .thenCompose(ignored -> repository.reconcile())
                        .exceptionally(ex -> {
                            plugin.getLogger().warning("Contract maintenance failed: " + rootMessage(ex));
                            return 0;
                        }), ticks, ticks);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record CreateResult(boolean success, String reason, UUID contractId, BigDecimal rewardAmount) {
        public static CreateResult opened(UUID id, BigDecimal amount) {
            return new CreateResult(true, "OPEN", id, amount);
        }
        public static CreateResult recoveryPending(UUID id, BigDecimal amount) {
            return new CreateResult(true, "OPEN_RECOVERY_PENDING", id, amount);
        }
        public static CreateResult fail(String reason) { return new CreateResult(false, reason, null, BigDecimal.ZERO); }
    }
}
