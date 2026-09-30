package store.cadera.cdrbounty.reputation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReputationAutoBountyPolicyTest {
    private static final List<ReputationAutoBountyPolicy.Rule> RULES = List.of(
            new ReputationAutoBountyPolicy.Rule(-1000, new BigDecimal("25000")),
            new ReputationAutoBountyPolicy.Rule(-2000, new BigDecimal("25000")),
            new ReputationAutoBountyPolicy.Rule(-3500, new BigDecimal("50000"))
    );

    @Test
    void oneLargeDropIssuesCumulativeReward() {
        var decision = ReputationAutoBountyPolicy.evaluate(-2500, 0, -500, RULES);
        assertTrue(decision.issue());
        assertEquals(-2000, decision.nextThreshold());
        assertEquals(0, decision.amount().compareTo(new BigDecimal("50000")));
    }

    @Test
    void escalationOnlyAddsNewlyCrossedTier() {
        var decision = ReputationAutoBountyPolicy.evaluate(-3600, -2000, -500, RULES);
        assertTrue(decision.issue());
        assertEquals(-3500, decision.nextThreshold());
        assertEquals(0, decision.amount().compareTo(new BigDecimal("50000")));
    }

    @Test
    void sameTierDoesNotReissue() {
        var decision = ReputationAutoBountyPolicy.evaluate(-1500, -1000, -500, RULES);
        assertFalse(decision.issue());
        assertFalse(decision.reset());
        assertEquals(-1000, decision.nextThreshold());
    }

    @Test
    void recoveryAboveResetThresholdRearmsSystem() {
        var decision = ReputationAutoBountyPolicy.evaluate(-400, -3500, -500, RULES);
        assertTrue(decision.reset());
        assertEquals(0, decision.nextThreshold());
    }
}
