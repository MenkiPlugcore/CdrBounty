package store.cadera.cdrbounty.storage;

import store.cadera.cdrbounty.bounty.BountyContribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BountyRepository extends AutoCloseable {
    void initialize() throws Exception;

    CompletableFuture<Void> upsertPlayer(UUID uuid, String lastName, Instant seenAt);

    CompletableFuture<Void> createPendingPlacement(
            BountyContribution contribution,
            UUID economyOperationId,
            BigDecimal issuerBalanceBefore
    );

    CompletableFuture<Void> activatePlacement(
            UUID contributionId,
            UUID economyOperationId,
            BigDecimal issuerBalanceAfter
    );

    CompletableFuture<Void> failPlacement(
            UUID contributionId,
            UUID economyOperationId,
            String reason
    );

    CompletableFuture<BigDecimal> activeTotal(UUID targetUuid, Instant now);

    CompletableFuture<List<TargetTotal>> listActiveTotals(Instant now, int limit);

    CompletableFuture<PairHistory> pairHistory(UUID killerUuid, UUID victimUuid);

    CompletableFuture<Instant> lastPaidClaimAgainst(UUID victimUuid);

    CompletableFuture<ClaimPreparation> prepareClaim(
            UUID claimId,
            UUID payoutOperationId,
            UUID targetUuid,
            UUID killerUuid,
            String world,
            Instant now,
            BigDecimal killerBalanceBefore
    );

    CompletableFuture<Void> completeClaim(
            UUID claimId,
            UUID payoutOperationId,
            BigDecimal killerBalanceAfter,
            Instant completedAt,
            long repeatedPairWindowSeconds
    );

    CompletableFuture<Void> failClaim(UUID claimId, UUID payoutOperationId, String reason);

    CompletableFuture<List<RecoveryOperation>> unresolvedEconomyOperations();

    CompletableFuture<Void> recoverPlacementAsActive(UUID contributionId, UUID operationId, BigDecimal balanceAfter);

    CompletableFuture<Void> recoverPlacementAsNotWithdrawn(UUID contributionId, UUID operationId);

    CompletableFuture<Void> recoverClaimAsPaid(UUID claimId, UUID operationId, BigDecimal balanceAfter, Instant completedAt, long pairWindowSeconds);

    CompletableFuture<Void> markOperationAmbiguous(UUID operationId, String reason);

    CompletableFuture<Void> audit(UUID actorUuid, String action, UUID targetUuid, BigDecimal amount, String details, Instant at);

    @Override
    void close();

    record TargetTotal(UUID targetUuid, BigDecimal amount) {}

    record PairHistory(Instant lastClaimAt, Instant windowStart, int windowClaims) {
        public static PairHistory empty() {
            return new PairHistory(null, null, 0);
        }
    }

    record ClaimPreparation(UUID claimId, UUID targetUuid, UUID killerUuid, BigDecimal amount, int contributionCount) {}

    record RecoveryOperation(
            UUID operationId,
            Type type,
            UUID playerUuid,
            UUID contributionId,
            UUID claimId,
            BigDecimal amount,
            BigDecimal balanceBefore,
            Instant createdAt
    ) {
        public enum Type { WITHDRAWAL, PAYOUT, REFUND }
    }
}
