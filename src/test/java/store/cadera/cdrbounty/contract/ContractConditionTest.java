package store.cadera.cdrbounty.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractConditionTest {
    @Test
    void requiredWorldIsCaseInsensitive() {
        ContractCondition condition = new ContractCondition(ContractConditionType.REQUIRED_WORLD, "World_RP");
        assertTrue(condition.matches("world_rp", "DIAMOND_SWORD"));
        assertTrue(condition.matches("WORLD_RP", "AIR"));
        assertFalse(condition.matches("world", "AIR"));
    }

    @Test
    void forbiddenWorldRejectsMatchingWorld() {
        ContractCondition condition = new ContractCondition(ContractConditionType.FORBIDDEN_WORLD, "lobby");
        assertFalse(condition.matches("LOBBY", "AIR"));
        assertTrue(condition.matches("worldrp", "AIR"));
    }

    @Test
    void requiredWeaponNormalizesMaterialName() {
        ContractCondition condition = new ContractCondition(ContractConditionType.REQUIRED_WEAPON, "diamond_sword");
        assertTrue(condition.matches("world", "DIAMOND_SWORD"));
        assertFalse(condition.matches("world", "IRON_SWORD"));
    }
}
