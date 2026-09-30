package store.cadera.cdrbounty.reputation;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class ReputationAutoBountyPolicy {
    private ReputationAutoBountyPolicy() {
    }

    public static Decision evaluate(int reputation, int lastThreshold, int resetThreshold, List<Rule> rules) {
        if (reputation >= resetThreshold) {
            return new Decision(lastThreshold != 0, 0, BigDecimal.ZERO);
        }

        BigDecimal amount = BigDecimal.ZERO;
        int nextThreshold = lastThreshold;
        for (Rule rule : rules.stream().sorted(Comparator.comparingInt(Rule::threshold).reversed()).toList()) {
            if (reputation <= rule.threshold() && rule.threshold() < lastThreshold) {
                amount = amount.add(rule.addBounty());
                nextThreshold = Math.min(nextThreshold, rule.threshold());
            }
        }
        return new Decision(false, nextThreshold, amount);
    }

    public record Rule(int threshold, BigDecimal addBounty) {
        public Rule {
            if (addBounty == null || addBounty.signum() <= 0) {
                throw new IllegalArgumentException("addBounty must be > 0");
            }
        }
    }

    public record Decision(boolean reset, int nextThreshold, BigDecimal amount) {
        public boolean issue() {
            return amount != null && amount.signum() > 0;
        }
    }
}
