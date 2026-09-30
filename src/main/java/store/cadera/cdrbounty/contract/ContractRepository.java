package store.cadera.cdrbounty.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ContractRepository extends AutoCloseable {
    void initialize() throws Exception;

    CompletableFuture<Void> createDraft(BountyContract contract, Set<UUID> allowedHunters,
                                        List<ContractCondition> conditions);

    CompletableFuture<Void> open(UUID contractId, BigDecimal rewardAmount, Instant activatedAt);

    CompletableFuture<Void> voidDraft(UUID contractId, String reason);

    CompletableFuture<Optional<ContractView>> get(UUID contractId, UUID viewerUuid, boolean admin);

    CompletableFuture<List<ContractView>> listVisible(UUID viewerUuid, boolean admin, Instant now, int limit);

    CompletableFuture<ActionResult> accept(UUID contractId, UUID hunterUuid, Instant now, int maxActivePerHunter);

    CompletableFuture<ActionResult> abandon(UUID contractId, UUID hunterUuid, Instant now);

    CompletableFuture<BigDecimal> claimableAmount(UUID targetUuid, UUID hunterUuid, String world,
                                                   String weaponMaterial, Instant now);

    CompletableFuture<ClaimPreparation> prepareClaim(UUID claimId, UUID payoutOperationId,
                                                      UUID targetUuid, UUID hunterUuid,
                                                      String world, String weaponMaterial,
                                                      Instant now, BigDecimal hunterBalanceBefore);

    CompletableFuture<Void> completeContractsForClaim(UUID claimId, Instant completedAt);

    CompletableFuture<Void> releaseContractsForClaim(UUID claimId, String reason, Instant now);

    CompletableFuture<Integer> reconcile();

    CompletableFuture<Integer> syncTerminalStates(Instant now);

    CompletableFuture<BigDecimal> visibleTotal(UUID targetUuid, UUID viewerUuid, boolean admin, Instant now);

    CompletableFuture<List<VisibleTargetTotal>> visibleTargetTotals(UUID viewerUuid, boolean admin,
                                                                     Instant now, int limit);

    CompletableFuture<Integer> contractCount();

    @Override
    void close();

    record ContractView(BountyContract contract, int activeHunters, boolean viewerAccepted,
                        boolean issuerVisible, List<ContractCondition> conditions) {}

    record ActionResult(boolean success, String reason) {
        public static ActionResult ok() { return new ActionResult(true, "OK"); }
        public static ActionResult fail(String reason) { return new ActionResult(false, reason); }
    }

    record ClaimPreparation(UUID claimId, UUID targetUuid, UUID hunterUuid, BigDecimal amount,
                            int contributionCount, int contractCount) {}

    record VisibleTargetTotal(UUID targetUuid, BigDecimal amount) {}
}
