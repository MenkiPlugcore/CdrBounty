package store.cadera.cdrbounty.bounty;

import java.util.EnumSet;
import java.util.Map;

public enum BountyState {
    PENDING,
    ACTIVE,
    CLAIMING,
    CLAIMED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    VOIDED;

    private static final Map<BountyState, EnumSet<BountyState>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(ACTIVE, VOIDED, CANCELLED),
            ACTIVE, EnumSet.of(CLAIMING, EXPIRED, CANCELLED, VOIDED),
            CLAIMING, EnumSet.of(CLAIMED, ACTIVE, VOIDED),
            CLAIMED, EnumSet.noneOf(BountyState.class),
            EXPIRED, EnumSet.of(REFUNDED, VOIDED),
            CANCELLED, EnumSet.of(REFUNDED, VOIDED),
            REFUNDED, EnumSet.noneOf(BountyState.class),
            VOIDED, EnumSet.noneOf(BountyState.class)
    );

    public boolean canTransitionTo(BountyState next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public void requireTransitionTo(BountyState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Invalid bounty state transition: " + this + " -> " + next);
        }
    }

    public boolean terminal() {
        return this == CLAIMED || this == REFUNDED || this == VOIDED;
    }
}
