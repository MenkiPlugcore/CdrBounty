package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.integration.IntegrationService;
import store.cadera.cdrbounty.CdrBountyPlugin;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class BetonQuestBootstrap {
    private BetonQuestBootstrap() {}

    public static boolean register(CdrBountyPlugin plugin, QuestBountyService service) {
        IntegrationService integrationService = plugin.getServer().getServicesManager().load(IntegrationService.class);
        if (integrationService == null) {
            plugin.getLogger().warning("BetonQuest detected but IntegrationService is not available. Quest bounty hooks skipped.");
            return false;
        }
        integrationService.withPolicies().register(plugin,
                () -> new CdrBountyBetonQuestIntegration(service));
        return true;
    }
}
