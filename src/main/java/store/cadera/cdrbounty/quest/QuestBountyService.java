package store.cadera.cdrbounty.quest;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.tracking.BountyTrackerService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class QuestBountyService {
    private final JavaPlugin plugin;
    private final QuestBountyRepository repository;
    private final BountyTrackerService trackers;
    private final Supplier<PluginSettings> settings;

    public QuestBountyService(JavaPlugin plugin, QuestBountyRepository repository,
                              BountyTrackerService trackers, Supplier<PluginSettings> settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.trackers = trackers;
        this.settings = settings;
    }

    public QuestBountyRepository.CreateResult create(Player hunter, String rawQuestKey,
                                                       String rawTarget, BigDecimal amount) {
        String questKey = normalizeKey(rawQuestKey);
        UUID targetUuid = resolveTarget(rawTarget);
        if (hunter.getUniqueId().equals(targetUuid)) {
            throw new IllegalArgumentException("Quest bounty target cannot be the hunter.");
        }
        QuestBountyRepository.CreateResult result = join(repository.createOrReuse(
                hunter.getUniqueId(), questKey, targetUuid, amount, Instant.now(), settings.get().durationSeconds()));
        if (result.contractId() != null) trackers.issue(hunter, result.contractId());
        return result;
    }

    public QuestBountyRepository.CancelResult cancel(Player hunter, String rawQuestKey) {
        String questKey = normalizeKey(rawQuestKey);
        QuestBountyRepository.StatusResult before = status(hunter.getUniqueId(), questKey);
        QuestBountyRepository.CancelResult result = join(repository.cancel(hunter.getUniqueId(), questKey, Instant.now()));
        if (result.success() && before.contractId() != null) trackers.removeContract(hunter, before.contractId());
        return result;
    }

    public QuestBountyRepository.StatusResult status(UUID playerId, String rawQuestKey) {
        return join(repository.status(playerId, normalizeKey(rawQuestKey)));
    }

    public boolean has(UUID playerId, String questKey) {
        return status(playerId, questKey).status() != QuestBountyRepository.QuestStatus.NONE;
    }

    public boolean active(UUID playerId, String questKey) {
        return status(playerId, questKey).status() == QuestBountyRepository.QuestStatus.ACTIVE;
    }

    public boolean completed(UUID playerId, String questKey) {
        return status(playerId, questKey).status() == QuestBountyRepository.QuestStatus.COMPLETED;
    }

    private UUID resolveTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.isBlank()) throw new IllegalArgumentException("Target is required.");
        String target = rawTarget.trim();
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
        }

        Player online = Bukkit.getPlayerExact(target);
        if (online != null) return online.getUniqueId();
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name != null && name.equalsIgnoreCase(target)) return offline.getUniqueId();
        }
        throw new IllegalArgumentException("Unknown bounty target '" + target + "'. Player must have joined this server before.");
    }

    private static String normalizeKey(String value) {
        if (value == null) throw new IllegalArgumentException("Quest key is required.");
        String key = value.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Quest key is required.");
        if (key.length() > 128) throw new IllegalArgumentException("Quest key cannot exceed 128 characters.");
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) throw new IllegalArgumentException("Quest key contains control characters.");
        }
        return key;
    }

    private static <T> T join(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }
}
