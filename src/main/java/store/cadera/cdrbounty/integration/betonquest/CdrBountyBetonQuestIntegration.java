package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.integration.Integration;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class CdrBountyBetonQuestIntegration implements Integration {
    private final QuestBountyService service;

    public CdrBountyBetonQuestIntegration(QuestBountyService service) {
        this.service = service;
    }

    @Override
    public void enable(BetonQuestApi api) throws QuestException {
        api.actions().registry().register("cdrbounty_create", new QuestBountyCreateActionFactory(service));
        api.actions().registry().register("cdrbounty_cancel", new QuestBountyCancelActionFactory(service));

        api.conditions().registry().register("cdrbounty_has", new QuestBountyStateConditionFactory(service, QuestBountyStateCondition.Mode.HAS));
        api.conditions().registry().register("cdrbounty_active", new QuestBountyStateConditionFactory(service, QuestBountyStateCondition.Mode.ACTIVE));
        api.conditions().registry().register("cdrbounty_completed", new QuestBountyStateConditionFactory(service, QuestBountyStateCondition.Mode.COMPLETED));
    }

    @Override public void postEnable(BetonQuestApi api) throws QuestException {}
    @Override public void disable() throws QuestException {}
}
