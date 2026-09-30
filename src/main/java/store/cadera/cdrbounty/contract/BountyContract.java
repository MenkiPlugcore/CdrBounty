package store.cadera.cdrbounty.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record BountyContract(
        UUID id,
        UUID contributionId,
        UUID targetUuid,
        UUID issuerUuid,
        BigDecimal rewardAmount,
        Set<ContractFlag> flags,
        ContractState state,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        int reservationLimit,
        UUID settlementClaimId,
        String lastError
) {
    public BountyContract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contributionId, "contributionId");
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(issuerUuid, "issuerUuid");
        Objects.requireNonNull(rewardAmount, "rewardAmount");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        flags = Set.copyOf(flags);
        if (rewardAmount.signum() < 0) throw new IllegalArgumentException("rewardAmount cannot be negative");
        if (expiresAt.isBefore(createdAt)) throw new IllegalArgumentException("expiresAt cannot be before createdAt");
        if (reservationLimit < 1) throw new IllegalArgumentException("reservationLimit must be >= 1");
    }

    public boolean isPrivate() { return flags.contains(ContractFlag.PRIVATE); }
    public boolean isExclusive() { return flags.contains(ContractFlag.EXCLUSIVE); }
    public boolean isAnonymous() { return flags.contains(ContractFlag.ANONYMOUS); }
    public boolean activeAt(Instant now) { return state.claimable() && expiresAt.isAfter(now); }
}
