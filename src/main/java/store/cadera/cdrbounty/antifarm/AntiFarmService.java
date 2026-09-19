package store.cadera.cdrbounty.antifarm;

import org.bukkit.entity.Player;
import store.cadera.cdrbounty.config.PluginSettings;
import store.cadera.cdrbounty.storage.BountyMaintenanceRepository;
import store.cadera.cdrbounty.storage.BountyRepository;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class AntiFarmService {
    private final BountyRepository repository;
    private final BountyMaintenanceRepository maintenance;
    private final Supplier<PluginSettings> settings;

    public AntiFarmService(BountyRepository repository, BountyMaintenanceRepository maintenance,
                           Supplier<PluginSettings> settings) {
        this.repository = repository;
        this.maintenance = maintenance;
        this.settings = settings;
    }

    public CompletableFuture<Decision> evaluate(Player killer, Player victim, Instant now) {
        Objects.requireNonNull(killer, "killer");
        Objects.requireNonNull(victim, "victim");

        if (killer.getUniqueId().equals(victim.getUniqueId())) {
            return CompletableFuture.completedFuture(Decision.block("SELF_KILL"));
        }
        if (killer.hasPermission("cdrbounty.bypass.antifarm")) {
            return CompletableFuture.completedFuture(Decision.allow());
        }

        PluginSettings cfg = settings.get();
        boolean sameIp = sameAddress(killer, victim);
        if (sameIp && cfg.sameIpPolicy() == PluginSettings.SameIpPolicy.BLOCK) {
            return CompletableFuture.completedFuture(Decision.block("SAME_IP"));
        }

        CompletableFuture<BountyRepository.PairHistory> pairFuture =
                repository.pairHistory(killer.getUniqueId(), victim.getUniqueId());
        CompletableFuture<Instant> targetFuture = maintenance.claimHistory(victim.getUniqueId(), 20)
                .thenApply(history -> history.stream()
                        .filter(item -> "PAID".equals(item.status()) && item.paidAt() != null)
                        .map(BountyMaintenanceRepository.ClaimHistory::paidAt)
                        .findFirst()
                        .orElse(null));

        return pairFuture.thenCombine(targetFuture, (pair, lastTargetClaim) -> {
            if (pair.lastClaimAt() != null && cfg.killerVictimCooldownSeconds() > 0
                    && now.isBefore(pair.lastClaimAt().plusSeconds(cfg.killerVictimCooldownSeconds()))) {
                return Decision.block("PAIR_COOLDOWN");
            }

            if (pair.windowStart() != null
                    && now.isBefore(pair.windowStart().plusSeconds(cfg.repeatedPairWindowSeconds()))
                    && pair.windowClaims() >= cfg.repeatedPairMaxClaims()) {
                return Decision.block("REPEATED_PAIR_LIMIT");
            }

            if (lastTargetClaim != null && cfg.minimumSurvivalAfterClaimSeconds() > 0
                    && now.isBefore(lastTargetClaim.plusSeconds(cfg.minimumSurvivalAfterClaimSeconds()))) {
                return Decision.block("TARGET_SURVIVAL_COOLDOWN");
            }

            return Decision.allow();
        });
    }

    private static boolean sameAddress(Player a, Player b) {
        if (a.getAddress() == null || b.getAddress() == null) return false;
        InetAddress first = a.getAddress().getAddress();
        InetAddress second = b.getAddress().getAddress();
        return first != null && first.equals(second);
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "OK");
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }
}
