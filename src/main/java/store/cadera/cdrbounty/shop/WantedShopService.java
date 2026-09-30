package store.cadera.cdrbounty.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.api.CdrBountyShopApi;
import store.cadera.cdrbounty.contract.ContractRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WantedShopService implements CdrBountyShopApi {
    private static final List<Tier> DEFAULT_TIERS = List.of(
            new Tier(new BigDecimal("10000.00"), 1.10D),
            new Tier(new BigDecimal("50000.00"), 1.25D),
            new Tier(new BigDecimal("100000.00"), 1.50D),
            new Tier(new BigDecimal("500000.00"), 2.00D)
    );

    private final JavaPlugin plugin;
    private final ContractRepository contracts;
    private final Map<UUID, BigDecimal> totals = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private volatile List<Tier> tiers = DEFAULT_TIERS;
    private volatile boolean enabled;

    public WantedShopService(JavaPlugin plugin, ContractRepository contracts) {
        this.plugin = plugin;
        this.contracts = contracts;
        reloadConfiguration();
    }

    public void start() {
        refreshNow();
        long seconds = Math.max(1L, plugin.getConfig().getLong("shop-integration.refresh-seconds", 3L));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshNow, seconds * 20L, seconds * 20L);
    }

    public void reloadConfiguration() {
        this.enabled = plugin.getConfig().getBoolean("shop-integration.enabled", true);
        List<Tier> parsed = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop-integration.tiers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection row = section.getConfigurationSection(key);
                if (row == null) continue;
                String rawMinimum = row.getString("minimum-bounty", "0");
                double multiplier = row.getDouble("buy-multiplier", 1.0D);
                try {
                    BigDecimal minimum = new BigDecimal(rawMinimum.trim());
                    if (minimum.signum() < 0 || !Double.isFinite(multiplier) || multiplier < 1.0D) continue;
                    parsed.add(new Tier(minimum, multiplier));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid shop-integration tier '" + key + "': minimum-bounty=" + rawMinimum);
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed.addAll(DEFAULT_TIERS);
            plugin.getLogger().info("shop-integration.tiers tidak ditemukan/kosong; memakai tier default v0.7.0.");
        }
        parsed.sort(Comparator.comparing(Tier::minimumBounty));
        this.tiers = List.copyOf(parsed);
    }

    public void refreshNow() {
        if (!refreshRunning.compareAndSet(false, true)) return;
        contracts.listVisible(null, true, Instant.now(), 1000)
                .whenComplete((views, error) -> {
                    try {
                        if (error != null) {
                            plugin.getLogger().warning("Wanted shop cache refresh failed: " + root(error));
                            return;
                        }
                        Map<UUID, BigDecimal> next = new ConcurrentHashMap<>();
                        for (ContractRepository.ContractView view : views) {
                            next.merge(view.contract().targetUuid(), view.contract().rewardAmount(), BigDecimal::add);
                        }
                        totals.clear();
                        totals.putAll(next);
                    } finally {
                        refreshRunning.set(false);
                    }
                });
    }

    @Override
    public boolean isWanted(UUID playerId) {
        return enabled && activeBountyTotal(playerId).signum() > 0;
    }

    @Override
    public BigDecimal activeBountyTotal(UUID playerId) {
        if (playerId == null) return BigDecimal.ZERO;
        return totals.getOrDefault(playerId, BigDecimal.ZERO);
    }

    @Override
    public double shopBuyMultiplier(UUID playerId) {
        if (!enabled || playerId == null) return 1.0D;
        BigDecimal total = activeBountyTotal(playerId);
        if (total.signum() <= 0) return 1.0D;

        double multiplier = 1.0D;
        for (Tier tier : tiers) {
            if (total.compareTo(tier.minimumBounty()) >= 0) multiplier = tier.buyMultiplier();
            else break;
        }
        return multiplier;
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Tier(BigDecimal minimumBounty, double buyMultiplier) {}
}
