package store.cadera.cdrbounty.contract;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.tracking.BountyTrackerService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class ContractGuiService implements Listener {
    private static final String BOARD_TITLE = ChatColor.DARK_GRAY + "Bounty Board";
    private static final String MY_TITLE = ChatColor.DARK_GRAY + "My Contracts";

    private final JavaPlugin plugin;
    private final ContractService contracts;
    private final VaultEconomyAdapter economy;
    private final BountyTrackerService trackers;
    private final NamespacedKey contractKey;
    private final NamespacedKey acceptedKey;

    public ContractGuiService(JavaPlugin plugin, ContractService contracts, VaultEconomyAdapter economy,
                              BountyTrackerService trackers) {
        this.plugin = plugin;
        this.contracts = contracts;
        this.economy = economy;
        this.trackers = trackers;
        this.contractKey = new NamespacedKey(plugin, "contract_id");
        this.acceptedKey = new NamespacedKey(plugin, "contract_accepted");
    }

    public void open(Player player) {
        load(player, false);
    }

    public void openMine(Player player) {
        load(player, true);
    }

    private void load(Player player, boolean mineOnly) {
        int limit = mineOnly ? 200 : 45;
        contracts.browse(player.getUniqueId(), player.hasPermission("cdrbounty.admin.contracts"), limit)
                .thenAccept(entries -> MainThread.run(plugin, () -> {
                    if (!player.isOnline()) return;
                    List<ContractRepository.ContractView> shown = mineOnly
                            ? entries.stream().filter(ContractRepository.ContractView::viewerAccepted).limit(45).toList()
                            : entries.stream().limit(45).toList();
                    render(player, shown, mineOnly);
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Contract GUI failed: " + ex.getMessage());
                    MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Contract browser gagal dimuat."));
                    return null;
                });
    }

    private void render(Player player, List<ContractRepository.ContractView> entries, boolean mineOnly) {
        Inventory inventory = Bukkit.createInventory(null, 54, mineOnly ? MY_TITLE : BOARD_TITLE);
        int slot = 0;
        for (ContractRepository.ContractView entry : entries) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item(entry, mineOnly));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, simple(Material.PAPER,
                    mineOnly ? ChatColor.GRAY + "Belum Ada Contract" : ChatColor.GRAY + "Board Kosong",
                    List.of(mineOnly ? ChatColor.DARK_GRAY + "Accept bounty dari Bounty Board dulu."
                                     : ChatColor.DARK_GRAY + "Belum ada contract yang tersedia.")));
        }
        inventory.setItem(49, simple(mineOnly ? Material.WRITABLE_BOOK : Material.COMPASS,
                mineOnly ? ChatColor.AQUA + "My Contracts" : ChatColor.GOLD + "Bounty Board",
                mineOnly
                        ? List.of(ChatColor.GRAY + "Left click = ambil ulang tracker.",
                                  ChatColor.GRAY + "Right click = abandon contract.")
                        : List.of(ChatColor.GRAY + "Left click = accept.",
                                  ChatColor.GRAY + "Accepted: left = tracker, right = abandon.")));
        inventory.setItem(53, action(Material.CLOCK, "refresh", ChatColor.GREEN + "Refresh",
                List.of(ChatColor.GRAY + "Muat ulang daftar.")));
        player.openInventory(inventory);
    }

    private ItemStack item(ContractRepository.ContractView view, boolean mineOnly) {
        BountyContract contract = view.contract();
        ItemStack item = new ItemStack(view.viewerAccepted() ? Material.COMPASS : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        OfflinePlayer target = Bukkit.getOfflinePlayer(contract.targetUuid());
        meta.setDisplayName(ChatColor.GOLD + "Target: " + ChatColor.WHITE + safeName(target));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + economy.format(contract.rewardAmount()));
        lore.add(ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(contract.flags()));
        lore.add(ChatColor.GRAY + "State: " + ChatColor.WHITE + contract.state().name());
        lore.add(ChatColor.GRAY + "Hunters: " + ChatColor.WHITE + view.activeHunters() + "/" + contract.reservationLimit());
        lore.add(ChatColor.GRAY + "Remaining: " + ChatColor.WHITE + remaining(contract.expiresAt()));
        if (view.issuerVisible()) {
            OfflinePlayer issuer = Bukkit.getOfflinePlayer(contract.issuerUuid());
            lore.add(ChatColor.GRAY + "Issuer: " + ChatColor.WHITE + safeName(issuer));
        } else {
            lore.add(ChatColor.GRAY + "Issuer: " + ChatColor.DARK_GRAY + "Anonymous");
        }
        if (!view.conditions().isEmpty()) {
            lore.add("");
            lore.add(ChatColor.YELLOW + "Conditions:");
            for (ContractCondition condition : view.conditions()) {
                lore.add(ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + condition.description());
            }
        }
        lore.add("");
        if (view.viewerAccepted()) {
            lore.add(ChatColor.GREEN + "ACCEPTED");
            lore.add(ChatColor.GOLD + "Left click untuk ambil ulang Bounty Tracker.");
            lore.add(ChatColor.RED + "Right click untuk abandon.");
        } else if (!mineOnly) {
            lore.add(ChatColor.GREEN + "Left click untuk accept.");
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(contractKey, PersistentDataType.STRING, contract.id().toString());
        meta.getPersistentDataContainer().set(acceptedKey, PersistentDataType.INTEGER, view.viewerAccepted() ? 1 : 0);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if ((!BOARD_TITLE.equals(title) && !MY_TITLE.equals(title)) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "contract_gui_action"), PersistentDataType.STRING);
        if ("refresh".equals(action)) {
            load(player, MY_TITLE.equals(title));
            return;
        }
        String raw = meta.getPersistentDataContainer().get(contractKey, PersistentDataType.STRING);
        if (raw == null) return;
        UUID contractId;
        try { contractId = UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return; }
        Integer acceptedRaw = meta.getPersistentDataContainer().get(acceptedKey, PersistentDataType.INTEGER);
        boolean accepted = acceptedRaw != null && acceptedRaw == 1;
        boolean mine = MY_TITLE.equals(title);

        if (accepted) {
            if (event.isLeftClick()) {
                trackers.issue(player, contractId);
                return;
            }
            if (!event.isRightClick()) return;
            contracts.abandon(player, contractId).thenAccept(result -> MainThread.run(plugin, () -> {
                if (result.success()) trackers.removeContract(player, contractId);
                player.sendMessage(result.success() ? ChatColor.YELLOW + "Contract ditinggalkan."
                        : ChatColor.RED + "Tidak dapat abandon: " + result.reason());
                load(player, mine);
            })).exceptionally(ex -> failure(player, ex));
            return;
        }
        if (mine || !event.isLeftClick()) return;
        contracts.accept(player, contractId).thenAccept(result -> MainThread.run(plugin, () -> {
            if (result.success()) trackers.issue(player, contractId);
            player.sendMessage(result.success() ? ChatColor.GREEN + "Contract diterima. Tracker sedang disiapkan."
                    : ChatColor.RED + "Contract ditolak: " + result.reason());
            load(player, false);
        })).exceptionally(ex -> failure(player, ex));
    }

    private ItemStack action(Material material, String action, String name, List<String> lore) {
        ItemStack item = simple(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "contract_gui_action"), PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simple(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Void failure(Player player, Throwable ex) {
        plugin.getLogger().warning("Contract GUI action failed: " + ex.getMessage());
        MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Aksi contract gagal diproses."));
        return null;
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
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
}
