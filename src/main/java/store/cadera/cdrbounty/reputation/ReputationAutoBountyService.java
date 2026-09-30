package store.cadera.cdrbounty.reputation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.core.MainThread;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReputationAutoBountyService implements Listener {
    private static final String API_CLASS = "com.menkiestes.cdrreputation.api.CdrReputationApi";
    private static final String EVENT_CLASS = "com.menkiestes.cdrreputation.event.ReputationChangeEvent";

    private final JavaPlugin plugin;
    private final ReputationAutoBountyRepository repository;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private Object reputationApi;
    private Method getReputationMethod;

    public ReputationAutoBountyService(JavaPlugin plugin, ReputationAutoBountyRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean start() {
        if (!plugin.getConfig().getBoolean("reputation-auto-bounty.enabled", true)) {
            plugin.getLogger().info("Reputation auto-bounty disabled by config.");
            return false;
        }
        if (!started.compareAndSet(false, true)) return reputationApi != null;

        Plugin reputationPlugin = Bukkit.getPluginManager().getPlugin("CdrReputation");
        if (reputationPlugin == null || !reputationPlugin.isEnabled()) {
            plugin.getLogger().warning("CdrReputation not found; reputation auto-bounty integration is inactive.");
            return false;
        }

        try {
            ClassLoader loader = reputationPlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(API_CLASS, true, loader);
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) apiClass);
            if (registration == null || registration.getProvider() == null) {
                plugin.getLogger().warning("CdrReputation API service is not registered; auto-bounty integration is inactive.");
                return false;
            }
            reputationApi = registration.getProvider();
            getReputationMethod = apiClass.getMethod("getReputation", UUID.class);

            Class<?> rawEventClass = Class.forName(EVENT_CLASS, true, loader);
            if (!Event.class.isAssignableFrom(rawEventClass)) {
                throw new IllegalStateException(EVENT_CLASS + " is not a Bukkit Event");
            }
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass;
            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.MONITOR,
                    (listener, event) -> onReputationEvent(event),
                    plugin,
                    true
            );
            Bukkit.getPluginManager().registerEvents(this, plugin);

            List<ReputationAutoBountyPolicy.Rule> rules = loadRules();
            plugin.getLogger().info("CdrReputation integration active with " + rules.size() + " auto-bounty threshold(s).");
            for (Player player : Bukkit.getOnlinePlayers()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> evaluateCurrent(player.getUniqueId(), "STARTUP"), 20L);
            }
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to hook CdrReputation; auto-bounty disabled: " + root(ex));
            reputationApi = null;
            getReputationMethod = null;
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (reputationApi == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> evaluateCurrent(playerId, "JOIN"), 20L);
    }

    private void onReputationEvent(Event event) {
        try {
            Object change = event.getClass().getMethod("getChange").invoke(event);
            UUID playerId = (UUID) change.getClass().getMethod("playerId").invoke(change);
            int newValue = ((Number) change.getClass().getMethod("newValue").invoke(change)).intValue();
            evaluate(playerId, newValue, "EVENT");
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to read ReputationChangeEvent: " + root(ex));
        }
    }

    private void evaluateCurrent(UUID playerId, String source) {
        if (reputationApi == null || getReputationMethod == null) return;
        try {
            int reputation = ((Number) getReputationMethod.invoke(reputationApi, playerId)).intValue();
            evaluate(playerId, reputation, source);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to query CdrReputation for " + playerId + ": " + root(ex));
        }
    }

    private void evaluate(UUID playerId, int reputation, String source) {
        List<ReputationAutoBountyPolicy.Rule> rules;
        try {
            rules = loadRules();
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Invalid reputation-auto-bounty config: " + ex.getMessage());
            return;
        }
        int resetThreshold = plugin.getConfig().getInt("reputation-auto-bounty.reset-threshold", -500);
        repository.evaluateAndIssue(playerId, reputation, resetThreshold, rules, Instant.now())
                .thenAccept(result -> {
                    if (result.issued()) {
                        MainThread.run(plugin, () -> announceIssue(playerId, result, source));
                    } else if (result.action() == ReputationAutoBountyRepository.EvaluationResult.Action.RESET
                            && plugin.getConfig().getBoolean("runtime.debug", false)) {
                        plugin.getLogger().info("Reputation auto-bounty state reset for " + playerId
                                + " at reputation " + reputation + ".");
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Reputation auto-bounty evaluation failed for " + playerId + ": " + root(ex));
                    return null;
                });
    }

    private void announceIssue(UUID playerId, ReputationAutoBountyRepository.EvaluationResult result, String source) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerId);
        String name = target.getName() == null ? playerId.toString() : target.getName();
        String amount = result.amount().stripTrailingZeros().toPlainString();

        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(ChatColor.DARK_RED + "Reputasimu terlalu buruk. "
                    + ChatColor.RED + "System bounty " + ChatColor.GOLD + amount
                    + ChatColor.RED + " telah diterbitkan atas namamu.");
        }
        if (plugin.getConfig().getBoolean("reputation-auto-bounty.announce", true)) {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[BOUNTY] " + ChatColor.RED + name
                    + " kini menjadi buronan dengan tambahan bounty " + ChatColor.GOLD + amount + ChatColor.RED + ".");
        }
        plugin.getLogger().info("Issued reputation system bounty: target=" + name
                + ", reputation=" + result.reputation()
                + ", threshold=" + result.threshold()
                + ", amount=" + amount
                + ", source=" + source
                + ", contract=" + result.contractId());
    }

    private List<ReputationAutoBountyPolicy.Rule> loadRules() {
        var section = plugin.getConfig().getConfigurationSection("reputation-auto-bounty.tiers");
        if (section == null) throw new IllegalArgumentException("tiers section is missing");
        int resetThreshold = plugin.getConfig().getInt("reputation-auto-bounty.reset-threshold", -500);
        List<ReputationAutoBountyPolicy.Rule> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            var tier = section.getConfigurationSection(key);
            if (tier == null) continue;
            int threshold = tier.getInt("threshold");
            String raw = tier.getString("add-bounty");
            if (raw == null) throw new IllegalArgumentException(key + ".add-bounty is missing");
            BigDecimal add;
            try { add = new BigDecimal(raw); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(key + ".add-bounty is invalid: " + raw); }
            if (threshold >= resetThreshold) {
                throw new IllegalArgumentException(key + ".threshold must be lower than reset-threshold");
            }
            if (add.signum() <= 0) throw new IllegalArgumentException(key + ".add-bounty must be > 0");
            result.add(new ReputationAutoBountyPolicy.Rule(threshold, add));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("at least one tier is required");
        result.sort(Comparator.comparingInt(ReputationAutoBountyPolicy.Rule::threshold).reversed());
        return List.copyOf(result);
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
