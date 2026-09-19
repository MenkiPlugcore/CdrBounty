package store.cadera.cdrbounty.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyMath {
    private MoneyMath() {
    }

    public static BigDecimal normalize(BigDecimal amount, int scale) {
        if (amount == null) {
            throw new IllegalArgumentException("amount cannot be null");
        }
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal fee(BigDecimal amount, BigDecimal percent, int scale) {
        BigDecimal normalized = normalize(amount, scale);
        BigDecimal result = normalized.multiply(percent)
                .divide(BigDecimal.valueOf(100), scale + 4, RoundingMode.HALF_UP);
        return normalize(result, scale);
    }

    public static BigDecimal escrow(BigDecimal amount, BigDecimal fee, int scale) {
        BigDecimal value = normalize(amount, scale).subtract(normalize(fee, scale));
        if (value.signum() < 0) {
            throw new IllegalArgumentException("fee cannot exceed amount");
        }
        return normalize(value, scale);
    }
}
