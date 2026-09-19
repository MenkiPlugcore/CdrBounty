package store.cadera.cdrbounty.bounty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BountyStateTest {
    @Test
    void activeCanEnterClaimingButCannotJumpToClaimed() {
        assertTrue(BountyState.ACTIVE.canTransitionTo(BountyState.CLAIMING));
        assertFalse(BountyState.ACTIVE.canTransitionTo(BountyState.CLAIMED));
    }

    @Test
    void claimingCanCompleteOrRollBackToActive() {
        assertTrue(BountyState.CLAIMING.canTransitionTo(BountyState.CLAIMED));
        assertTrue(BountyState.CLAIMING.canTransitionTo(BountyState.ACTIVE));
    }

    @Test
    void terminalStatesCannotTransition() {
        assertTrue(BountyState.CLAIMED.terminal());
        assertTrue(BountyState.REFUNDED.terminal());
        assertTrue(BountyState.VOIDED.terminal());
        assertFalse(BountyState.CLAIMED.canTransitionTo(BountyState.ACTIVE));
        assertFalse(BountyState.REFUNDED.canTransitionTo(BountyState.ACTIVE));
        assertFalse(BountyState.VOIDED.canTransitionTo(BountyState.ACTIVE));
    }

    @Test
    void invalidTransitionThrows() {
        assertThrows(IllegalStateException.class,
                () -> BountyState.PENDING.requireTransitionTo(BountyState.CLAIMED));
    }
}
