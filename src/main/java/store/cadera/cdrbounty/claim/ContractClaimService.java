package store.cadera.cdrbounty.claim;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.antifarm.AntiFarmService;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.ContractRepository;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ContractClaimService {
    private final JavaPlugin plugin;
    private final BountyRepository bountyRepository;
    private final ContractRepository contracts;
    private final VaultEconomyAdapter economy;
    private final AntiFarmService antiFarm;
    private final Supplier<PluginSettings> settings;

    public ContractClaimService(JavaPlugin plugin, BountyRepository bountyRepository, ContractRepository contracts,
                                VaultEconomyAdapter economy, AntiFarmService antiFarm,
                                Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.bountyRepository = bountyRepository;
        this.contracts = contracts;
        this.economy = economy;
        this.antiFarm = antiFarm;
        this.settings = settings;
    }

    public CompletableFuture<Result> claim(Player victim, Player killer) {
        Instant now = Instant.now();
        PluginSettings cfg = settings.get();
        if (killer == null) return CompletableFuture.completedFuture(Result.noBounty());
        if (killer.getUniqueId().equals(victim.getUniqueId())) return CompletableFuture.completedFuture(Result.blocked("SELF_KILL"));
        if (cfg.claimWorldBlocked(victim.getWorld().getName())) return CompletableFuture.completedFuture(Result.blocked("WORLD_BLOCKED"));

        String world = victim.getWorld().getName();
        String weapon = killer.getInventory().getItemInMainHand().getType().name();
        return contracts.claimableAmount(victim.getUniqueId(), killer.getUniqueId(), world, weapon, now)
                .thenCompose(total -> {
                    if (total.signum() <= 0) return CompletableFuture.completedFuture(Result.noBounty());
                    return MainThread.call(plugin, () -> antiFarm.evaluate(killer, victim, now))
                            .thenCompose(Function.identity())
                            .thenCompose(decision -> {
                                if (!decision.allowed()) return CompletableFuture.completedFuture(Result.blocked(decision.reason()));
                                return prepareAndPay(victim, killer, world, weapon, now);
                            });
                })
                .exceptionally(ex -> Result.failed(rootMessage(ex)));
    }

    private CompletableFuture<Result> prepareAndPay(Player victim, Player killer, String world,
                                                     String weapon, Instant now) {
        UUID claimId = UUID.randomUUID();
        UUID payoutOperationId = UUID.randomUUID();
        return MainThread.call(plugin, () -> normalize(economy.balance(killer)))
                .thenCompose(balanceBefore -> contracts.prepareClaim(
                        claimId, payoutOperationId,
                        victim.getUniqueId(), killer.getUniqueId(),
                        world, weapon, now, balanceBefore))
                .thenCompose(preparation -> MainThread.call(plugin, () -> {
                    VaultEconomyAdapter.Result deposit = economy.deposit(killer, preparation.amount());
                    BigDecimal balanceAfter = normalize(economy.balance(killer));
                    return new Payout(preparation, deposit, balanceAfter);
                }))
                .thenCompose(payout -> {
                    if (!payout.result().success()) {
                        return bountyRepository.failClaim(claimId, payoutOperationId, payout.result().safeError())
                                .thenCompose(ignored -> contracts.releaseContractsForClaim(
                                        claimId, payout.result().safeError(), Instant.now()))
                                .handle((ignored, error) -> Result.failed("ECONOMY_DEPOSIT_FAILED"));
                    }
                    Instant completed = Instant.now();
                    return bountyRepository.completeClaim(
                                    claimId, payoutOperationId, payout.balanceAfter(), completed,
                                    settings.get().repeatedPairWindowSeconds())
                            .thenCompose(ignored -> contracts.completeContractsForClaim(claimId, completed)
                                    .handle((v, contractError) -> {
                                        if (contractError != null) {
                                            plugin.getLogger().warning("Claim " + claimId
                                                    + " paid; contract finalization deferred to recovery: "
                                                    + rootMessage(contractError));
                                        }
                                        return Result.paid(payout.preparation().amount());
                                    }));
                });
    }

    private BigDecimal normalize(BigDecimal amount) {
        return MoneyMath.normalize(amount, settings.get().decimalScale());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record Result(Status status, String reason, BigDecimal amount) {
        public enum Status { PAID, BLOCKED, NO_BOUNTY, FAILED }
        public static Result paid(BigDecimal amount) { return new Result(Status.PAID, "OK", amount); }
        public static Result blocked(String reason) { return new Result(Status.BLOCKED, reason, BigDecimal.ZERO); }
        public static Result noBounty() { return new Result(Status.NO_BOUNTY, "NO_ELIGIBLE_BOUNTY", BigDecimal.ZERO); }
        public static Result failed(String reason) { return new Result(Status.FAILED, reason, BigDecimal.ZERO); }
    }

    private record Payout(ContractRepository.ClaimPreparation preparation,
                          VaultEconomyAdapter.Result result, BigDecimal balanceAfter) {}
}
