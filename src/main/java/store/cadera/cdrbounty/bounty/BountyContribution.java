package store.cadera.cdrbounty.bounty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BountyContribution(
        UUID id,
        UUID targetUuid,
        UUID issuerUuid,
        BigDecimal grossAmount,
        BigDecimal escrowAmount,
        BigDecimal feeAmount,
        BountyState state,
        Instant createdAt,
        Instant expiresAt,
        UUID claimId
) {
    public BountyContribution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(issuerUuid, "issuerUuid");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(escrowAmount, "escrowAmount");
        Objects.requireNonNull(feeAmount, "feeAmount");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");

        if (grossAmount.signum() < 0 || escrowAmount.signum() < 0 || feeAmount.signum() < 0) {
            throw new IllegalArgumentException("Bounty monetary values cannot be negative");
        }
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt cannot be before createdAt");
        }
    }

    public boolean activeAt(Instant instant) {
        return state == BountyState.ACTIVE && expiresAt.isAfter(instant);
    }
}
