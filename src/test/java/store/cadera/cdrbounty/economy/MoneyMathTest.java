package store.cadera.cdrbounty.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyMathTest {
    @Test
    void feeAndEscrowUseConfiguredScale() {
        BigDecimal gross = new BigDecimal("1000.00");
        BigDecimal fee = MoneyMath.fee(gross, new BigDecimal("2.5"), 2);
        BigDecimal escrow = MoneyMath.escrow(gross, fee, 2);

        assertEquals(new BigDecimal("25.00"), fee);
        assertEquals(new BigDecimal("975.00"), escrow);
    }

    @Test
    void normalizationRoundsHalfUp() {
        assertEquals(new BigDecimal("10.01"), MoneyMath.normalize(new BigDecimal("10.005"), 2));
    }

    @Test
    void escrowCannotBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMath.escrow(new BigDecimal("10.00"), new BigDecimal("11.00"), 2));
    }
}
