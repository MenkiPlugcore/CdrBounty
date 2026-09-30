package store.cadera.cdrbounty.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.contract.ContractCondition;
import store.cadera.cdrbounty.contract.ContractConditionType;
import store.cadera.cdrbounty.contract.ContractFlag;
import store.cadera.cdrbounty.contract.ContractGuiService;
import store.cadera.cdrbounty.contract.ContractRepository;
import store.cadera.cdrbounty.contract.ContractService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BountyCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final ContractService contracts;
    private final BountyPlacementService placementService;
    private final ContractGuiService gui;
    private final MessageService messages;
    private final VaultEconomyAdapter economy;

    public BountyCommand(JavaPlugin plugin, ContractService contracts, BountyPlacementService placementService,
                         ContractGuiService gui, MessageService messages, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.contracts = contracts;
        this.placementService = placementService;
        this.gui = gui;
        this.messages = messages;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6CdrBounty beta.2 §7— add, view, list, hunt, contracts, create, accept, abandon");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "add" -> add(sender, args);
            case "view" -> view(sender, args);
            case "list" -> list(sender);
            case "hunt" -> hunt(sender);
            case "contracts" -> contracts(sender);
            case "create" -> create(sender, args);
            case "accept" -> accept(sender, args);
            case "abandon" -> abandon(sender, args);
            default -> {
                sender.sendMessage("§cUsage: /bounty <add|view|list|hunt|contracts|create|accept|abandon>");
                yield true;
            }
        };
    }

    private boolean add(CommandSender sender, String[] args) {
        if (!(sender instanceof Player issuer)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.add")) return denied(sender);
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /bounty add <player> <amount>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal amount = amount(sender, args[2]);
        if (amount == null) return true;
        placementService.place(issuer, target, amount).thenAccept(result -> MainThread.run(plugin, () -> {
            if (result.success()) {
                issuer.sendMessage(messages.text("placement-success", Map.of(
                        "amount", economy.format(result.rewardAmount()), "target", safeName(target))));
            } else issuer.sendMessage(messages.text("placement-failed", Map.of("reason", result.reason())));
        })).exceptionally(ex -> internal(issuer, "Bounty placement", ex));
        return true;
    }

    private boolean view(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.view")) return denied(sender);
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /bounty view <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID viewer = sender instanceof Player p ? p.getUniqueId() : null;
        boolean admin = sender.hasPermission("cdrbounty.admin.contracts");
        contracts.visibleTotal(target.getUniqueId(), viewer, admin).thenAccept(total -> MainThread.run(plugin, () -> {
            String name = safeName(target);
            if (total.signum() <= 0) sender.sendMessage(messages.text("no-active-bounty", Map.of("target", name)));
            else sender.sendMessage(messages.text("view-bounty", Map.of("target", name, "amount", economy.format(total))));
        })).exceptionally(ex -> internal(sender, "Bounty view", ex));
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!sender.hasPermission("cdrbounty.list")) return denied(sender);
        UUID viewer = sender instanceof Player p ? p.getUniqueId() : null;
        boolean admin = sender.hasPermission("cdrbounty.admin.contracts");
        contracts.visibleTotals(viewer, admin, 10).thenAccept(entries -> MainThread.run(plugin, () -> {
            sender.sendMessage("§6§lCdrBounty §8— §eVisible Active Bounties");
            if (entries.isEmpty()) {
                sender.sendMessage("§7Belum ada bounty yang dapat kamu lihat.");
                return;
            }
            int index = 1;
            for (ContractRepository.VisibleTargetTotal entry : entries) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(entry.targetUuid());
                sender.sendMessage("§e" + index++ + ". §f" + safeName(target) + " §8— §6" + economy.format(entry.amount()));
            }
        })).exceptionally(ex -> internal(sender, "Bounty list", ex));
        return true;
    }

    private boolean hunt(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.contract.accept")) return denied(sender);
        gui.open(player);
        return true;
    }

    private boolean contracts(CommandSender sender) {
        UUID viewer = sender instanceof Player p ? p.getUniqueId() : null;
        boolean admin = sender.hasPermission("cdrbounty.admin.contracts");
        contracts.browse(viewer, admin, 10).thenAccept(entries -> MainThread.run(plugin, () -> {
            sender.sendMessage("§6§lCdrBounty Contracts");
            if (entries.isEmpty()) {
                sender.sendMessage("§7Tidak ada contract yang dapat kamu lihat.");
                return;
            }
            for (ContractRepository.ContractView view : entries) {
                var c = view.contract();
                OfflinePlayer target = Bukkit.getOfflinePlayer(c.targetUuid());
                sender.sendMessage("§8• §f" + safeName(target) + " §8— §6" + economy.format(c.rewardAmount())
                        + " §7[" + ContractFlag.serialize(c.flags()) + "] §8" + c.id());
            }
        })).exceptionally(ex -> internal(sender, "Contract list", ex));
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player issuer)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.contract.create")) return denied(sender);
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /bounty create <player> <amount> [public|private:p1,p2] [anonymous] [exclusive] [world:name] [forbidworld:name] [weapon:MATERIAL]");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal amount = amount(sender, args[2]);
        if (amount == null) return true;

        EnumSet<ContractFlag> flags = EnumSet.of(ContractFlag.PUBLIC);
        Set<UUID> allowed = new LinkedHashSet<>();
        List<ContractCondition> conditions = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            String token = args[i];
            String lower = token.toLowerCase();
            try {
                if (lower.equals("public")) {
                    flags.remove(ContractFlag.PRIVATE);
                    flags.add(ContractFlag.PUBLIC);
                } else if (lower.startsWith("private:")) {
                    flags.remove(ContractFlag.PUBLIC);
                    flags.add(ContractFlag.PRIVATE);
                    String rawNames = token.substring(token.indexOf(':') + 1);
                    for (String name : rawNames.split(",")) {
                        if (name.isBlank()) continue;
                        OfflinePlayer hunter = Bukkit.getOfflinePlayer(name.trim());
                        if (!hunter.isOnline() && !hunter.hasPlayedBefore()) {
                            sender.sendMessage("§cPrivate hunter tidak ditemukan: §f" + name.trim());
                            return true;
                        }
                        allowed.add(hunter.getUniqueId());
                    }
                } else if (lower.equals("anonymous")) {
                    flags.add(ContractFlag.ANONYMOUS);
                } else if (lower.equals("exclusive")) {
                    flags.add(ContractFlag.EXCLUSIVE);
                } else if (lower.startsWith("world:")) {
                    conditions.add(new ContractCondition(ContractConditionType.REQUIRED_WORLD, token.substring(token.indexOf(':') + 1)));
                } else if (lower.startsWith("forbidworld:")) {
                    conditions.add(new ContractCondition(ContractConditionType.FORBIDDEN_WORLD, token.substring(token.indexOf(':') + 1)));
                } else if (lower.startsWith("weapon:")) {
                    String raw = token.substring(token.indexOf(':') + 1);
                    Material material = Material.matchMaterial(raw);
                    if (material == null) {
                        sender.sendMessage("§cMaterial tidak valid: §f" + raw);
                        return true;
                    }
                    conditions.add(new ContractCondition(ContractConditionType.REQUIRED_WEAPON, material.name()));
                } else {
                    sender.sendMessage("§cOpsi contract tidak dikenal: §f" + token);
                    return true;
                }
            } catch (IllegalArgumentException ex) {
                sender.sendMessage("§cOpsi contract tidak valid: §f" + token);
                return true;
            }
        }

        contracts.create(issuer, target, amount, flags, allowed, conditions)
                .thenAccept(result -> MainThread.run(plugin, () -> {
                    if (!result.success()) {
                        issuer.sendMessage("§cContract gagal dibuat: §7" + result.reason());
                        return;
                    }
                    issuer.sendMessage("§aContract dibuka untuk §f" + safeName(target)
                            + " §8— §6" + economy.format(result.rewardAmount()));
                    issuer.sendMessage("§7Contract ID: §f" + result.contractId());
                    if (!"OK".equals(result.reason())) issuer.sendMessage("§eStatus: " + result.reason());
                })).exceptionally(ex -> internal(issuer, "Contract create", ex));
        return true;
    }

    private boolean accept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.contract.accept")) return denied(sender);
        UUID id = contractId(sender, args, "accept");
        if (id == null) return true;
        contracts.accept(player, id).thenAccept(result -> MainThread.run(plugin, () ->
                player.sendMessage(result.success() ? "§aContract diterima." : "§cContract ditolak: §7" + result.reason())))
                .exceptionally(ex -> internal(player, "Contract accept", ex));
        return true;
    }

    private boolean abandon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.contract.abandon")) return denied(sender);
        UUID id = contractId(sender, args, "abandon");
        if (id == null) return true;
        contracts.abandon(player, id).thenAccept(result -> MainThread.run(plugin, () ->
                player.sendMessage(result.success() ? "§eContract ditinggalkan." : "§cTidak dapat abandon: §7" + result.reason())))
                .exceptionally(ex -> internal(player, "Contract abandon", ex));
        return true;
    }

    private UUID contractId(CommandSender sender, String[] args, String action) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /bounty " + action + " <contractUuid>");
            return null;
        }
        try { return UUID.fromString(args[1]); }
        catch (IllegalArgumentException ex) {
            sender.sendMessage("§cContract UUID tidak valid.");
            return null;
        }
    }

    private BigDecimal amount(CommandSender sender, String raw) {
        try { return new BigDecimal(raw); }
        catch (NumberFormatException ex) {
            sender.sendMessage(messages.text("invalid-amount"));
            return null;
        }
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage(messages.text("no-permission"));
        return true;
    }

    private Void internal(CommandSender sender, String action, Throwable ex) {
        plugin.getLogger().warning(action + " failed: " + rootMessage(ex));
        MainThread.run(plugin, () -> sender.sendMessage(messages.text("internal-error")));
        return null;
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
