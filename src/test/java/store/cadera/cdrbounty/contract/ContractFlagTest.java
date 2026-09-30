package store.cadera.cdrbounty.contract;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractFlagTest {
    @Test
    void blankDefaultsToPublic() {
        assertEquals(Set.of(ContractFlag.PUBLIC), ContractFlag.parse(""));
    }

    @Test
    void flagsRoundTrip() {
        Set<ContractFlag> flags = Set.of(ContractFlag.PRIVATE, ContractFlag.EXCLUSIVE, ContractFlag.ANONYMOUS);
        Set<ContractFlag> restored = ContractFlag.parse(ContractFlag.serialize(flags));
        assertEquals(flags, restored);
        assertTrue(restored.contains(ContractFlag.PRIVATE));
    }
}
