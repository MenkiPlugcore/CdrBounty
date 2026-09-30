package store.cadera.cdrbounty.approval;

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
import store.cadera.cdrbounty.contract.BountyContract;
import store.cadera.cdrbounty.contract.ContractCondition;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class ApprovalAdminGui implements Listener {
    private static final String LIST_TITLE = ChatColor.DARK_GRAY + "Bounty Approval";
    private static final String DETAIL_TITLE = ChatColor.DARK_GRAY + "Bounty Review";

    private final JavaPlugin plugin;
    private final BountyApprovalService approvals;
    private final VaultEconomyAdapter economy;
    private final NamespacedKey contractKey;
    private final NamespacedKey actionKey;

    public ApprovalAdminGui(JavaPlugin plugin, BountyApprovalService approvals, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.approvals = approvals;
        this.economy = economy;
        this.contractKey = new NamespacedKey(plugin, "approval_contract_id");
        this.actionKey = new NamespacedKey(plugin, "approval_action");
    }

    public void open(Player player) {
        if (!player.hasPermission("cdrbounty.admin.approval")) {
            player.sendMessage(ChatColor.RED + "Kamu tidak punya izin membuka approval bounty.");
            return;
        }
        approvals.pending(45).thenAccept(requests -> MainThread.run(plugin, () -> renderList(player, requests)))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Approval GUI load failed: " + root(ex));
                    MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + "Approval queue gagal dimuat."));
                    return null;
                });
    }

    private void renderList(Player player, List<ApprovalRepository.ApprovalRequest> requests) {
        if (!player.isOnline()) return;
        Inventory inventory = Bukkit.createInventory(null, 54, LIST_TITLE);
        int slot = 0;
        for (ApprovalRepository.ApprovalRequest request : requests) {
            if (slot >= 45) break;
            inventory.setItem(slot++, requestItem(request));
        }
        if (requests.isEmpty()) {
            inventory.setItem(22, simple(Material.LIME_DYE, ChatColor.GREEN + "Tidak Ada Request",
                    List.of(ChatColor.GRAY + "Semua request bounty sudah ditangani.")));
        }
        inventory.setItem(49, simple(Material.BOOK, ChatColor.GOLD + "Pending Approval",
                List.of(ChatColor.GRAY + "Jumlah: " + ChatColor.WHITE + requests.size(),
                        ChatColor.DARK_GRAY + "Klik request untuk review.")));
        inventory.setItem(53, action(Material.CLOCK, "refresh", null, ChatColor.GREEN + "Refresh",
                List.of(ChatColor.GRAY + "Muat ulang approval queue.")));
        player.openInventory(inventory);
    }

    private ItemStack requestItem(ApprovalRepository.ApprovalRequest request) {
        BountyContract contract = request.contract();
        OfflinePlayer issuer = Bukkit.getOfflinePlayer(contract.issuerUuid());
        OfflinePlayer target = Bukkit.getOfflinePlayer(contract.targetUuid());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Requester: " + ChatColor.WHITE + safeName(issuer));
        lore.add(ChatColor.GRAY + "Target: " + ChatColor.WHITE + safeName(target));
        lore.add(ChatColor.GRAY + "Reward: " + ChatColor.GOLD + economy.format(contract.rewardAmount()));
        lore.add(ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(contract.flags()));
        lore.add(ChatColor.GRAY + "Conditions: " + ChatColor.WHITE + request.conditions().size());
        lore.add(ChatColor.GRAY + "Waiting: " + ChatColor.WHITE + age(contract.createdAt()));
        lore.add("");
        lore.add(ChatColor.YELLOW + "Klik untuk review.");
        return action(Material.PAPER, "detail", contract.id(), ChatColor.GOLD + "Request: " + safeName(target), lore);
    }

    private void openDetail(Player player, UUID contractId) {
        approvals.pending(contractId).thenAccept(request -> MainThread.run(plugin, () -> {
            if (!player.isOnline()) return;
            if (request == null) {
                player.sendMessage(ChatColor.YELLOW + "Request itu sudah tidak pending.");
                open(player);
                return;
            }
            renderDetail(player, request);
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Approval detail failed: " + root(ex));
            return null;
        });
    }

    private void renderDetail(Player player, ApprovalRepository.ApprovalRequest request) {
        BountyContract contract = request.contract();
        OfflinePlayer issuer = Bukkit.getOfflinePlayer(contract.issuerUuid());
        OfflinePlayer target = Bukkit.getOfflinePlayer(contract.targetUuid());
        Inventory inventory = Bukkit.createInventory(null, 45, DETAIL_TITLE);

        inventory.setItem(10, simple(Material.PLAYER_HEAD, ChatColor.GOLD + "Target",
                List.of(ChatColor.WHITE + safeName(target), ChatColor.DARK_GRAY.toString() + contract.targetUuid())));
        inventory.setItem(12, simple(Material.NAME_TAG, ChatColor.GOLD + "Requester",
                List.of(ChatColor.WHITE + safeName(issuer), ChatColor.DARK_GRAY.toString() + contract.issuerUuid())));
        inventory.setItem(14, simple(Material.GOLD_INGOT, ChatColor.GOLD + "Reward",
                List.of(ChatColor.WHITE + economy.format(contract.rewardAmount()))));
        List<String> contractLore = new ArrayList<>();
        contractLore.add(ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(contract.flags()));
        contractLore.add(ChatColor.GRAY + "Waiting: " + ChatColor.WHITE + age(contract.createdAt()));
        contractLore.add(ChatColor.GRAY + "Conditions:");
        if (request.conditions().isEmpty()) contractLore.add(ChatColor.DARK_GRAY + "- none");
        else for (ContractCondition condition : request.conditions()) {
            contractLore.add(ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + condition.description());
        }
        inventory.setItem(16, simple(Material.COMPASS, ChatColor.AQUA + "Contract Detail", contractLore));

        inventory.setItem(20, action(Material.LIME_WOOL, "approve", contract.id(), ChatColor.GREEN + "APPROVE",
                List.of(ChatColor.GRAY + "Aktifkan bounty ini.", ChatColor.GRAY + "Timer bounty mulai dari approval.")));
        inventory.setItem(24, action(Material.RED_WOOL, "reject", contract.id(), ChatColor.RED + "REJECT",
                List.of(ChatColor.GRAY + "Tolak request dan refund penuh requester.",
                        ChatColor.DARK_GRAY + "Refund memakai recovery-safe transaction.")));
        inventory.setItem(40, action(Material.ARROW, "back", null, ChatColor.YELLOW + "Kembali",
                List.of(ChatColor.GRAY + "Ke approval queue.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!LIST_TITLE.equals(title) && !DETAIL_TITLE.equals(title)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        String rawId = meta.getPersistentDataContainer().get(contractKey, PersistentDataType.STRING);
        UUID contractId = null;
        if (rawId != null) {
            try { contractId = UUID.fromString(rawId); }
            catch (IllegalArgumentException ignored) { return; }
        }

        switch (action) {
            case "refresh", "back" -> open(player);
            case "detail" -> { if (contractId != null) openDetail(player, contractId); }
            case "approve" -> { if (contractId != null) approve(player, contractId); }
            case "reject" -> { if (contractId != null) reject(player, contractId); }
            default -> { }
        }
    }

    private void approve(Player player, UUID contractId) {
        player.closeInventory();
        approvals.approve(player, contractId).thenAccept(result -> MainThread.run(plugin, () -> {
            player.sendMessage(result.success()
                    ? ChatColor.GREEN + "Bounty disetujui dan sekarang aktif."
                    : ChatColor.RED + "Approval gagal: " + result.reason());
            open(player);
        })).exceptionally(ex -> failure(player, "Approve", ex));
    }

    private void reject(Player player, UUID contractId) {
        player.closeInventory();
        approvals.reject(player, contractId).thenAccept(result -> MainThread.run(plugin, () -> {
            player.sendMessage(result.success()
                    ? ChatColor.YELLOW + "Bounty ditolak. Saldo requester sudah direfund penuh."
                    : ChatColor.RED + "Reject gagal: " + result.reason());
            open(player);
        })).exceptionally(ex -> failure(player, "Reject", ex));
    }

    private Void failure(Player player, String action, Throwable ex) {
        plugin.getLogger().warning(action + " approval failed: " + root(ex));
        MainThread.run(plugin, () -> player.sendMessage(ChatColor.RED + action + " gagal. Jika refund sudah dimulai, recovery akan menyelesaikannya."));
        return null;
    }

    private ItemStack action(Material material, String action, UUID contractId, String name, List<String> lore) {
        ItemStack item = simple(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (contractId != null) meta.getPersistentDataContainer().set(contractKey, PersistentDataType.STRING, contractId.toString());
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

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String age(Instant createdAt) {
        long seconds = Math.max(0L, Duration.between(createdAt, Instant.now()).getSeconds());
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
