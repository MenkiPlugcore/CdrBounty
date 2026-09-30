package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.instruction.Instruction;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.betonquest.betonquest.api.quest.action.PlayerActionFactory;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class QuestBountyCancelActionFactory implements PlayerActionFactory {
    private final QuestBountyService service;

    public QuestBountyCancelActionFactory(QuestBountyService service) {
        this.service = service;
    }

    @Override
    public PlayerAction parsePlayer(Instruction instruction) throws QuestException {
        Argument<String> questKey = instruction.string().get();
        return new QuestBountyCancelAction(service, questKey);
    }
}
