package store.cadera.cdrbounty.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import store.cadera.cdrbounty.approval.ApprovalAdminGui;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.diagnostic.ProductionDiagnosticsService;

public final class RootAdminCommand implements CommandExecutor {
    private final CommandExecutor delegate;
    private final ApprovalAdminGui approvalGui;
    private final ProductionDiagnosticsService diagnostics;

    public RootAdminCommand(CommandExecutor delegate, ApprovalAdminGui approvalGui,
                            ProductionDiagnosticsService diagnostics) {
        this.delegate = delegate;
        this.approvalGui = approvalGui;
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("approval") || args[0].equalsIgnoreCase("approve"))) {
            if (!sender.hasPermission("cdrbounty.admin.approval")) {
                sender.sendMessage("§cKamu tidak punya izin membuka approval bounty.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cApproval GUI hanya dapat dibuka oleh player/admin in-game.");
                return true;
            }
            approvalGui.open(player);
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("diagnose") || args[0].equalsIgnoreCase("health"))) {
            if (!sender.hasPermission("cdrbounty.admin.debug")) {
                sender.sendMessage("§cKamu tidak punya izin menjalankan diagnostics.");
                return true;
            }
            sender.sendMessage("§6CdrBounty Diagnostics §7— menjalankan pemeriksaan produksi...");
            diagnostics.run().thenAccept(report -> MainThread.run(
                    org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(RootAdminCommand.class), () -> {
                        sender.sendMessage("§7SQLite integrity: " + color(report.integrity().equalsIgnoreCase("ok")) + report.integrity());
                        sender.sendMessage("§7Orphan contracts/hunters/conditions/quest: §f"
                                + report.orphanContracts() + "/" + report.orphanHunters() + "/"
                                + report.orphanConditions() + "/" + report.orphanQuestLinks());
                        sender.sendMessage("§7Unresolved economy operations: §f" + report.unresolvedEconomyOperations());
                        sender.sendMessage("§7NPC: §f" + report.npc());
                        sender.sendMessage("§7Integrations: §fCitizens=" + report.citizens()
                                + " Vault=" + report.vault()
                                + " Reputation=" + report.reputation()
                                + " BetonQuest=" + report.betonQuest()
                                + " QuestJournal=" + report.questJournal()
                                + " Floodgate=" + report.floodgate());
                        if (!report.notes().isEmpty()) {
                            report.notes().forEach(note -> sender.sendMessage("§e• " + note));
                        }
                        sender.sendMessage(report.healthy()
                                ? "§aProduction health: OK"
                                : "§eProduction health: PERLU DICEK — lihat nilai di atas.");
                    })).exceptionally(error -> {
                        MainThread.run(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(RootAdminCommand.class),
                                () -> sender.sendMessage("§cDiagnostics gagal: " + root(error)));
                        return null;
                    });
            return true;
        }

        boolean handled = delegate.onCommand(sender, command, label, args);
        if (args.length == 0) {
            if (sender.hasPermission("cdrbounty.admin.approval")) {
                sender.sendMessage("§e/cdrbounty approval §7- buka pending bounty approval GUI");
            }
            if (sender.hasPermission("cdrbounty.admin.debug")) {
                sender.sendMessage("§e/cdrbounty diagnose §7- cek SQLite, orphan data, economy recovery, NPC, dan integrasi");
            }
        }
        return handled;
    }

    private static String color(boolean ok) {
        return ok ? "§a" : "§e";
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
