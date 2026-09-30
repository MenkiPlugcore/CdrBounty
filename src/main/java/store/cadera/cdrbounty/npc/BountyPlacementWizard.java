package store.cadera.cdrbounty.npc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
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
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.contract.ContractCondition;
import store.cadera.cdrbounty.contract.ContractConditionType;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.contract.ContractService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@SuppressWarnings("deprecation")
public final class BountyPlacementWizard implements Listener {
    private static final String SETUP_TITLE = ChatColor.DARK_GRAY + "Bounty Setup";
    private static final String CONDITIONS_TITLE = ChatColor.DARK_GRAY + "Bounty Conditions";
    private static final String WORLD_TITLE = ChatColor.DARK_GRAY + "World Condition";
    private static final String CONFIRM_TITLE = ChatColor.DARK_GRAY + "Confirm Bounty";

    private final JavaPlugin plugin;
    private final ContractService contracts;
    private final VaultEconomyAdapter economy;
    private final Supplier<PluginSettings> settings;
    private final NamespacedKey actionKey;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> submitting = ConcurrentHashMap.newKeySet();

    public BountyPlacementWizard(JavaPlugin plugin, ContractService contracts,
                                 VaultEconomyAdapter economy, Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.contracts = contracts;
        this.economy = economy;
        this.settings = settings;
        this.actionKey = new NamespacedKey(plugin, "bounty_wizard_action");
    }

    public boolean hasDraft(Player player) {
        return session(player, false) != null;
    }

    public String draftStage(Player player) {
        Session session = session(player, false);
        return session == null ? "NONE" : stageLabel(session.stage);
    }

    public void start(Player player) {
        if (!player.hasPermission("cdrbounty.contract.create")) {
            player.sendMessage(ChatColor.RED + "Kamu tidak punya izin memasang bounty.");
            return;
        }
        if (submitting.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Bounty sebelumnya masih diproses.");
            return;
        }
        Session session = new Session();
        sessions.put(player.getUniqueId(), session);
        promptTarget(player, session);
    }

    public void resume(Player player) {
        Session session = session(player, true);
        if (session == null) return;
        switch (session.stage) {
            case TARGET -> promptTarget(player, session);
            case AMOUNT -> promptAmount(player, session);
            case PRIVATE_HUNTERS -> promptPrivateHunters(player, session);
            case OPTIONS -> openSetup(player);
            case CONDITIONS -> openConditions(player);
            case WORLD -> openWorldMenu(player);
            case CONFIRM -> openConfirm(player);
        }
    }

    private void promptTarget(Player player, Session session) {
        session.stage = Stage.TARGET;
        armTimeout(player, session);
        player.closeInventory();
        player.sendMessage(ChatColor.GOLD + "Bounty Master: " + ChatColor.GRAY + "Ketik nama target di chat.");
        player.sendMessage(ChatColor.DARK_GRAY + "'batal' = cancel. Input tidak masuk public chat.");
    }

    private void promptAmount(Player player, Session session) {
        session.stage = Stage.AMOUNT;
        armTimeout(player, session);
        player.closeInventory();
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid);
        player.sendMessage(ChatColor.GOLD + "Target: " + ChatColor.WHITE + safeName(target));
        player.sendMessage(ChatColor.GRAY + "Ketik nominal bounty. 'kembali' = ganti target, 'batal' = cancel.");
    }

    private void promptPrivateHunters(Player player, Session session) {
        session.stage = Stage.PRIVATE_HUNTERS;
        armTimeout(player, session);
        player.closeInventory();
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Private Contract: " + ChatColor.GRAY + "ketik hunter yang diizinkan.");
        player.sendMessage(ChatColor.GRAY + "Pisahkan dengan koma, contoh: " + ChatColor.WHITE + "HunterA,HunterB");
        player.sendMessage(ChatColor.DARK_GRAY + "'kembali' = setup, 'batal' = cancel.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || !session.stage.chatInput()) return;
        event.setCancelled(true);
        String text = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleChat(event.getPlayer(), text));
    }

    private void handleChat(Player player, String text) {
        Session session = session(player, true);
        if (session == null) return;
        if (text.equalsIgnoreCase("batal") || text.equalsIgnoreCase("cancel")) {
            cancel(player, false);
            return;
        }
        if (text.equalsIgnoreCase("kembali") || text.equalsIgnoreCase("back")) {
            if (session.stage == Stage.AMOUNT) promptTarget(player, session);
            else if (session.stage == Stage.PRIVATE_HUNTERS) openSetup(player);
            else cancel(player, false);
            return;
        }

        switch (session.stage) {
            case TARGET -> handleTarget(player, session, text);
            case AMOUNT -> handleAmount(player, session, text);
            case PRIVATE_HUNTERS -> handlePrivateHunters(player, session, text);
            default -> { }
        }
    }

    private void handleTarget(Player player, Session session, String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            armTimeout(player, session);
            player.sendMessage(ChatColor.RED + "Player tidak ditemukan. Coba nama lain atau 'batal'.");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            armTimeout(player, session);
            player.sendMessage(ChatColor.RED + "Kamu tidak bisa memasang bounty pada diri sendiri.");
            return;
        }
        session.targetUuid = target.getUniqueId();
        promptAmount(player, session);
    }

    private void handleAmount(Player player, Session session, String raw) {
        BigDecimal amount;
        try {
            amount = MoneyMath.normalize(new BigDecimal(raw.replace(",", "").replace("_", "")), settings.get().decimalScale());
        } catch (RuntimeException ex) {
            armTimeout(player, session);
            player.sendMessage(ChatColor.RED + "Nominal tidak valid.");
            return;
        }
        PluginSettings cfg = settings.get();
        if (amount.compareTo(cfg.minimumBounty()) < 0 || amount.compareTo(cfg.maximumBounty()) > 0) {
            armTimeout(player, session);
            player.sendMessage(ChatColor.RED + "Nominal harus antara " + economy.format(cfg.minimumBounty())
                    + " dan " + economy.format(cfg.maximumBounty()) + ".");
            return;
        }
        session.amount = amount;
        openSetup(player);
    }

    private void handlePrivateHunters(Player player, Session session, String raw) {
        Set<UUID> allowed = new LinkedHashSet<>();
        for (String name : raw.split("[,\\s]+")) {
            if (name.isBlank()) continue;
            OfflinePlayer hunter = Bukkit.getOfflinePlayer(name.trim());
            if (!hunter.isOnline() && !hunter.hasPlayedBefore()) {
                armTimeout(player, session);
                player.sendMessage(ChatColor.RED + "Hunter tidak ditemukan: " + name.trim());
                return;
            }
            if (hunter.getUniqueId().equals(player.getUniqueId()) || hunter.getUniqueId().equals(session.targetUuid)) {
                armTimeout(player, session);
                player.sendMessage(ChatColor.RED + "Issuer/target tidak boleh masuk private allowlist.");
                return;
            }
            allowed.add(hunter.getUniqueId());
        }
        if (allowed.isEmpty()) {
            armTimeout(player, session);
            player.sendMessage(ChatColor.RED + "Private contract membutuhkan minimal satu hunter.");
            return;
        }
        session.allowedHunters.clear();
        session.allowedHunters.addAll(allowed);
        session.flags.remove(ContractFlag.PUBLIC);
        session.flags.add(ContractFlag.PRIVATE);
        openSetup(player);
    }

    private void openSetup(Player player) {
        Session session = session(player, true);
        if (session == null) return;
        if (session.targetUuid == null || session.amount == null) {
            promptTarget(player, session);
            return;
        }
        session.stage = Stage.OPTIONS;
        session.expiresAt = 0L;
        boolean priv = session.flags.contains(ContractFlag.PRIVATE);
        boolean exclusive = session.flags.contains(ContractFlag.EXCLUSIVE);
        boolean anonymous = session.flags.contains(ContractFlag.ANONYMOUS);
        Inventory inv = Bukkit.createInventory(null, 45, SETUP_TITLE);
        inv.setItem(10, actionItem(priv ? Material.NAME_TAG : Material.PAPER, "visibility",
                priv ? ChatColor.LIGHT_PURPLE + "Visibility: PRIVATE" : ChatColor.GREEN + "Visibility: PUBLIC",
                priv ? List.of(ChatColor.GRAY + "Allowed hunters: " + session.allowedHunters.size(), ChatColor.YELLOW + "Klik = PUBLIC")
                        : List.of(ChatColor.GRAY + "Semua hunter dapat melihat/accept.", ChatColor.YELLOW + "Klik = PRIVATE")));
        inv.setItem(12, actionItem(Material.IRON_SWORD, "exclusive",
                (exclusive ? ChatColor.GREEN : ChatColor.GRAY) + "Exclusive: " + (exclusive ? "ON" : "OFF"),
                List.of(ChatColor.GRAY + "ON = satu hunter saja.", ChatColor.YELLOW + "Klik untuk toggle.")));
        inv.setItem(14, actionItem(Material.GRAY_DYE, "anonymous",
                (anonymous ? ChatColor.GREEN : ChatColor.GRAY) + "Anonymous: " + (anonymous ? "ON" : "OFF"),
                List.of(ChatColor.GRAY + "Sembunyikan identitas issuer.", ChatColor.YELLOW + "Klik untuk toggle.")));
        inv.setItem(16, actionItem(Material.COMPASS, "conditions", ChatColor.AQUA + "Conditions", conditionLore(session.conditions, true)));
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid);
        inv.setItem(22, plainItem(Material.BOOK, ChatColor.GOLD + "Draft Summary",
                List.of(ChatColor.GRAY + "Target: " + ChatColor.WHITE + safeName(target),
                        ChatColor.GRAY + "Nominal: " + ChatColor.GOLD + economy.format(session.amount),
                        ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(session.flags),
                        ChatColor.GRAY + "Conditions: " + ChatColor.WHITE + session.conditions.size())));
        inv.setItem(36, actionItem(Material.ARROW, "back_amount", ChatColor.YELLOW + "Kembali", List.of(ChatColor.GRAY + "Ubah nominal.")));
        inv.setItem(40, actionItem(Material.BARRIER, "cancel", ChatColor.RED + "Batalkan", List.of(ChatColor.GRAY + "Hapus draft.")));
        inv.setItem(44, actionItem(Material.LIME_WOOL, "next", ChatColor.GREEN + "Lanjut Konfirmasi", List.of(ChatColor.GRAY + "Review sebelum saldo dipotong.")));
        player.openInventory(inv);
    }

    private void openConditions(Player player) {
        Session session = session(player, true);
        if (session == null) return;
        session.stage = Stage.CONDITIONS;
        session.expiresAt = 0L;
        Inventory inv = Bukkit.createInventory(null, 36, CONDITIONS_TITLE);
        ContractCondition world = first(session, ContractConditionType.REQUIRED_WORLD, ContractConditionType.FORBIDDEN_WORLD);
        ContractCondition weapon = first(session, ContractConditionType.REQUIRED_WEAPON);
        inv.setItem(11, actionItem(Material.MAP, "world", ChatColor.AQUA + "World Rule",
                List.of(ChatColor.GRAY + "Current: " + ChatColor.WHITE + (world == null ? "Any World" : world.description()),
                        ChatColor.YELLOW + "Klik untuk pilih.")));
        inv.setItem(15, actionItem(Material.IRON_SWORD, "weapon", ChatColor.AQUA + "Weapon Rule",
                List.of(ChatColor.GRAY + "Current: " + ChatColor.WHITE + (weapon == null ? "Any Weapon" : weapon.value()),
                        ChatColor.GRAY + "Pegang item di main hand lalu klik.", ChatColor.YELLOW + "Klik lagi untuk clear.")));
        inv.setItem(27, actionItem(Material.ARROW, "conditions_done", ChatColor.YELLOW + "Kembali", List.of(ChatColor.GRAY + "Ke setup.")));
        inv.setItem(31, plainItem(Material.BOOK, ChatColor.GOLD + "Condition Summary", conditionLore(session.conditions, false)));
        inv.setItem(35, actionItem(Material.BARRIER, "cancel", ChatColor.RED + "Batalkan", List.of(ChatColor.GRAY + "Hapus draft.")));
        player.openInventory(inv);
    }

    private void openWorld(Player player) {
        Session session = session(player, true);
        if (session == null) return;
        session.stage = Stage.WORLD;
        session.expiresAt = 0L;
        Inventory inv = Bukkit.createInventory(null, 54, WORLD_TITLE);
        int slot = 0;
        for (World world : Bukkit.getWorlds()) {
            if (slot >= 45) break;
            inv.setItem(slot++, actionItem(worldIcon(world), "world:" + world.getName(), ChatColor.AQUA + world.getName(),
                    List.of(ChatColor.GREEN + "Left click = REQUIRE", ChatColor.RED + "Right click = FORBID")));
        }
        inv.setItem(45, actionItem(Material.BUCKET, "clear_world", ChatColor.GRAY + "Any World", List.of(ChatColor.GRAY + "Hapus world condition.")));
        inv.setItem(49, actionItem(Material.ARROW, "world_back", ChatColor.YELLOW + "Kembali", List.of(ChatColor.GRAY + "Ke Conditions.")));
        inv.setItem(53, actionItem(Material.BARRIER, "cancel", ChatColor.RED + "Batalkan", List.of(ChatColor.GRAY + "Hapus draft.")));
        player.openInventory(inv);
    }

    private void openConfirm(Player player) {
        Session session = session(player, true);
        if (session == null) return;
        if (session.flags.contains(ContractFlag.PRIVATE) && session.allowedHunters.isEmpty()) {
            promptPrivateHunters(player, session);
            return;
        }
        session.stage = Stage.CONFIRM;
        session.expiresAt = 0L;
        PluginSettings cfg = settings.get();
        BigDecimal fee = MoneyMath.fee(session.amount, cfg.placementFeePercent(), cfg.decimalScale());
        BigDecimal reward = MoneyMath.escrow(session.amount, fee, cfg.decimalScale());
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid);
        Inventory inv = Bukkit.createInventory(null, 36, CONFIRM_TITLE);
        inv.setItem(10, plainItem(Material.PLAYER_HEAD, ChatColor.GOLD + "Target", List.of(ChatColor.WHITE + safeName(target))));
        inv.setItem(12, plainItem(Material.GOLD_INGOT, ChatColor.GOLD + "Economy",
                List.of(ChatColor.GRAY + "Nominal: " + ChatColor.WHITE + economy.format(session.amount),
                        ChatColor.GRAY + "Fee: " + ChatColor.RED + economy.format(fee),
                        ChatColor.GRAY + "Reward bersih: " + ChatColor.GREEN + economy.format(reward),
                        ChatColor.GRAY + "Saldo kamu: " + ChatColor.WHITE + economy.format(economy.balance(player)))));
        inv.setItem(14, plainItem(Material.NAME_TAG, ChatColor.GOLD + "Contract",
                List.of(ChatColor.GRAY + "Flags: " + ChatColor.WHITE + ContractFlag.serialize(session.flags),
                        ChatColor.GRAY + "Private hunters: " + ChatColor.WHITE + session.allowedHunters.size(),
                        ChatColor.GRAY + "Duration: " + ChatColor.WHITE + duration(cfg.durationSeconds()))));
        inv.setItem(16, plainItem(Material.COMPASS, ChatColor.GOLD + "Conditions", conditionLore(session.conditions, false)));
        inv.setItem(27, actionItem(Material.ARROW, "confirm_back", ChatColor.YELLOW + "Kembali", List.of(ChatColor.GRAY + "Ubah options.")));
        inv.setItem(31, actionItem(Material.LIME_WOOL, "confirm", ChatColor.GREEN + "CONFIRM BOUNTY",
                List.of(ChatColor.GRAY + "Saldo baru dipotong setelah klik ini.")));
        inv.setItem(35, actionItem(Material.BARRIER, "cancel", ChatColor.RED + "Batalkan", List.of(ChatColor.GRAY + "Tanpa memotong saldo.")));
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !managedTitle(event.getView().getTitle())) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        Session session = session(player, true);
        if (session == null) return;

        if (action.startsWith("world:")) {
            String name = action.substring(6);
            World world = Bukkit.getWorld(name);
            if (world == null) return;
            remove(session, ContractConditionType.REQUIRED_WORLD);
            remove(session, ContractConditionType.FORBIDDEN_WORLD);
            session.conditions.add(new ContractCondition(event.isRightClick()
                    ? ContractConditionType.FORBIDDEN_WORLD : ContractConditionType.REQUIRED_WORLD, world.getName()));
            openWorld(player);
            return;
        }

        switch (action) {
            case "visibility" -> toggleVisibility(player, session);
            case "exclusive" -> toggleExclusive(player, session);
            case "anonymous" -> toggleAnonymous(player, session);
            case "conditions" -> openConditions(player);
            case "next" -> openConfirm(player);
            case "back_amount" -> promptAmount(player, session);
            case "cancel" -> cancel(player, true);
            case "world" -> openWorld(player);
            case "weapon" -> toggleWeapon(player, session);
            case "conditions_done" -> openSetup(player);
            case "clear_world" -> { remove(session, ContractConditionType.REQUIRED_WORLD); remove(session, ContractConditionType.FORBIDDEN_WORLD); openWorld(player); }
            case "world_back" -> openConditions(player);
            case "confirm_back" -> openSetup(player);
            case "confirm" -> submit(player, session);
            default -> { }
        }
    }

    private void toggleVisibility(Player player, Session session) {
        if (session.flags.contains(ContractFlag.PRIVATE)) {
            session.flags.remove(ContractFlag.PRIVATE);
            session.flags.add(ContractFlag.PUBLIC);
            session.allowedHunters.clear();
            openSetup(player);
            return;
        }
        if (!player.hasPermission("cdrbounty.contract.private")) {
            player.sendMessage(ChatColor.RED + "Kamu tidak punya izin membuat private contract.");
            return;
        }
        session.flags.remove(ContractFlag.PUBLIC);
        session.flags.add(ContractFlag.PRIVATE);
        session.allowedHunters.clear();
        promptPrivateHunters(player, session);
    }

    private void toggleExclusive(Player player, Session session) {
        if (!player.hasPermission("cdrbounty.contract.exclusive")) {
            player.sendMessage(ChatColor.RED + "Kamu tidak punya izin membuat exclusive contract.");
            return;
        }
        if (session.flags.contains(ContractFlag.EXCLUSIVE)) session.flags.remove(ContractFlag.EXCLUSIVE);
        else session.flags.add(ContractFlag.EXCLUSIVE);
        openSetup(player);
    }

    private void toggleAnonymous(Player player, Session session) {
        if (!player.hasPermission("cdrbounty.contract.anonymous")) {
            player.sendMessage(ChatColor.RED + "Kamu tidak punya izin membuat anonymous contract.");
            return;
        }
        if (session.flags.contains(ContractFlag.ANONYMOUS)) session.flags.remove(ContractFlag.ANONYMOUS);
        else session.flags.add(ContractFlag.ANONYMOUS);
        openSetup(player);
    }

    private void toggleWeapon(Player player, Session session) {
        ContractCondition existing = first(session, ContractConditionType.REQUIRED_WEAPON);
        if (existing != null) {
            remove(session, ContractConditionType.REQUIRED_WEAPON);
            openConditions(player);
            return;
        }
        Material held = player.getInventory().getItemInMainHand().getType();
        if (held == Material.AIR) {
            player.sendMessage(ChatColor.RED + "Pegang item/weapon di main hand dulu.");
            return;
        }
        session.conditions.add(new ContractCondition(ContractConditionType.REQUIRED_WEAPON, held.name()));
        openConditions(player);
    }

    private void submit(Player player, Session session) {
        if (!submitting.add(player.getUniqueId())) return;
        UUID targetId = session.targetUuid;
        BigDecimal amount = session.amount;
        Set<ContractFlag> flags = Set.copyOf(session.flags);
        Set<UUID> allowed = Set.copyOf(session.allowedHunters);
        List<ContractCondition> conditions = List.copyOf(session.conditions);
        sessions.remove(player.getUniqueId());
        player.closeInventory();
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        contracts.create(player, target, amount, flags, allowed, conditions)
                .thenAccept(result -> MainThread.run(plugin, () -> {
                    submitting.remove(player.getUniqueId());
                    if (!player.isOnline()) return;
                    if (!result.success()) player.sendMessage(ChatColor.RED + "Bounty gagal dibuat: " + result.reason());
                    else player.sendMessage(ChatColor.GREEN + "Bounty berhasil dipasang pada " + safeName(target)
                            + " dengan reward " + economy.format(result.rewardAmount()) + ".");
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("NPC bounty wizard submit failed: " + root(ex));
                    MainThread.run(plugin, () -> { submitting.remove(player.getUniqueId()); player.sendMessage(ChatColor.RED + "Bounty gagal diproses."); });
                    return null;
                });
    }

    private void cancel(Player player, boolean close) {
        sessions.remove(player.getUniqueId());
        if (close) player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + "Draft bounty dibatalkan. Tidak ada saldo yang dipotong.");
    }

    private Session session(Player player, boolean notify) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return null;
        if (session.expiresAt > 0L && System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(player.getUniqueId(), session);
            if (notify) player.sendMessage(ChatColor.YELLOW + "Draft bounty kadaluarsa karena tidak ada aktivitas.");
            return null;
        }
        return session;
    }

    private void armTimeout(Player player, Session session) {
        long seconds = Math.max(10L, Math.min(300L, plugin.getConfig().getLong("npc.input-timeout-seconds", 60L)));
        long expires = System.currentTimeMillis() + seconds * 1000L;
        session.expiresAt = expires;
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session current = sessions.get(id);
            if (current != session || current.expiresAt != expires || !current.stage.chatInput()) return;
            if (System.currentTimeMillis() < expires || !sessions.remove(id, current)) return;
            Player online = Bukkit.getPlayer(id);
            if (online != null) online.sendMessage(ChatColor.YELLOW + "Draft bounty kadaluarsa karena tidak ada aktivitas.");
        }, seconds * 20L + 2L);
    }

    private ContractCondition first(Session session, ContractConditionType... types) {
        for (ContractCondition condition : session.conditions) {
            for (ContractConditionType type : types) if (condition.type() == type) return condition;
        }
        return null;
    }

    private void remove(Session session, ContractConditionType type) {
        session.conditions.removeIf(condition -> condition.type() == type);
    }

    private List<String> conditionLore(List<ContractCondition> conditions, boolean hint) {
        List<String> lore = new ArrayList<>();
        if (conditions.isEmpty()) lore.add(ChatColor.GRAY + "No special conditions.");
        else for (ContractCondition condition : conditions) lore.add(ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + condition.description());
        if (hint) lore.add(ChatColor.YELLOW + "Klik untuk edit.");
        return lore;
    }

    private ItemStack actionItem(Material material, String action, String name, List<String> lore) {
        ItemStack item = plainItem(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack plainItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Material worldIcon(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private boolean managedTitle(String title) {
        return title.equals(SETUP_TITLE) || title.equals(CONDITIONS_TITLE) || title.equals(WORLD_TITLE) || title.equals(CONFIRM_TITLE);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        submitting.remove(id);
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String duration(long seconds) {
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        if (days > 0) return days + "d " + hours + "h";
        return Math.max(1L, seconds / 3600L) + "h";
    }

    private static String stageLabel(Stage stage) {
        return switch (stage) {
            case TARGET -> "Target";
            case AMOUNT -> "Nominal";
            case PRIVATE_HUNTERS -> "Private Hunters";
            case OPTIONS -> "Options";
            case CONDITIONS -> "Conditions";
            case WORLD -> "World Rule";
            case CONFIRM -> "Confirmation";
        };
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum Stage {
        TARGET, AMOUNT, PRIVATE_HUNTERS, OPTIONS, CONDITIONS, WORLD, CONFIRM;
        boolean chatInput() { return this == TARGET || this == AMOUNT || this == PRIVATE_HUNTERS; }
    }

    private static final class Session {
        private volatile Stage stage = Stage.TARGET;
        private UUID targetUuid;
        private BigDecimal amount;
        private final EnumSet<ContractFlag> flags = EnumSet.of(ContractFlag.PUBLIC);
        private final Set<UUID> allowedHunters = new LinkedHashSet<>();
        private final List<ContractCondition> conditions = new ArrayList<>();
        private volatile long expiresAt;
    }
}
