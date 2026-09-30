package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.condition.PlayerCondition;
import org.betonquest.betonquest.api.quest.condition.PlayerConditionFactory;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class QuestBountyStateConditionFactory implements PlayerConditionFactory {
    private final QuestBountyService service;
    private final QuestBountyStateCondition.Mode mode;

    public QuestBountyStateConditionFactory(QuestBountyService service, QuestBountyStateCondition.Mode mode) {
        this.service = service;
        this.mode = mode;
    }

    @Override
    public PlayerCondition parsePlayer(Instruction instruction) throws QuestException {
        Argument<String> questKey = instruction.string().get();
        return new QuestBountyStateCondition(service, mode, questKey);
    }
}
