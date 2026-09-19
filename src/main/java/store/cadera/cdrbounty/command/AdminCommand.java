package store.cadera.cdrbounty.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import store.cadera.cdrbounty.CdrBountyPlugin;
import store.cadera.cdrbounty.bounty.BountyRefundService;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.MoneyMath;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AdminCommand implements CommandExecutor {
    private final CdrBountyPlugin plugin;
    private final BountyRepository repository;
    private final BountyMaintenanceRepository maintenance;
    private final BountyRefundService refunds;
    private final MessageService messages;
    private final VaultEconomyAdapter economy;

    public AdminCommand(CdrBountyPlugin plugin, BountyRepository repository,
                        BountyMaintenanceRepository maintenance, BountyRefundService refunds,
                        MessageService messages, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.repository = repository;
        this.maintenance = maintenance;
        this.refunds = refunds;
        this.messages = messages;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cdrbounty.admin")) {
            sender.sendMessage(messages.text("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6CdrBounty Admin §7— reload, add, remove, inspect, history, debug");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> reload(sender);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "inspect" -> inspect(sender, args);
            case "history" -> history(sender, args);
            case "debug" -> debug(sender);
            default -> {
                sender.sendMessage("§cUsage: /cdrbounty <reload|add|remove|inspect|history|debug>");
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("cdrbounty.admin.reload")) return denied(sender);
        try {
            plugin.reloadRuntimeConfiguration();
            sender.sendMessage(messages.text("reload-success"));
        } catch (Exception ex) {
            sender.sendMessage("§cReload ditolak: " + ex.getMessage());
        }
        return true;
    }

    private boolean add(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.admin.modify")) return denied(sender);
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /cdrbounty add <player> <amount>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(messages.text("player-not-found"));
            return true;
        }

        BigDecimal amount;
        try {
            amount = MoneyMath.normalize(new BigDecimal(args[2]), plugin.settings().decimalScale());
        } catch (RuntimeException ex) {
            sender.sendMessage(messages.text("invalid-amount"));
            return true;
        }
        if (amount.compareTo(plugin.settings().minimumBounty()) < 0
                || amount.compareTo(plugin.settings().maximumBounty()) > 0) {
            sender.sendMessage(messages.text("invalid-amount"));
            return true;
        }

        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        Instant now = Instant.now();
        BigDecimal finalAmount = amount;
        repository.activeTotal(target.getUniqueId(), now)
                .thenCompose(active -> {
                    if (active.add(finalAmount).compareTo(plugin.settings().maximumActivePerTarget()) > 0) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("TARGET_MAXIMUM_EXCEEDED"));
                    }
                    return maintenance.adminAdd(target.getUniqueId(), actor, finalAmount, now,
                            now.plusSeconds(plugin.settings().durationSeconds()));
                })
                .thenAccept(id -> MainThread.run(plugin, () -> sender.sendMessage(
                        "§aAdmin bounty ditambahkan: §e" + safeName(target) + " §8— §6" + economy.format(finalAmount)
                                + " §8(" + id + ")")))
                .exceptionally(ex -> {
                    MainThread.run(plugin, () -> sender.sendMessage("§cAdmin add gagal: " + rootMessage(ex)));
                    return null;
                });
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.admin.modify")) return denied(sender);
        if (args.length < 3 || !args[2].equalsIgnoreCase("all")) {
            sender.sendMessage("§cUsage beta.1: /cdrbounty remove <player> all");
            sender.sendMessage("§7Partial amount removal disimpan untuk patch beta.1 berikutnya agar ownership/refund tidak rusak.");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        refunds.cancelAll(actor, target.getUniqueId())
                .thenCompose(total -> repository.audit(
                                actor,
                                "ADMIN_CANCEL_ALL",
                                target.getUniqueId(),
                                total,
                                "Cancelled all active bounty contributions; refund policy=" + plugin.settings().cancelRefundPolicy(),
                                Instant.now())
                        .thenApply(ignored -> total))
                .thenAccept(total -> MainThread.run(plugin, () -> sender.sendMessage(
                        "§aBounty aktif §e" + safeName(target) + "§a dibatalkan. Escrow diproses: §6" + economy.format(total))))
                .exceptionally(ex -> {
                    MainThread.run(plugin, () -> sender.sendMessage("§cCancel gagal/recovery diperlukan: " + rootMessage(ex)));
                    return null;
                });
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.admin.inspect")) return denied(sender);
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /cdrbounty inspect <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        Instant now = Instant.now();
        repository.activeTotal(target.getUniqueId(), now)
                .thenCombine(maintenance.activeContributions(target.getUniqueId()), (total, entries) -> new Inspection(total, entries.size()))
                .thenAccept(data -> MainThread.run(plugin, () -> {
                    sender.sendMessage("§6CdrBounty Inspect §8— §f" + safeName(target));
                    sender.sendMessage("§7Active total: §6" + economy.format(data.total()));
                    sender.sendMessage("§7Active contributions: §e" + data.contributions());
                    sender.sendMessage("§7UUID: §f" + target.getUniqueId());
                }));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cdrbounty.admin.inspect")) return denied(sender);
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /cdrbounty history <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        maintenance.claimHistory(target.getUniqueId(), 10).thenAccept(history -> MainThread.run(plugin, () -> {
            sender.sendMessage("§6CdrBounty History §8— §f" + safeName(target));
            if (history.isEmpty()) {
                sender.sendMessage("§7Belum ada claim history.");
                return;
            }
            for (BountyMaintenanceRepository.ClaimHistory item : history) {
                OfflinePlayer killer = Bukkit.getOfflinePlayer(item.killerUuid());
                sender.sendMessage("§8• §e" + safeName(killer) + " §7→ §6" + economy.format(item.amount())
                        + " §8[" + item.status() + "] §7" + item.world());
            }
        }));
        return true;
    }

    private boolean debug(CommandSender sender) {
        if (!sender.hasPermission("cdrbounty.admin.debug")) return denied(sender);
        repository.unresolvedEconomyOperations().thenAccept(operations -> MainThread.run(plugin, () -> {
            sender.sendMessage("§6CdrBounty Debug");
            sender.sendMessage("§7Version: §f" + plugin.getDescription().getVersion());
            sender.sendMessage("§7SQLite: §f" + plugin.settings().sqliteFile());
            sender.sendMessage("§7Unresolved economy operations: §e" + operations.size());
            sender.sendMessage("§7Debug mode: §f" + plugin.settings().debug());
        }));
        return true;
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage(messages.text("no-permission"));
        return true;
    }

    private static String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Inspection(BigDecimal total, int contributions) {}
}
