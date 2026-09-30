package store.cadera.cdrbounty.npc;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.contract.ContractGuiService;
import store.cadera.cdrbounty.contract.ContractService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class BountyNpcService implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "Bounty Master";

    private final JavaPlugin plugin;
    private final BountyNpcBindingService binding;
    private final ContractGuiService board;
    private final ContractService contracts;
    private final VaultEconomyAdapter economy;
    private final NamespacedKey actionKey;
    private final Map<UUID, PlacementInput> placementInputs = new HashMap<>();

    public BountyNpcService(JavaPlugin plugin, BountyNpcBindingService binding,
                            ContractGuiService board, ContractService contracts,
                            VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.binding = binding;
        this.board = board;
        this.contracts = contracts;
        this.economy = economy;
        this.actionKey = new NamespacedKey(plugin, "bounty_npc_action");
    }

    @EventHandler
    public void onNpcClick(NPCRightClickEvent event) {
        if (!binding.matches(event.getNPC())) return;
        openMain(event.getClicker());
    }

    public void openMain(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);
        inventory.setItem(11, actionItem(Material.COMPASS, "board",
                ChatColor.GOLD + "Bounty Board",
                List.of(ChatColor.GRAY + "Lihat contract yang tersedia.", ChatColor.GRAY + "Klik contract untuk accept/abandon.")));
        inventory.setItem(15, actionItem(Material.GOLD_INGOT, "create",
                ChatColor.YELLOW + "Pasang Bounty",
                List.of(ChatColor.GRAY + "Buat PUBLIC bounty baru.", ChatColor.GRAY + "Target dan nominal diisi lewat chat.")));
        inventory.setItem(22, actionItem(Material.BARRIER, "close",
                ChatColor.RED + "Tutup", List.of(ChatColor.GRAY + "Keluar dari menu Bounty Master.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle()) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        switch (action) {
            case "board" -> board.open(player);
            case "create" -> {
                if (!player.hasPermission("cdrbounty.contract.create")) {
                    player.sendMessage(ChatColor.RED + "Kamu tidak punya izin memasang bounty.");
                    return;
                }
                beginPlacement(player);
            }
            case "close" -> player.closeInventory();
            default -> { }
        }
    }

    private void beginPlacement(Player player) {
        placementInputs.put(player.getUniqueId(), new PlacementInput(Stage.TARGET, null));
        player.closeInventory();
        player.sendMessage(ChatColor.GOLD + "Bounty Master: " + ChatColor.GRAY + "Ketik nama target di chat.");
        player.sendMessage(ChatColor.DARK_GRAY + "Ketik 'batal' untuk membatalkan.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PlacementInput input = placementInputs.get(player.getUniqueId());
        if (input == null) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleChat(player, message));
    }

    private void handleChat(Player player, String message) {
        PlacementInput input = placementInputs.get(player.getUniqueId());
        if (input == null) return;
        if (message.equalsIgnoreCase("batal") || message.equalsIgnoreCase("cancel")) {
            placementInputs.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Pemasangan bounty dibatalkan.");
            return;
        }

        if (input.stage() == Stage.TARGET) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(message);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                player.sendMessage(ChatColor.RED + "Player tidak ditemukan. Ketik nama target lain atau 'batal'.");
                return;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Kamu tidak bisa memasang bounty pada diri sendiri.");
                return;
            }
            placementInputs.put(player.getUniqueId(), new PlacementInput(Stage.AMOUNT, target.getUniqueId()));
            player.sendMessage(ChatColor.GOLD + "Bounty Master: " + ChatColor.GRAY + "Target: " + ChatColor.WHITE + safeName(target));
            player.sendMessage(ChatColor.GRAY + "Sekarang ketik nominal bounty di chat, contoh: " + ChatColor.WHITE + "25000");
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(message.replace(",", ""));
        } catch (NumberFormatException ex) {
            player.sendMessage(ChatColor.RED + "Nominal tidak valid. Masukkan angka atau ketik 'batal'.");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(input.targetUuid());
        placementInputs.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "Memproses bounty untuk " + ChatColor.WHITE + safeName(target) + ChatColor.GRAY + "...");
        contracts.create(player, target, amount, Set.of(ContractFlag.PUBLIC), Set.of(), List.of())
                .thenAccept(result -> MainThread.run(plugin, () -> {
                    if (!result.success()) {
                        player.sendMessage(ChatColor.RED + "Bounty gagal dibuat: " + ChatColor.GRAY + result.reason());
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN + "Bounty berhasil dipasang pada " + ChatColor.WHITE + safeName(target)
                            + ChatColor.GREEN + " sebesar " + ChatColor.GOLD + economy.format(result.rewardAmount()) + ChatColor.GREEN + ".");
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("NPC bounty placement failed: " + rootMessage(ex));
                    MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Terjadi kesalahan saat memasang bounty."));
                    return null;
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        placementInputs.remove(event.getPlayer().getUniqueId());
    }

    private ItemStack actionItem(Material material, String action, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum Stage { TARGET, AMOUNT }
    private record PlacementInput(Stage stage, UUID targetUuid) { }
}
