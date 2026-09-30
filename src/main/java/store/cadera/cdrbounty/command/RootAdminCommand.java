package store.cadera.cdrbounty.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import store.cadera.cdrbounty.approval.ApprovalAdminGui;

public final class RootAdminCommand implements CommandExecutor {
    private final CommandExecutor delegate;
    private final ApprovalAdminGui approvalGui;

    public RootAdminCommand(CommandExecutor delegate, ApprovalAdminGui approvalGui) {
        this.delegate = delegate;
        this.approvalGui = approvalGui;
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
        boolean handled = delegate.onCommand(sender, command, label, args);
        if (args.length == 0 && sender.hasPermission("cdrbounty.admin.approval")) {
            sender.sendMessage("§e/cdrbounty approval §7- buka pending bounty approval GUI");
        }
        return handled;
    }
}
