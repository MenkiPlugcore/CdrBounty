package store.cadera.cdrbounty.contract;

import java.util.Locale;
import java.util.Objects;

public record ContractCondition(ContractConditionType type, String value) {
    public ContractCondition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        value = normalize(type, value);
        if (value.isBlank()) throw new IllegalArgumentException("Contract condition value cannot be blank");
    }

    public boolean matches(String worldName, String weaponMaterial) {
        String world = worldName == null ? "" : worldName.toLowerCase(Locale.ROOT);
        String weapon = weaponMaterial == null ? "AIR" : weaponMaterial.toUpperCase(Locale.ROOT);
        return switch (type) {
            case REQUIRED_WORLD -> world.equals(value);
            case FORBIDDEN_WORLD -> !world.equals(value);
            case REQUIRED_WEAPON -> weapon.equals(value);
        };
    }

    public String description() {
        return switch (type) {
            case REQUIRED_WORLD -> "Required world: " + value;
            case FORBIDDEN_WORLD -> "Forbidden world: " + value;
            case REQUIRED_WEAPON -> "Required weapon: " + value;
        };
    }

    private static String normalize(ContractConditionType type, String raw) {
        String trimmed = raw.trim();
        return switch (type) {
            case REQUIRED_WORLD, FORBIDDEN_WORLD -> trimmed.toLowerCase(Locale.ROOT);
            case REQUIRED_WEAPON -> trimmed.toUpperCase(Locale.ROOT);
        };
    }
}
