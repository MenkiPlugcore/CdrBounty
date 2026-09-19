package store.cadera.cdrbounty.bounty;

import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class BountyPlacementService {
    private final JavaPlugin plugin;
    private final BountyRepository repository;
    private final VaultEconomyAdapter economy;
    private final Supplier<PluginSettings> settings;

    public BountyPlacementService(JavaPlugin plugin, BountyRepository repository, VaultEconomyAdapter economy,
                                  Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.settings = settings;
    }

    public CompletableFuture<Result> place(Player issuer, OfflinePlayer target, BigDecimal rawAmount) {
        return MainThread.call(plugin, () -> validateInitial(issuer, target, rawAmount))
                .thenCompose(initial -> {
                    if (!initial.success()) return CompletableFuture.completedFuture(initial);
                    Instant now = Instant.now();
                    return repository.activeTotal(target.getUniqueId(), now)
                            .thenCompose(active -> MainThread.call(plugin, () -> prepare(issuer, target, rawAmount, active, now)))
                            .thenCompose(preparation -> {
                                if (preparation.failure() != null) {
                                    return CompletableFuture.completedFuture(preparation.failure());
                                }
                                Pending pending = preparation.pending();
                                return repository.createPendingPlacement(
                                                pending.contribution(), pending.operationId(), pending.balanceBefore())
                                        .thenCompose(ignored -> MainThread.call(plugin, () -> {
                                            VaultEconomyAdapter.Result withdrawal = economy.withdraw(issuer, pending.contribution().grossAmount());
                                            BigDecimal after = normalize(economy.balance(issuer));
                                            return new Withdrawal(withdrawal, after);
                                        }))
                                        .thenCompose(withdrawal -> {
                                            if (!withdrawal.result().success()) {
                                                return repository.failPlacement(
                                                                pending.contribution().id(),
                                                                pending.operationId(),
                                                                withdrawal.result().safeError())
                                                        .thenApply(ignored -> Result.fail("ECONOMY_WITHDRAW_FAILED"));
                                            }
                                            return repository.activatePlacement(
                                                            pending.contribution().id(),
                                                            pending.operationId(),
                                                            withdrawal.balanceAfter())
                                                    .thenApply(ignored -> Result.success(pending.contribution().escrowAmount()));
                                        });
                            });
                });
    }

    private Result validateInitial(Player issuer, OfflinePlayer target, BigDecimal rawAmount) {
        PluginSettings cfg = settings.get();
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) return Result.fail("TARGET_NOT_FOUND");
        if (!cfg.allowOfflineTargets() && !target.isOnline()) return Result.fail("OFFLINE_TARGET_BLOCKED");
        if (!cfg.allowSelfBounty() && issuer.getUniqueId().equals(target.getUniqueId())) return Result.fail("SELF_BOUNTY_BLOCKED");
        if (cfg.placementWorldBlocked(issuer.getWorld().getName())) return Result.fail("WORLD_BLOCKED");

        long playedSeconds = issuer.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        if (playedSeconds < cfg.minimumPlaytimeSeconds()) return Result.fail("MINIMUM_PLAYTIME");

        BigDecimal amount;
        try {
            amount = normalize(rawAmount);
        } catch (RuntimeException ex) {
            return Result.fail("INVALID_AMOUNT");
        }
        if (amount.compareTo(cfg.minimumBounty()) < 0 || amount.compareTo(cfg.maximumBounty()) > 0) {
            return Result.fail("AMOUNT_OUT_OF_RANGE");
        }
        return Result.success(BigDecimal.ZERO);
    }

    private Preparation prepare(Player issuer, OfflinePlayer target, BigDecimal rawAmount, BigDecimal active, Instant now) {
        PluginSettings cfg = settings.get();
        BigDecimal gross = normalize(rawAmount);
        BigDecimal fee = MoneyMath.fee(gross, cfg.placementFeePercent(), cfg.decimalScale());
        BigDecimal escrow = MoneyMath.escrow(gross, fee, cfg.decimalScale());

        if (!cfg.stackingEnabled() && active.signum() > 0) {
            return Preparation.failure(Result.fail("STACKING_DISABLED"));
        }
        if (active.add(escrow).compareTo(cfg.maximumActivePerTarget()) > 0) {
            return Preparation.failure(Result.fail("TARGET_MAXIMUM_EXCEEDED"));
        }
        if (!economy.has(issuer, gross)) {
            return Preparation.failure(Result.fail("INSUFFICIENT_BALANCE"));
        }

        BigDecimal balanceBefore = normalize(economy.balance(issuer));
        UUID contributionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        BountyContribution contribution = new BountyContribution(
                contributionId,
                target.getUniqueId(),
                issuer.getUniqueId(),
                gross,
                escrow,
                fee,
                BountyState.PENDING,
                now,
                now.plusSeconds(cfg.durationSeconds()),
                null
        );
        return Preparation.ready(new Pending(contribution, operationId, balanceBefore));
    }

    private BigDecimal normalize(BigDecimal value) {
        return MoneyMath.normalize(value, settings.get().decimalScale());
    }

    public record Result(boolean success, String reason, BigDecimal rewardAmount) {
        public static Result success(BigDecimal amount) {
            return new Result(true, "OK", amount);
        }

        public static Result fail(String reason) {
            return new Result(false, reason, BigDecimal.ZERO);
        }
    }

    private record Pending(BountyContribution contribution, UUID operationId, BigDecimal balanceBefore) {}
    private record Withdrawal(VaultEconomyAdapter.Result result, BigDecimal balanceAfter) {}
    private record Preparation(Pending pending, Result failure) {
        static Preparation ready(Pending pending) { return new Preparation(pending, null); }
        static Preparation failure(Result failure) { return new Preparation(null, failure); }
    }
}
