package store.cadera.cdrbounty.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record PluginSettings(
        BigDecimal minimumBounty,
        BigDecimal maximumBounty,
        BigDecimal placementFeePercent,
        int decimalScale,
        boolean allowSelfBounty,
        boolean allowOfflineTargets,
        boolean stackingEnabled,
        long minimumPlaytimeSeconds,
        BigDecimal maximumActivePerTarget,
        long durationSeconds,
        Set<String> placementBlacklistedWorlds,
        Set<String> claimBlacklistedWorlds,
        long killerVictimCooldownSeconds,
        long repeatedPairWindowSeconds,
        int repeatedPairMaxClaims,
        SameIpPolicy sameIpPolicy,
        long minimumSurvivalAfterClaimSeconds,
        RefundPolicy expireRefundPolicy,
        RefundPolicy cancelRefundPolicy,
        long expirationScanSeconds,
        boolean debug,
        Path sqliteFile,
        int sqliteBusyTimeoutMs,
        String sqliteJournalMode,
        String sqliteSynchronous
) {
    public enum SameIpPolicy { BLOCK, IGNORE }
    public enum RefundPolicy { FULL, NONE }

    public static PluginSettings load(JavaPlugin plugin) {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        FileConfiguration storage = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "storage.yml"));

        BigDecimal min = decimal(config, "economy.minimum-bounty");
        BigDecimal max = decimal(config, "economy.maximum-bounty");
        BigDecimal fee = decimal(config, "economy.placement-fee-percent");
        int scale = config.getInt("economy.decimal-scale", 2);
        BigDecimal maxActive = decimal(config, "placement.maximum-active-bounty-per-target");

        if (min.signum() <= 0) throw invalid("economy.minimum-bounty must be > 0");
        if (max.compareTo(min) < 0) throw invalid("economy.maximum-bounty must be >= minimum-bounty");
        if (fee.signum() < 0 || fee.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw invalid("economy.placement-fee-percent must be >= 0 and < 100");
        }
        if (scale < 0 || scale > 8) throw invalid("economy.decimal-scale must be between 0 and 8");
        if (maxActive.compareTo(min) < 0) throw invalid("placement.maximum-active-bounty-per-target is too small");

        long duration = positive(config.getLong("placement.duration-seconds", 604800), "placement.duration-seconds");
        long scan = positive(config.getLong("runtime.expiration-scan-seconds", 30), "runtime.expiration-scan-seconds");
        int maxPairClaims = config.getInt("claim.repeated-pair-max-claims", 2);
        if (maxPairClaims < 1) throw invalid("claim.repeated-pair-max-claims must be >= 1");

        String provider = storage.getString("provider", "SQLITE").toUpperCase(Locale.ROOT);
        if (!provider.equals("SQLITE")) throw invalid("beta.1 supports only SQLITE storage");

        String fileName = storage.getString("sqlite.file", "bounty.db");
        Path dataRoot = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path dbPath = dataRoot.resolve(fileName).normalize();
        if (!dbPath.startsWith(dataRoot)) throw invalid("sqlite.file must stay inside the plugin data directory");

        int busyTimeout = storage.getInt("sqlite.busy-timeout-ms", 5000);
        if (busyTimeout < 0 || busyTimeout > 60000) throw invalid("sqlite.busy-timeout-ms must be between 0 and 60000");

        String journal = storage.getString("sqlite.journal-mode", "WAL").toUpperCase(Locale.ROOT);
        if (!Set.of("WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF").contains(journal)) {
            throw invalid("Unsupported sqlite.journal-mode: " + journal);
        }
        String synchronous = storage.getString("sqlite.synchronous", "NORMAL").toUpperCase(Locale.ROOT);
        if (!Set.of("OFF", "NORMAL", "FULL", "EXTRA").contains(synchronous)) {
            throw invalid("Unsupported sqlite.synchronous: " + synchronous);
        }

        return new PluginSettings(
                min, max, fee, scale,
                config.getBoolean("placement.allow-self-bounty", false),
                config.getBoolean("placement.allow-offline-targets", true),
                config.getBoolean("placement.stacking-enabled", true),
                nonNegative(config.getLong("placement.minimum-playtime-seconds", 0), "placement.minimum-playtime-seconds"),
                maxActive,
                duration,
                lowerSet(config, "placement.blacklisted-worlds"),
                lowerSet(config, "claim.blacklisted-worlds"),
                nonNegative(config.getLong("claim.killer-victim-cooldown-seconds", 3600), "claim.killer-victim-cooldown-seconds"),
                nonNegative(config.getLong("claim.repeated-pair-window-seconds", 86400), "claim.repeated-pair-window-seconds"),
                maxPairClaims,
                enumValue(SameIpPolicy.class, config.getString("claim.same-ip-policy", "BLOCK"), "claim.same-ip-policy"),
                nonNegative(config.getLong("claim.minimum-survival-after-claim-seconds", 300), "claim.minimum-survival-after-claim-seconds"),
                enumValue(RefundPolicy.class, config.getString("refund.on-expire", "FULL"), "refund.on-expire"),
                enumValue(RefundPolicy.class, config.getString("refund.on-admin-cancel", "FULL"), "refund.on-admin-cancel"),
                scan,
                config.getBoolean("runtime.debug", false),
                dbPath,
                busyTimeout,
                journal,
                synchronous
        );
    }

    public boolean placementWorldBlocked(String worldName) {
        return placementBlacklistedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean claimWorldBlocked(String worldName) {
        return claimBlacklistedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static BigDecimal decimal(FileConfiguration config, String path) {
        String raw = config.getString(path);
        if (raw == null) throw invalid("Missing required numeric setting: " + path);
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw invalid("Invalid decimal at " + path + ": " + raw);
        }
    }

    private static Set<String> lowerSet(FileConfiguration config, String path) {
        return config.getStringList(path).stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static long positive(long value, String path) {
        if (value <= 0) throw invalid(path + " must be > 0");
        return value;
    }

    private static long nonNegative(long value, String path) {
        if (value < 0) throw invalid(path + " must be >= 0");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String path) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw invalid("Invalid value at " + path + ": " + raw);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("CdrBounty configuration error: " + message);
    }
}
