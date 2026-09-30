# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC. Player-created bounty requests require administrator approval, accepted hunters receive an intentionally inaccurate compass tracker, and very poor CdrReputation can automatically create system-funded bounties.

## Current Release

**CdrBounty `0.5.0 — Reputation Auto-Bounty`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, and a Vault-compatible economy provider. CdrReputation is an optional soft dependency used by the automatic system-bounty integration.

## Core Flow

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

Player-created bounties still use:

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

## Reputation Auto-Bounty

When CdrReputation is installed, CdrBounty hooks its Bukkit service and `ReputationChangeEvent` at runtime. CdrBounty does not read or modify the CdrReputation database directly.

Default thresholds are cumulative:

```text
Reputation <= -1000 → +25,000 system bounty
Reputation <= -2000 → +25,000 additional bounty
Reputation <= -3500 → +50,000 additional bounty

Maximum cumulative automatic escalation: 100,000
```

A large reputation drop can cross several thresholds at once. For example, moving directly to `-2500` creates a `50,000` system bounty rather than requiring two separate reputation events.

System bounties:

- are funded by the server/system rather than a player's Vault balance;
- become `OPEN` immediately;
- bypass administrator approval;
- appear in the same NPC Bounty Board;
- show `Issuer: SYSTEM`;
- use the same hunter accept, compass tracking, claim validation, pause timer, anti-farm, and payout pipeline as normal contracts.

The plugin stores the most severe reputation threshold already issued for each player, so repeated reputation updates inside the same tier do **not** create duplicate bounties. The escalation becomes armed again only after reputation recovers to the configured reset threshold.

Default configuration:

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

This keeps the responsibilities separate:

```text
CdrReputation
      ↓ ReputationChangeEvent
CdrBounty
      ↓ system contract
Bounty Master NPC
      ↓
Hunter
```

Future systems such as CdrReport can reduce reputation only after an administrator validates a report; if that penalty crosses one of these thresholds, CdrBounty naturally issues the configured system bounty.

## Inaccurate Bounty Tracker

The compass never receives the target's exact location. Default accuracy:

```text
0–300 blocks      → ±25 blocks
301–1000          → ±50 blocks
1001–2000         → ±80 blocks
>2000             → ±120 blocks
```

The approximate point is regenerated on refresh. Cross-world targets produce **Signal Lost** instead of exposing coordinates.

The active bounty timer pauses while the target is offline or inside a configured safe world such as `lobby`. Both the contract and escrow contribution deadline are extended together.

## Bounty Master Features

The NPC placement wizard supports:

- `PUBLIC` / `PRIVATE`;
- `EXCLUSIVE`;
- `ANONYMOUS`;
- required world;
- forbidden world;
- required weapon;
- confirmation before escrow;
- chat-input timeout;
- resumable in-memory drafts.

## Admin Setup

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

- SQLite-backed contracts, approval, tracking pause state, and reputation escalation state.
- Reputation-generated contribution + contract + threshold state are written in one SQLite transaction.
- CdrReputation integration is soft-linked through Bukkit ServicesManager/reflection; CdrBounty can still start without it.
- Vault escrow and recoverable economy intents remain authoritative for player-created bounties.
- Per-player hunter contracts; no shared party hunting.
- Existing anti-farm checks remain in the settlement path.
- Stale tracker items are cleaned when the associated accepted contract is no longer active.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 ✅ Inaccurate Compass Tracking
0.5.0 ✅ Reputation Auto-Bounty
0.6.0 → Quest Integration
0.7.0 → Shop Price Integration
0.9.0 → Polish / Crossplay / Anti-Abuse
1.0.0 → Production
```

See [`ROADMAP.md`](ROADMAP.md) for the focused roadmap.

## Build

```bash
mvn clean verify
```

CI validates the packaged JAR, reputation integration classes, tracking classes, configuration, version metadata, optional CdrReputation declaration, and confirms that player `/bounty` has not been reintroduced.

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
