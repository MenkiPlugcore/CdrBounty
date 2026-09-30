package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.profile.Profile;
import org.betonquest.betonquest.api.quest.condition.PlayerCondition;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class QuestBountyStateCondition implements PlayerCondition {
    public enum Mode { HAS, ACTIVE, COMPLETED }

    private final QuestBountyService service;
    private final Mode mode;
    private final Argument<String> questKey;

    public QuestBountyStateCondition(QuestBountyService service, Mode mode, Argument<String> questKey) {
        this.service = service;
        this.mode = mode;
        this.questKey = questKey;
    }

    @Override
    public boolean check(Profile profile) throws QuestException {
        String key = questKey.getValue(profile);
        try {
            return switch (mode) {
                case HAS -> service.has(profile.getPlayerUUID(), key);
                case ACTIVE -> service.active(profile.getPlayerUUID(), key);
                case COMPLETED -> service.completed(profile.getPlayerUUID(), key);
            };
        } catch (RuntimeException ex) {
            throw new QuestException("Could not evaluate bounty quest condition '" + key + "': " + root(ex));
        }
    }

    @Override public boolean isPrimaryThreadEnforced() { return true; }

    private static String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
