package store.cadera.cdrbounty.storage;

import store.cadera.cdrbounty.bounty.BountyState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BountyMaintenanceRepository extends AutoCloseable {
    UUID SYSTEM_ISSUER = new UUID(0L, 0L);

    void initialize() throws Exception;

    CompletableFuture<List<RefundCandidate>> findExpired(Instant now, int limit);

    CompletableFuture<List<RefundCandidate>> activeContributions(UUID targetUuid);

    CompletableFuture<Void> reserveRefund(UUID contributionId, BountyState intermediateState,
                                          UUID operationId, BigDecimal balanceBefore, Instant now);

    CompletableFuture<Void> completeRefund(UUID contributionId, UUID operationId,
                                           BigDecimal balanceAfter, Instant now);

    CompletableFuture<Void> voidContribution(UUID contributionId, BountyState intermediateState,
                                             UUID actorUuid, String reason, Instant now);

    CompletableFuture<UUID> adminAdd(UUID targetUuid, UUID actorUuid, BigDecimal amount,
                                     Instant createdAt, Instant expiresAt);

    CompletableFuture<List<ClaimHistory>> claimHistory(UUID targetUuid, int limit);

    @Override
    void close();

    record RefundCandidate(UUID contributionId, UUID issuerUuid, BigDecimal amount, Instant expiresAt) {}

    record ClaimHistory(UUID claimId, UUID killerUuid, BigDecimal amount, String world,
                        String status, Instant createdAt, Instant paidAt) {}
}
