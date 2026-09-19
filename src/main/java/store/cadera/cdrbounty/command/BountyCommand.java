package store.cadera.cdrbounty.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import store.cadera.cdrbounty.bounty.BountyPlacementService;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class BountyCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final BountyRepository repository;
    private final BountyPlacementService placementService;
    private final MessageService messages;
    private final VaultEconomyAdapter economy;

    public BountyCommand(JavaPlugin plugin, BountyRepository repository, BountyPlacementService placementService,
                         MessageService messages, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.repository = repository;
        this.placementService = placementService;
        this.messages = messages;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6CdrBounty beta.1 §7— /bounty add <player> <amount>, /bounty view <player>, /bounty list");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "add" -> add(sender, args);
            case "view" -> view(sender, args);
            case "list" -> list(sender);
            default -> {
                sender.sendMessage("§cUsage: /bounty <add|view|list>");
                yield true;
            }
        };
    }

    private boolean add(CommandSender sender, String[] args) {
        if (!(sender instanceof Player issuer)) {
            sender.sendMessage(messages.text("player-only"));
            return true;
        }
        if (!sender.hasPermission("cdrbounty.add")) {
            sender.sendMessage(messages.text("no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /bounty add <player> <amount>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal amount;
        try {
            amount = new BigDecimal(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(messages.text("invalid-amount"));
            return true;
        }

        placementService.place(issuer, target, amount).thenAccept(result -> MainThread.run(plugin, () -> {
            if (result.success()) {
                String targetName = target.getName() == null ? target.getUniqueId().toString() : target.getName();
                issuer.sendMessage(messages.text("placement-success", Map.of(
                        "amount", economy.format(result.rewardAmount()),
                        "target", targetName
                )));
            } else {
                issuer.sendMessage(messages.text("placement-failed", Map.of("reason", result.reason())));
            }
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Bounty placement failed: " + ex.getMessage());
            MainThread.run(plugin, () -> issuer.sendMessage(messages.text("internal-error")));
            return null;
        });
        return true;
    }

    private boolean view(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.view")) {
            sender.sendMessage(messages.text("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /bounty view <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        repository.activeTotal(target.getUniqueId(), Instant.now()).thenAccept(total -> MainThread.run(plugin, () -> {
            String name = target.getName() == null ? args[1] : target.getName();
            if (total.signum() <= 0) {
                sender.sendMessage(messages.text("no-active-bounty", Map.of("target", name)));
            } else {
                sender.sendMessage(messages.text("view-bounty", Map.of(
                        "target", name,
                        "amount", economy.format(total)
                )));
            }
        }));
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!sender.hasPermission("cdrbounty.list")) {
            sender.sendMessage(messages.text("no-permission"));
            return true;
        }
        repository.listActiveTotals(Instant.now(), 10).thenAccept(entries -> MainThread.run(plugin, () -> {
            sender.sendMessage("§6§lCdrBounty §8— §eTop Active Bounties");
            if (entries.isEmpty()) {
                sender.sendMessage("§7Belum ada bounty aktif.");
                return;
            }
            int index = 1;
            for (BountyRepository.TargetTotal entry : entries) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(entry.targetUuid());
                String name = target.getName() == null ? entry.targetUuid().toString() : target.getName();
                sender.sendMessage("§e" + index++ + ". §f" + name + " §8— §6" + economy.format(entry.amount()));
            }
        }));
        return true;
    }
}
