package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.profile.Profile;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import store.cadera.cdrbounty.quest.QuestBountyRepository;
import store.cadera.cdrbounty.quest.QuestBountyService;

public final class QuestBountyCancelAction implements PlayerAction {
    private final QuestBountyService service;
    private final Argument<String> questKey;

    public QuestBountyCancelAction(QuestBountyService service, Argument<String> questKey) {
        this.service = service;
        this.questKey = questKey;
    }

    @Override
    public void execute(Profile profile) throws QuestException {
        Player player = Bukkit.getPlayer(profile.getPlayerUUID());
        if (player == null) throw new QuestException("CdrBounty quest action requires an online player.");
        String key = questKey.getValue(profile);
        try {
            QuestBountyRepository.CancelResult result = service.cancel(player, key);
            if (!result.success() && !"NOT_FOUND".equals(result.reason()) && !"NOT_ACTIVE".equals(result.reason())) {
                throw new QuestException("Could not cancel quest bounty '" + key + "': " + result.reason());
            }
        } catch (QuestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new QuestException("Could not cancel quest bounty '" + key + "': " + root(ex));
        }
    }

    @Override public boolean isPrimaryThreadEnforced() { return true; }

    private static String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
