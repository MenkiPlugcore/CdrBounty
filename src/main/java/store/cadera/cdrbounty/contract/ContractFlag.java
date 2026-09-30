package store.cadera.cdrbounty.contract;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public enum ContractFlag {
    PUBLIC,
    PRIVATE,
    EXCLUSIVE,
    ANONYMOUS;

    public static Set<ContractFlag> parse(String raw) {
        if (raw == null || raw.isBlank()) return Set.of(PUBLIC);
        EnumSet<ContractFlag> flags = EnumSet.noneOf(ContractFlag.class);
        for (String token : raw.split(",")) {
            if (token.isBlank()) continue;
            flags.add(ContractFlag.valueOf(token.trim().toUpperCase()));
        }
        if (!flags.contains(PUBLIC) && !flags.contains(PRIVATE)) flags.add(PUBLIC);
        return Set.copyOf(flags);
    }

    public static String serialize(Set<ContractFlag> flags) {
        return flags.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
