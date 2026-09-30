package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.betonquest.betonquest.api.quest.action.PlayerActionFactory;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class QuestBountyCreateActionFactory implements PlayerActionFactory {
    private final QuestBountyService service;

    public QuestBountyCreateActionFactory(QuestBountyService service) {
        this.service = service;
    }

    @Override
    public PlayerAction parsePlayer(Instruction instruction) throws QuestException {
        Argument<String> questKey = instruction.string().get();
        Argument<String> target = instruction.string().get();
        Argument<String> amount = instruction.string().get();
        return new QuestBountyCreateAction(service, questKey, target, amount);
    }
}
