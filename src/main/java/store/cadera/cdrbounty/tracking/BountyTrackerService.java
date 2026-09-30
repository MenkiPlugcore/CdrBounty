package store.cadera.cdrbounty.tracking;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.core.MainThread;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class BountyTrackerService implements Listener {
    private final JavaPlugin plugin;
    private final TrackingRepository repository;
    private final NamespacedKey contractKey;
    private final AtomicBoolean pauseScanRunning = new AtomicBoolean(false);

    public BountyTrackerService(JavaPlugin plugin, TrackingRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.contractKey = new NamespacedKey(plugin, "bounty_tracker_contract");
    }

    public void start() {
        long pauseTicks = Math.max(20L,
                plugin.getConfig().getLong("tracking.pause-scan-seconds", 5L) * 20L);
        long updateTicks = Math.max(20L,
                plugin.getConfig().getLong("tracking.update-seconds", 20L) * 20L);

        Bukkit.getScheduler().runTaskTimer(plugin, this::syncPauseState, 1L, pauseTicks);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnlineTrackers, 20L, updateTicks);
    }

    public void issue(Player hunter, UUID contractId) {
        repository.activeForHunter(hunter.getUniqueId(), Instant.now())
                .thenAccept(active -> MainThread.run(plugin, () -> {
                    if (!hunter.isOnline()) return;
                    TrackingRepository.TrackingContract contract = active.stream()
                            .filter(item -> item.contractId().equals(contractId))
                            .findFirst().orElse(null);
                    if (contract == null) {
                        hunter.sendMessage(ChatColor.RED + "Tracker tidak tersedia untuk contract ini.");
                        return;
                    }
                    removeContract(hunter, contractId);
                    ItemStack tracker = createTracker(hunter, contract);
                    Map<Integer, ItemStack> overflow = hunter.getInventory().addItem(tracker);
                    if (!overflow.isEmpty()) {
                        overflow.values().forEach(item -> hunter.getWorld().dropItemNaturally(hunter.getLocation(), item));
                        hunter.sendMessage(ChatColor.YELLOW + "Inventory penuh. Bounty Tracker dijatuhkan di dekatmu.");
                    } else {
                        hunter.sendMessage(ChatColor.GOLD + "Bounty Tracker diberikan. Arah kompas hanya perkiraan.");
                    }
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to issue bounty tracker: " + root(ex));
                    return null;
                });
    }

    public void removeContract(Player hunter, UUID contractId) {
        ItemStack[] contents = hunter.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            UUID tracked = trackerContract(item);
            if (contractId.equals(tracked)) {
                hunter.getInventory().setItem(i, null);
                changed = true;
            }
        }
        if (changed) hunter.updateInventory();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshPlayer(event.getPlayer()), 20L);
    }

    private void syncPauseState() {
        if (!pauseScanRunning.compareAndSet(false, true)) return;
        repository.activeTargets()
                .thenCompose(targets -> MainThread.call(plugin, () -> {
                    Map<UUID, Boolean> availability = new HashMap<>();
                    for (UUID targetId : targets) {
                        Player target = Bukkit.getPlayer(targetId);
                        boolean available = target != null && target.isOnline()
                                && !pauseWorld(target.getWorld().getName());
                        availability.put(targetId, available);
                    }
                    return Map.copyOf(availability);
                }))
                .thenCompose(availability -> repository.applyAvailability(availability, Instant.now()))
                .whenComplete((changed, error) -> {
                    pauseScanRunning.set(false);
                    if (error != null) {
                        plugin.getLogger().warning("Bounty pause heartbeat failed: " + root(error));
                    } else if (plugin.getConfig().getBoolean("runtime.debug", false) && changed > 0) {
                        plugin.getLogger().info("Tracking pause heartbeat changed " + changed + " contract state(s).\n");
                    }
                });
    }

    private void refreshOnlineTrackers() {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            refreshPlayer(player);
        }
    }

    private void refreshPlayer(Player hunter) {
        repository.activeForHunter(hunter.getUniqueId(), Instant.now())
                .thenAccept(active -> MainThread.run(plugin, () -> {
                    if (!hunter.isOnline()) return;
                    Map<UUID, TrackingRepository.TrackingContract> byId = new HashMap<>();
                    for (TrackingRepository.TrackingContract contract : active) byId.put(contract.contractId(), contract);

                    ItemStack[] contents = hunter.getInventory().getContents();
                    boolean changed = false;
                    for (int slot = 0; slot < contents.length; slot++) {
                        ItemStack item = contents[slot];
                        UUID id = trackerContract(item);
                        if (id == null) continue;
                        TrackingRepository.TrackingContract contract = byId.get(id);
                        if (contract == null) {
                            hunter.getInventory().setItem(slot, null);
                        } else {
                            hunter.getInventory().setItem(slot, updateTracker(item, hunter, contract));
                        }
                        changed = true;
                    }
                    if (changed) hunter.updateInventory();
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Bounty tracker refresh failed for " + hunter.getName() + ": " + root(ex));
                    return null;
                });
    }

    private ItemStack createTracker(Player hunter, TrackingRepository.TrackingContract contract) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        return updateTracker(compass, hunter, contract);
    }

    private ItemStack updateTracker(ItemStack item, Player hunter, TrackingRepository.TrackingContract contract) {
        if (item.getType() != Material.COMPASS) item.setType(Material.COMPASS);
        CompassMeta meta = (CompassMeta) item.getItemMeta();
        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(contract.targetUuid());
        Player target = Bukkit.getPlayer(contract.targetUuid());
        String targetName = safeName(offlineTarget);

        meta.setDisplayName(ChatColor.GOLD + "Bounty Tracker");
        meta.getPersistentDataContainer().set(contractKey, PersistentDataType.STRING, contract.contractId().toString());

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Target: " + ChatColor.WHITE + targetName);
        lore.add(ChatColor.GRAY + "Contract: " + ChatColor.DARK_GRAY + shortId(contract.contractId()));
        lore.add(ChatColor.GRAY + "Remaining: " + ChatColor.WHITE + remaining(contract.expiresAt()));
        lore.add("");

        String lostReason = signalLostReason(hunter, target, contract.paused());
        if (lostReason != null) {
            meta.setLodestone(hunter.getLocation());
            meta.setLodestoneTracked(false);
            lore.add(ChatColor.RED + "Signal: LOST");
            lore.add(ChatColor.GRAY + lostReason);
            if (target == null || contract.paused() || (target != null && pauseWorld(target.getWorld().getName()))) {
                lore.add(ChatColor.YELLOW + "Contract timer: PAUSED");
            }
        } else {
            Location targetLocation = target.getLocation();
            double distance = hunter.getLocation().distance(targetLocation);
            int offset = maxOffset(distance);
            Location approximate = approximate(targetLocation, offset);
            meta.setLodestone(approximate);
            meta.setLodestoneTracked(false);
            lore.add(ChatColor.GREEN + "Signal: DETECTED");
            lore.add(ChatColor.GRAY + "Accuracy: " + ChatColor.YELLOW + "±" + offset + " blocks");
            lore.add(ChatColor.DARK_GRAY + "Posisi sengaja tidak presisi.");
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Tracker diperbarui berkala.");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String signalLostReason(Player hunter, Player target, boolean repositoryPaused) {
        if (target == null || !target.isOnline()) return "Target sedang offline.";
        if (pauseWorld(target.getWorld().getName())) return "Target berada di area aman/lobby.";
        if (repositoryPaused) return "Sinyal target sedang dipause.";
        if (!hunter.getWorld().equals(target.getWorld())) return "Target berada di world/dimensi lain.";
        return null;
    }

    private int maxOffset(double distance) {
        int closeDistance = positiveInt("tracking.accuracy.close-max-distance", 300);
        int mediumDistance = Math.max(closeDistance, positiveInt("tracking.accuracy.medium-max-distance", 1000));
        int farDistance = Math.max(mediumDistance, positiveInt("tracking.accuracy.far-max-distance", 2000));
        return TrackerAccuracy.maxOffset(
                distance, closeDistance, mediumDistance, farDistance,
                nonNegativeInt("tracking.accuracy.close-offset", 25),
                nonNegativeInt("tracking.accuracy.medium-offset", 50),
                nonNegativeInt("tracking.accuracy.far-offset", 80),
                nonNegativeInt("tracking.accuracy.very-far-offset", 120)
        );
    }

    private Location approximate(Location target, int maxOffset) {
        if (maxOffset <= 0) return target.clone();
        double minRadius = Math.max(3.0, maxOffset / 3.0);
        double radius = ThreadLocalRandom.current().nextDouble(minRadius, maxOffset + 0.0001);
        double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
        return target.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    private UUID trackerContract(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(contractKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean pauseWorld(String world) {
        Set<String> worlds = new HashSet<>();
        List<String> configured = plugin.getConfig().getStringList("tracking.pause-worlds");
        if (configured.isEmpty()) configured = List.of("lobby");
        for (String value : configured) worlds.add(value.toLowerCase(Locale.ROOT));
        return worlds.contains(world.toLowerCase(Locale.ROOT));
    }

    private int positiveInt(String path, int fallback) {
        return Math.max(1, plugin.getConfig().getInt(path, fallback));
    }

    private int nonNegativeInt(String path, int fallback) {
        return Math.max(0, plugin.getConfig().getInt(path, fallback));
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String remaining(Instant expiresAt) {
        long seconds = Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
