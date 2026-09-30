package store.cadera.cdrbounty.npc;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.contract.ContractGuiService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public final class BountyNpcService implements Listener {
    private static final String TITLE = ChatColor.DARK_GRAY + "Bounty Master";

    private final JavaPlugin plugin;
    private final BountyNpcBindingService binding;
    private final ContractGuiService board;
    private final BountyPlacementWizard wizard;
    private final NamespacedKey actionKey;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public BountyNpcService(JavaPlugin plugin, BountyNpcBindingService binding,
                            ContractGuiService board, BountyPlacementWizard wizard) {
        this.plugin = plugin;
        this.binding = binding;
        this.board = board;
        this.wizard = wizard;
        this.actionKey = new NamespacedKey(plugin, "bounty_npc_action");
    }

    @EventHandler
    public void onNpcClick(NPCRightClickEvent event) {
        if (!binding.matches(event.getNPC())) return;
        Player player = event.getClicker();
        long now = System.currentTimeMillis();
        long cooldown = Math.max(250L, Math.min(3000L,
                plugin.getConfig().getLong("npc.click-cooldown-millis", 600L)));
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < cooldown) return;
        lastClick.put(player.getUniqueId(), now);
        openMain(player);
    }

    public void openMain(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);
        inventory.setItem(10, actionItem(Material.COMPASS, "board",
                ChatColor.GOLD + "Bounty Board",
                List.of(ChatColor.GRAY + "Lihat contract yang tersedia.",
                        ChatColor.GRAY + "Accept/abandon langsung dari GUI.")));
        inventory.setItem(13, actionItem(Material.WRITABLE_BOOK, "mine",
                ChatColor.AQUA + "My Contracts",
                List.of(ChatColor.GRAY + "Contract yang sedang kamu buru.",
                        ChatColor.DARK_GRAY + "Progress tetap per-player.")));
        inventory.setItem(16, actionItem(Material.GOLD_INGOT, "create",
                ChatColor.YELLOW + "Pasang Bounty",
                List.of(ChatColor.GRAY + "Wizard NPC dengan confirmation.",
                        ChatColor.GRAY + "Public/Private/Exclusive/Anonymous",
                        ChatColor.GRAY + "+ World/Weapon conditions.")));
        if (wizard.hasDraft(player)) {
            inventory.setItem(22, actionItem(Material.CLOCK, "continue",
                    ChatColor.GREEN + "Lanjutkan Draft",
                    List.of(ChatColor.GRAY + "Tahap: " + ChatColor.WHITE + wizard.draftStage(player))));
        }
        inventory.setItem(26, actionItem(Material.BARRIER, "close",
                ChatColor.RED + "Tutup", List.of(ChatColor.GRAY + "Keluar dari menu.")));
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
            case "mine" -> board.openMine(player);
            case "create" -> wizard.start(player);
            case "continue" -> wizard.resume(player);
            case "close" -> player.closeInventory();
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
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
}
