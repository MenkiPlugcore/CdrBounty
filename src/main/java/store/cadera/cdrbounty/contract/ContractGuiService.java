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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class ContractGuiService implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "CdrBounty Contracts";

    private final JavaPlugin plugin;
    private final ContractService contracts;
    private final VaultEconomyAdapter economy;
    private final NamespacedKey contractKey;

    public ContractGuiService(JavaPlugin plugin, ContractService contracts, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.contracts = contracts;
        this.economy = economy;
        this.contractKey = new NamespacedKey(plugin, "contract_id");
    }

    public void open(Player player) {
        contracts.browse(player.getUniqueId(), player.hasPermission("cdrbounty.admin.contracts"), 45)
                .thenAccept(entries -> MainThread.run(plugin, () -> render(player, entries)))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Contract GUI failed: " + ex.getMessage());
                    MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Contract browser gagal dimuat."));
                    return null;
                });
    }

    private void render(Player player, List<ContractRepository.ContractView> entries) {
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);
        int slot = 0;
        for (ContractRepository.ContractView entry : entries) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item(entry));
        }
        ItemStack info = new ItemStack(Material.COMPASS);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Bounty Contract Board");
        meta.setLore(List.of(
                ChatColor.GRAY + "Left click: accept contract",
                ChatColor.GRAY + "Right click: abandon accepted contract",
                ChatColor.DARK_GRAY + "Progress is per-player."
        ));
        info.setItemMeta(meta);
        inventory.setItem(49, info);
        player.openInventory(inventory);
    }

    private ItemStack item(ContractRepository.ContractView view) {
        BountyContract contract = view.contract();
        ItemStack item = new ItemStack(view.viewerAccepted() ? Material.WRITABLE_BOOK : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        OfflinePlayer target = Bukkit.getOfflinePlayer(contract.targetUuid());
        String targetName = target.getName() == null ? contract.targetUuid().toString() : target.getName();
        meta.setDisplayName(ChatColor.GOLD + "Contract: " + ChatColor.WHITE + targetName);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + economy.format(contract.rewardAmount()));
        lore.add(ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(contract.flags()));
        lore.add(ChatColor.GRAY + "State: " + ChatColor.WHITE + contract.state().name());
        lore.add(ChatColor.GRAY + "Hunters: " + ChatColor.WHITE + view.activeHunters() + "/" + contract.reservationLimit());
        lore.add(ChatColor.GRAY + "Remaining: " + ChatColor.WHITE + remaining(contract.expiresAt()));
        if (view.issuerVisible()) {
            OfflinePlayer issuer = Bukkit.getOfflinePlayer(contract.issuerUuid());
            lore.add(ChatColor.GRAY + "Issuer: " + ChatColor.WHITE
                    + (issuer.getName() == null ? contract.issuerUuid().toString() : issuer.getName()));
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
        lore.add(view.viewerAccepted()
                ? ChatColor.RED + "Right click to abandon"
                : ChatColor.GREEN + "Left click to accept");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(contractKey, PersistentDataType.STRING, contract.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle()) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String raw = item.getItemMeta().getPersistentDataContainer().get(contractKey, PersistentDataType.STRING);
        if (raw == null) return;
        UUID contractId;
        try { contractId = UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return; }

        var future = event.isRightClick() ? contracts.abandon(player, contractId) : contracts.accept(player, contractId);
        future.thenAccept(result -> MainThread.run(plugin, () -> {
            if (result.success()) player.sendMessage(ChatColor.GREEN + "Contract updated: " + result.reason());
            else player.sendMessage(ChatColor.RED + "Contract ditolak: " + result.reason());
            open(player);
        })).exceptionally(ex -> {
            MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Contract action gagal."));
            return null;
        });
    }

    private static String remaining(Instant expiresAt) {
        long seconds = Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
