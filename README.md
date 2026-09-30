# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC. Player bounty requests require administrator approval, hunters receive an intentionally inaccurate compass tracker, poor CdrReputation can create system bounties, and BetonQuest can now create private quest-specific hunts.

## Current Release

**CdrBounty `0.6.0 — Quest Integration`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, BetonQuest 3.2.0, and a Vault-compatible economy provider.

Optional integrations:
- CdrReputation — automatic system bounty thresholds.
- BetonQuest — quest bounty actions and conditions.
- CdrQuestJournal — coordinated through the same BetonQuest event chain; CdrBounty does not bypass Journal lifecycle or safe turn-in.

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

## Quest Integration

CdrBounty registers native BetonQuest 3.2.0 actions and conditions through `IntegrationService`.

### Actions

```text
cdrbounty_create <questKey> <targetNameOrUuid> <amount>
cdrbounty_cancel <questKey>
```

`cdrbounty_create` creates a quest-specific bounty for the BetonQuest profile player.

Quest contracts are intentionally different from player-posted bounties:
- server/system funded;
- no administrator approval;
- `PRIVATE + EXCLUSIVE`;
- allowlisted only to the quest player;
- automatically accepted by that player;
- tracker is issued automatically;
- duration uses `placement.duration-seconds`;
- target by name must have joined the server before, or a UUID may be supplied directly;
- normal claim validation, anti-farm, inaccurate tracking, pause timer, and payout still apply.

Calling `cdrbounty_create` again while the same player's `questKey` is still active reuses the existing contract instead of duplicating it. Once the latest attempt is terminal, the same key may create a new contract, which keeps repeatable quests usable.

`cdrbounty_cancel` cancels the latest active quest contract for that player without creating a refund, because quest bounties are system-funded.

### Conditions

```text
cdrbounty_has <questKey>
cdrbounty_active <questKey>
cdrbounty_completed <questKey>
```

Semantics:
- `cdrbounty_has` — a latest bound attempt exists for the player and key;
- `cdrbounty_active` — the latest attempt is currently huntable/settling;
- `cdrbounty_completed` — the latest attempt was successfully claimed by that quest player.

### Recommended BetonQuest + CdrQuestJournal Chain

```text
Quest NPC / BetonQuest
↓
cdrjournal_start red_pirate
↓
cdrbounty_create red_pirate Redbeard 50000
↓
PRIVATE + EXCLUSIVE bounty auto-accepted
↓
Hunter receives approximate tracker
↓
Target killed and bounty settlement succeeds
↓
cdrbounty_completed red_pirate = true
↓
BetonQuest advances the Journal objective
↓
CdrQuestJournal becomes READY
↓
Return to the bound quest NPC
↓
cdrjournal_prepare
↓
external quest rewards / reputation actions
↓
cdrjournal_finalize
```

CdrBounty therefore supplies the hunt state. BetonQuest remains the quest logic authority, and CdrQuestJournal remains the physical journal / safe turn-in lifecycle authority.

## Reputation Auto-Bounty

When CdrReputation is installed, CdrBounty consumes its Bukkit API/event rather than reading the reputation database.

Default cumulative thresholds:

```text
Reputation <= -1000 → +25,000
Reputation <= -2000 → +25,000
Reputation <= -3500 → +50,000
```

If an existing SYSTEM bounty is still active, later escalation tops up that same contract rather than creating duplicate board entries.

```yaml
reputation-auto-bounty:
  enabled: true
  announce: true
  reset-threshold: -500
  tiers:
    outlaw:
      threshold: -1000
      add-bounty: "25000.00"
    infamous:
      threshold: -2000
      add-bounty: "25000.00"
    public-enemy:
      threshold: -3500
      add-bounty: "50000.00"
```

## Inaccurate Bounty Tracker

The tracker never receives exact live target coordinates.

```text
0–300 blocks      → ±25 blocks
301–1000          → ±50 blocks
1001–2000         → ±80 blocks
>2000             → ±120 blocks
```

The approximate point is regenerated periodically. Cross-world targets produce **Signal Lost**.

The active bounty timer pauses while the target is offline or inside configured safe worlds such as `lobby`. Both contract and underlying contribution deadlines are extended together.

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
- Quest bounty contribution + contract + allowlist + hunter reservation + quest link are committed atomically.
- Player bounty economy operations remain escrow-backed and recoverable.
- Reputation system bounty writes/escalation are atomic.
- Quest and reputation bounties use the same settlement and anti-farm pipeline.
- Per-player hunting only; no shared party contracts.
- Tracker items are tied to contract UUIDs through PDC and cleaned when stale.
- CdrReputation, BetonQuest, and CdrQuestJournal are soft dependencies; core NPC bounty gameplay remains loadable without them.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 ✅ Inaccurate Compass Tracking
0.5.0 ✅ Reputation Auto-Bounty
0.6.0 ✅ Quest Integration
0.7.0 → Shop Price Integration
0.9.0 → Polish / Crossplay / Anti-Abuse
1.0.0 → Production
```

See [`ROADMAP.md`](ROADMAP.md).

## Build

```bash
mvn clean verify
```

CI validates the packaged JAR, BetonQuest integration classes, quest persistence classes, reputation/tracking classes, dependency metadata, and confirms that player `/bounty` has not been reintroduced.

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
