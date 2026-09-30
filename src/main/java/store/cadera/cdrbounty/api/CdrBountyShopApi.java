package store.cadera.cdrbounty.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Small synchronous API intended for shop/economy integrations.
 * Values are served from CdrBounty's refreshed in-memory wanted cache.
 */
public interface CdrBountyShopApi {
    boolean isWanted(UUID playerId);

    BigDecimal activeBountyTotal(UUID playerId);

    double shopBuyMultiplier(UUID playerId);
}
