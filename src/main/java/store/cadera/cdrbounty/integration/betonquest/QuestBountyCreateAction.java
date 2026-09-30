package store.cadera.cdrbounty.integration.betonquest;

import org.betonquest.betonquest.api.QuestException;
import org.betonquest.betonquest.api.instruction.Argument;
import org.betonquest.betonquest.api.profile.Profile;
import org.betonquest.betonquest.api.quest.action.PlayerAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import store.cadera.cdrbounty.quest.QuestBountyService;

import java.math.BigDecimal;

public final class QuestBountyCreateAction implements PlayerAction {
    private final QuestBountyService service;
    private final Argument<String> questKey;
    private final Argument<String> target;
    private final Argument<String> amount;

    public QuestBountyCreateAction(QuestBountyService service, Argument<String> questKey,
                                   Argument<String> target, Argument<String> amount) {
        this.service = service;
        this.questKey = questKey;
        this.target = target;
        this.amount = amount;
    }

    @Override
    public void execute(Profile profile) throws QuestException {
        Player player = Bukkit.getPlayer(profile.getPlayerUUID());
        if (player == null) throw new QuestException("CdrBounty quest action requires an online player.");
        String key = questKey.getValue(profile);
        String targetValue = target.getValue(profile);
        String amountValue = amount.getValue(profile);
        try {
            BigDecimal reward = new BigDecimal(amountValue);
            service.create(player, key, targetValue, reward);
        } catch (RuntimeException ex) {
            throw new QuestException("Could not create quest bounty '" + key + "': " + root(ex));
        }
    }

    @Override public boolean isPrimaryThreadEnforced() { return true; }

    private static String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
