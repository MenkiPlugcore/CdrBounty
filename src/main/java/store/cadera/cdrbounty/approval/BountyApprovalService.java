package store.cadera.cdrbounty.approval;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
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
import java.util.function.Supplier;

public final class BountyApprovalService {
    private final JavaPlugin plugin;
    private final ApprovalRepository repository;
    private final BountyMaintenanceRepository maintenance;
    private final VaultEconomyAdapter economy;
    private final Supplier<PluginSettings> settings;

    public BountyApprovalService(JavaPlugin plugin, ApprovalRepository repository,
                                 BountyMaintenanceRepository maintenance,
                                 VaultEconomyAdapter economy,
                                 Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.maintenance = maintenance;
        this.economy = economy;
        this.settings = settings;
    }

    public CompletableFuture<Void> submit(UUID contractId, BigDecimal rewardAmount) {
        return repository.submit(contractId, rewardAmount, Instant.now());
    }

    public CompletableFuture<List<ApprovalRepository.ApprovalRequest>> pending(int limit) {
        return repository.listPending(limit);
    }

    public CompletableFuture<ApprovalRepository.ApprovalRequest> pending(UUID contractId) {
        return repository.getPending(contractId);
    }

    public CompletableFuture<ApprovalRepository.ActionResult> approve(Player actor, UUID contractId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(settings.get().durationSeconds());
        return repository.approve(contractId, actor.getUniqueId(), now, expiresAt);
    }

    public CompletableFuture<ApprovalRepository.ActionResult> reject(Player actor, UUID contractId) {
        return repository.getPending(contractId).thenCompose(request -> {
            if (request == null) {
                return CompletableFuture.completedFuture(ApprovalRepository.ActionResult.fail("NOT_PENDING"));
            }
            UUID issuerId = request.contract().issuerUuid();
            return MainThread.call(plugin, () -> {
                OfflinePlayer issuer = plugin.getServer().getOfflinePlayer(issuerId);
                BigDecimal before = normalize(economy.balance(issuer));
                return new RejectContext(issuer, before);
            }).thenCompose(context -> {
                UUID operationId = UUID.randomUUID();
                return repository.reserveRejection(
                                contractId, actor.getUniqueId(), operationId, context.balanceBefore(), Instant.now())
                        .thenCompose(preparation -> MainThread.call(plugin, () -> {
                            VaultEconomyAdapter.Result result = economy.deposit(context.issuer(), preparation.refundAmount());
                            BigDecimal after = normalize(economy.balance(context.issuer()));
                            return new DepositResult(preparation, operationId, result, after);
                        }))
                        .thenCompose(deposit -> {
                            if (!deposit.result().success()) {
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "Approval rejection refund failed and remains recoverable: "
                                                + deposit.result().safeError()));
                            }
                            return maintenance.completeRefund(
                                            deposit.preparation().contributionId(),
                                            deposit.operationId(),
                                            deposit.balanceAfter(),
                                            Instant.now())
                                    .thenCompose(ignored -> repository.completeRejection(
                                            contractId, actor.getUniqueId(), "Rejected by administrator", Instant.now()))
                                    .thenApply(ignored -> ApprovalRepository.ActionResult.ok());
                        });
            });
        });
    }

    public CompletableFuture<Integer> reconcile() {
        return repository.reconcile().thenCompose(changed -> autoOpenPending(200)
                .thenApply(opened -> changed + opened));
    }

    private CompletableFuture<Integer> autoOpenPending(int limit) {
        return repository.listPending(limit).thenCompose(requests -> {
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (ApprovalRepository.ApprovalRequest request : requests) {
                chain = chain.thenCompose(count -> {
                    Instant now = Instant.now();
                    Instant expiresAt = now.plusSeconds(settings.get().durationSeconds());
                    return repository.approve(
                                    request.contract().id(),
                                    BountyMaintenanceRepository.SYSTEM_ISSUER,
                                    now,
                                    expiresAt)
                            .thenApply(result -> count + (result.success() ? 1 : 0));
                });
            }
            return chain;
        });
    }

    public CompletableFuture<Integer> pendingCount() {
        return repository.pendingCount();
    }

    private BigDecimal normalize(BigDecimal value) {
        return MoneyMath.normalize(value, settings.get().decimalScale());
    }

    private record RejectContext(OfflinePlayer issuer, BigDecimal balanceBefore) {}
    private record DepositResult(ApprovalRepository.RejectPreparation preparation, UUID operationId,
                                 VaultEconomyAdapter.Result result, BigDecimal balanceAfter) {}
}
