package store.cadera.cdrbounty.claim;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.MessageService;
import store.cadera.cdrbounty.core.MainThread;
import store.cadera.cdrbounty.economy.VaultEconomyAdapter;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.time.Instant;
import java.util.Map;

public final class BountyListener implements Listener {
    private final JavaPlugin plugin;
    private final BountyRepository repository;
    private final BountyClaimService claimService;
    private final MessageService messages;
    private final VaultEconomyAdapter economy;

    public BountyListener(JavaPlugin plugin, BountyRepository repository, BountyClaimService claimService,
                          MessageService messages, VaultEconomyAdapter economy) {
        this.plugin = plugin;
        this.repository = repository;
        this.claimService = claimService;
        this.messages = messages;
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        repository.upsertPlayer(player.getUniqueId(), player.getName(), Instant.now())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to update player identity cache: " + ex.getMessage());
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        claimService.claim(victim, killer).thenAccept(result -> MainThread.run(plugin, () -> {
            switch (result.status()) {
                case PAID -> killer.sendMessage(messages.text("claim-success", Map.of(
                        "amount", economy.format(result.amount()),
                        "target", victim.getName()
                )));
                case BLOCKED -> killer.sendMessage(messages.text("claim-blocked", Map.of("reason", result.reason())));
                case FAILED -> {
                    plugin.getLogger().warning("Bounty claim failed for " + killer.getName() + " -> " + victim.getName() + ": " + result.reason());
                    killer.sendMessage(messages.text("internal-error"));
                }
                case NO_BOUNTY -> { }
            }
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Unhandled bounty death processing error: " + ex.getMessage());
            return null;
        });
    }
}
