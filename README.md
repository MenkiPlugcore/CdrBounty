# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC. Player bounty requests require administrator approval, hunters receive an intentionally inaccurate compass tracker, poor CdrReputation can create system bounties, BetonQuest can create private quest-specific hunts, and active bounties can now apply economic consequences through a public shop-pricing API.

## Current Release

**CdrBounty `0.7.0 — Shop Price Integration`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, BetonQuest 3.2.0, and a Vault-compatible economy provider.

Optional integrations:
- CdrReputation — automatic system bounty thresholds.
- BetonQuest — quest bounty actions and conditions.
- CdrQuestJournal — coordinated through the same BetonQuest event chain; CdrBounty does not bypass Journal lifecycle or safe turn-in.
- CdrVephilimEconomy — higher BUY prices for players with active bounties.

## Player Flow

Normal players have **no `/bounty` command**.

```text
Citizens Bounty Master NPC
        ↓
Bounty Board
        ↓
Accept Contract
        ↓
Bounty Tracker Compass
        ↓
Approximate target direction
```

Player-created bounty:

```text
NPC Placement Wizard
↓
Vault escrow
↓
PENDING_APPROVAL
↓
/cdrbounty approval
├─ APPROVE → OPEN
└─ REJECT  → full requester refund
```

`PENDING_APPROVAL` does not make the target wanted for shop pricing. Only active `OPEN` / `RESERVED` contracts contribute to the wanted total.

## Shop Price Integration

CdrBounty publishes `CdrBountyShopApi` through Bukkit ServicesManager. Shop plugins consume an in-memory snapshot instead of querying bounty SQLite during every GUI render or transaction.

API:

```java
boolean isWanted(UUID playerId);
BigDecimal activeBountyTotal(UUID playerId);
double shopBuyMultiplier(UUID playerId);
```

Default BUY multipliers:

```text
Active bounty >= 100       → 1.10x
Active bounty >= 50,000    → 1.25x
Active bounty >= 100,000   → 1.50x
Active bounty >= 500,000   → 2.00x
```

Configuration:

```yaml
shop-integration:
  enabled: true
  refresh-seconds: 3
  tiers:
    low:
      minimum-bounty: "100.00"
      buy-multiplier: 1.10
    medium:
      minimum-bounty: "50000.00"
      buy-multiplier: 1.25
    high:
      minimum-bounty: "100000.00"
      buy-multiplier: 1.50
    extreme:
      minimum-bounty: "500000.00"
      buy-multiplier: 2.00
```

CdrVephilimEconomy applies the surcharge after market/dynamic pricing and personal BUY discounts:

```text
market price
↓
personal BUY discount
↓
CdrBounty wanted multiplier
↓
FINAL BUY PRICE
```

SELL prices are intentionally unchanged. The same final BUY quote is used by Java GUI, Bedrock forms, and server-side transaction validation.

## Quest Integration

CdrBounty registers native BetonQuest 3.2.0 actions and conditions through `IntegrationService`.

### Actions

```text
cdrbounty_create <questKey> <targetNameOrUuid> <amount>
cdrbounty_cancel <questKey>
```

Quest contracts are server-funded, skip administrator approval, use `PRIVATE + EXCLUSIVE`, are allowlisted to the quest player, auto-accepted, and issue the tracker automatically. Normal claim validation, anti-farm, inaccurate tracking, pause timer, and payout still apply.

Calling `cdrbounty_create` again while the same player's `questKey` is still active reuses the existing contract. Once the latest attempt is terminal, the same key may create a new contract for repeatable quests.

### Conditions

```text
cdrbounty_has <questKey>
cdrbounty_active <questKey>
cdrbounty_completed <questKey>
```

Recommended chain:

```text
Quest NPC / BetonQuest
↓
cdrjournal_start red_pirate
↓
cdrbounty_create red_pirate Redbeard 50000
↓
PRIVATE + EXCLUSIVE bounty auto-accepted
↓
hunt + successful bounty settlement
↓
cdrbounty_completed red_pirate = true
↓
BetonQuest advances Journal objective
↓
cdrjournal_prepare
↓
external rewards / reputation
↓
cdrjournal_finalize
```

BetonQuest remains the quest logic authority and CdrQuestJournal remains the journal / safe-turn-in authority.

## Reputation Auto-Bounty

When CdrReputation is installed, CdrBounty consumes its Bukkit API/event rather than reading the reputation database.

Default cumulative thresholds:

```text
Reputation <= -1000 → +25,000
Reputation <= -2000 → +25,000
Reputation <= -3500 → +50,000
```

If an existing SYSTEM bounty is still active, later escalation tops up the same contract rather than creating duplicate board entries.

## Inaccurate Bounty Tracker

The tracker never receives exact live target coordinates.

```text
0–300 blocks      → ±25 blocks
301–1000          → ±50 blocks
1001–2000         → ±80 blocks
>2000             → ±120 blocks
```

The approximate point is regenerated periodically. Cross-world targets produce **Signal Lost**. The active bounty timer pauses while the target is offline or inside configured safe worlds such as `lobby`; contract and contribution deadlines are extended together.

## Bounty Master Features

The NPC wizard supports:
- Bounty Board;
- My Contracts;
- bounty placement;
- `PUBLIC` / `PRIVATE`;
- `EXCLUSIVE`;
- `ANONYMOUS`;
- required/forbidden world;
- required weapon;
- confirmation before escrow;
- private chat input with timeout/cancel;
- resumable placement drafts.

## Admin Commands

```text
/cdrbounty npc bind
/cdrbounty approval
/cdrbounty npc info
/cdrbounty npc unbind
/cdrbounty reload
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

## Safety / Persistence

- SQLite-backed contracts, approval, tracking, reputation escalation, and quest bindings.
- Wanted shop pricing is served from a periodically refreshed thread-safe cache.
- Pending/unapproved bounty requests do not affect shop prices.
- Quest and reputation bounties use the same settlement and anti-farm pipeline.
- Player bounty economy operations remain escrow-backed and recoverable.
- Per-player hunting only; no shared party contracts.
- Tracker items are tied to contract UUIDs through PDC and cleaned when stale.
- Optional integrations remain soft dependencies; core NPC bounty gameplay remains loadable without them.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 ✅ Inaccurate Compass Tracking
0.5.0 ✅ Reputation Auto-Bounty
0.6.0 ✅ Quest Integration
0.7.0 ✅ Shop Price Integration
0.9.0 → Polish / Crossplay / Anti-Abuse
1.0.0 → Production
```

See [`ROADMAP.md`](ROADMAP.md).

## Build

```bash
mvn clean verify
```

CI validates the packaged JAR, public shop API, wanted-price cache service, quest/reputation/tracking classes, version/config metadata, and confirms that player `/bounty` has not been reintroduced.

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
