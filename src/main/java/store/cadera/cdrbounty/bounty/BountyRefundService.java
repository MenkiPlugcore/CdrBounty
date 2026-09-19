package store.cadera.cdrbounty.bounty;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class BountyRefundService {
    private final JavaPlugin plugin;
    private final BountyMaintenanceRepository maintenance;
    private final VaultEconomyAdapter economy;
    private final Supplier<PluginSettings> settings;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    public BountyRefundService(JavaPlugin plugin, BountyMaintenanceRepository maintenance,
                               VaultEconomyAdapter economy, Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.maintenance = maintenance;
        this.economy = economy;
        this.settings = settings;
    }

    public void startExpirationTask() {
        long periodTicks = Math.max(20L, settings.get().expirationScanSeconds() * 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanExpired, periodTicks, periodTicks);
    }

    public void scanExpired() {
        if (!scanRunning.compareAndSet(false, true)) return;
        expireDue().whenComplete((count, error) -> {
            scanRunning.set(false);
            if (error != null) plugin.getLogger().warning("Expiration scan failed: " + rootMessage(error));
            else if (settings.get().debug() && count > 0) plugin.getLogger().info("Processed " + count + " expired bounty contributions.");
        });
    }

    public CompletableFuture<Integer> expireDue() {
        Instant now = Instant.now();
        return maintenance.findExpired(now, 100).thenCompose(candidates ->
                processAll(candidates, BountyState.EXPIRED, settings.get().expireRefundPolicy(), null, "EXPIRATION")
                        .thenApply(total -> candidates.size()));
    }

    public CompletableFuture<BigDecimal> cancelAll(UUID actorUuid, UUID targetUuid) {
        return maintenance.activeContributions(targetUuid).thenCompose(candidates ->
                processAll(candidates, BountyState.CANCELLED, settings.get().cancelRefundPolicy(), actorUuid, "ADMIN_CANCEL"));
    }

    private CompletableFuture<BigDecimal> processAll(List<BountyMaintenanceRepository.RefundCandidate> candidates,
                                                      BountyState state,
                                                      PluginSettings.RefundPolicy policy,
                                                      UUID actorUuid,
                                                      String reason) {
        CompletableFuture<BigDecimal> chain = CompletableFuture.completedFuture(BigDecimal.ZERO);
        for (BountyMaintenanceRepository.RefundCandidate candidate : candidates) {
            chain = chain.thenCompose(total -> processOne(candidate, state, policy, actorUuid, reason)
                    .thenApply(ignored -> total.add(candidate.amount())));
        }
        return chain;
    }

    private CompletableFuture<Void> processOne(BountyMaintenanceRepository.RefundCandidate candidate,
                                               BountyState state,
                                               PluginSettings.RefundPolicy policy,
                                               UUID actorUuid,
                                               String reason) {
        if (BountyMaintenanceRepository.SYSTEM_ISSUER.equals(candidate.issuerUuid())
                || policy == PluginSettings.RefundPolicy.NONE) {
            return maintenance.voidContribution(candidate.contributionId(), state, actorUuid, reason, Instant.now());
        }

        UUID operationId = UUID.randomUUID();
        return MainThread.call(plugin, () -> {
            OfflinePlayer issuer = plugin.getServer().getOfflinePlayer(candidate.issuerUuid());
            return new BalanceContext(issuer, normalize(economy.balance(issuer)));
        }).thenCompose(context -> maintenance.reserveRefund(
                        candidate.contributionId(), state, operationId, context.balanceBefore(), Instant.now())
                .thenCompose(ignored -> MainThread.call(plugin, () -> {
                    VaultEconomyAdapter.Result result = economy.deposit(context.player(), candidate.amount());
                    return new RefundDeposit(result, normalize(economy.balance(context.player())));
                }))
                .thenCompose(deposit -> {
                    if (!deposit.result().success()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Refund deposit failed and remains recoverable: " + deposit.result().safeError()));
                    }
                    return maintenance.completeRefund(
                            candidate.contributionId(), operationId, deposit.balanceAfter(), Instant.now());
                }));
    }

    private BigDecimal normalize(BigDecimal amount) {
        return MoneyMath.normalize(amount, settings.get().decimalScale());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record BalanceContext(OfflinePlayer player, BigDecimal balanceBefore) {}
    private record RefundDeposit(VaultEconomyAdapter.Result result, BigDecimal balanceAfter) {}
}
