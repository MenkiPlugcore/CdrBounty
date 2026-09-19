package store.cadera.cdrbounty.core;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class RecoveryService {
    private final JavaPlugin plugin;
    private final BountyRepository repository;
    private final VaultEconomyAdapter economy;
    private final Supplier<PluginSettings> settings;

    public RecoveryService(JavaPlugin plugin, BountyRepository repository, VaultEconomyAdapter economy,
                           Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.settings = settings;
    }

    public CompletableFuture<Void> recover() {
        return repository.unresolvedEconomyOperations().thenCompose(operations -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (BountyRepository.RecoveryOperation operation : operations) {
                chain = chain.thenCompose(ignored -> recoverOne(operation));
            }
            return chain;
        });
    }

    private CompletableFuture<Void> recoverOne(BountyRepository.RecoveryOperation operation) {
        return MainThread.call(plugin, () -> {
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(operation.playerUuid());
            BigDecimal current = normalize(economy.balance(player));
            return new BalanceSnapshot(player, current);
        }).thenCompose(snapshot -> switch (operation.type()) {
            case WITHDRAWAL -> recoverWithdrawal(operation, snapshot);
            case PAYOUT -> recoverPayout(operation, snapshot);
            case REFUND -> repository.markOperationAmbiguous(operation.operationId(), "REFUND recovery not implemented in this beta.1 revision");
        }).exceptionallyCompose(ex -> repository.markOperationAmbiguous(
                operation.operationId(), "Recovery exception: " + rootMessage(ex)));
    }

    private CompletableFuture<Void> recoverWithdrawal(BountyRepository.RecoveryOperation operation, BalanceSnapshot snapshot) {
        BigDecimal before = normalize(operation.balanceBefore());
        BigDecimal expectedAfter = normalize(before.subtract(operation.amount()));
        if (snapshot.balance().compareTo(expectedAfter) == 0) {
            plugin.getLogger().warning("Recovered completed bounty withdrawal " + operation.operationId());
            return repository.recoverPlacementAsActive(operation.contributionId(), operation.operationId(), snapshot.balance());
        }
        if (snapshot.balance().compareTo(before) == 0) {
            plugin.getLogger().warning("Recovered unexecuted bounty withdrawal " + operation.operationId());
            return repository.recoverPlacementAsNotWithdrawn(operation.contributionId(), operation.operationId());
        }
        return repository.markOperationAmbiguous(operation.operationId(),
                "Withdrawal balance is ambiguous: before=" + before + ", current=" + snapshot.balance());
    }

    private CompletableFuture<Void> recoverPayout(BountyRepository.RecoveryOperation operation, BalanceSnapshot snapshot) {
        BigDecimal before = normalize(operation.balanceBefore());
        BigDecimal expectedAfter = normalize(before.add(operation.amount()));
        if (snapshot.balance().compareTo(expectedAfter) == 0) {
            plugin.getLogger().warning("Recovered completed bounty payout " + operation.operationId());
            return repository.recoverClaimAsPaid(
                    operation.claimId(), operation.operationId(), snapshot.balance(), Instant.now(),
                    settings.get().repeatedPairWindowSeconds());
        }
        if (snapshot.balance().compareTo(before) != 0) {
            return repository.markOperationAmbiguous(operation.operationId(),
                    "Payout balance is ambiguous: before=" + before + ", current=" + snapshot.balance());
        }

        return MainThread.call(plugin, () -> {
            VaultEconomyAdapter.Result result = economy.deposit(snapshot.player(), operation.amount());
            return new RecoveryDeposit(result, normalize(economy.balance(snapshot.player())));
        }).thenCompose(deposit -> {
            if (!deposit.result().success()) {
                return repository.markOperationAmbiguous(operation.operationId(),
                        "Recovery payout failed: " + deposit.result().safeError());
            }
            return repository.recoverClaimAsPaid(
                    operation.claimId(), operation.operationId(), deposit.balanceAfter(), Instant.now(),
                    settings.get().repeatedPairWindowSeconds());
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

    private record BalanceSnapshot(OfflinePlayer player, BigDecimal balance) {}
    private record RecoveryDeposit(VaultEconomyAdapter.Result result, BigDecimal balanceAfter) {}
}
