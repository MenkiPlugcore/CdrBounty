# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC. Player-created bounty requests require administrator approval, and accepted hunters receive an intentionally inaccurate compass tracker.

## Current Release

**CdrBounty `0.4.0 — Inaccurate Compass Tracking`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, and a Vault-compatible economy provider.

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

Accepted contracts can also be viewed through **My Contracts**. Left-click an accepted contract to reissue its tracker; right-click to abandon it.

## Player-Created Bounty Approval

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

Pending requests cannot be accepted or claimed. Approval starts the active bounty timer. Rejection uses the recoverable refund pipeline.

## Inaccurate Bounty Tracker

The compass never receives the target's exact location. CdrBounty creates a random offset around the target every tracker refresh.

Default accuracy bands:

```text
0–300 blocks      → ±25 blocks
301–1000          → ±50 blocks
1001–2000         → ±80 blocks
>2000             → ±120 blocks
```

The approximate location is regenerated periodically, so the compass behaves like a tracking signal rather than GPS.

If the target is in another world/dimension, the tracker reports **Signal Lost** instead of leaking cross-world coordinates.

## Paused Contract Timer

The bounty timer pauses when the target cannot reasonably be hunted:

- target is offline;
- target is inside a configured pause/safe world such as `lobby`.

The pause affects both the contract deadline and the underlying escrow contribution deadline. The expiration/refund system therefore does not consume bounty time while the target is unavailable.

Player join, quit, and world-change events update availability immediately, with a periodic heartbeat as a recovery fallback.

## Tracking Configuration

```yaml
tracking:
  update-seconds: 20
  pause-scan-seconds: 5
  pause-worlds:
    - lobby

  accuracy:
    close-max-distance: 300
    medium-max-distance: 1000
    far-max-distance: 2000

    close-offset: 25
    medium-offset: 50
    far-offset: 80
    very-far-offset: 120
```

Add additional safe worlds to `tracking.pause-worlds` if needed.

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

Bind the Bounty Master NPC:

```text
/cdrbounty npc bind
```

Useful commands:

```text
/cdrbounty approval
/cdrbounty npc info
/cdrbounty npc unbind
/cdrbounty reload
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

## Safety / Persistence

- SQLite-backed contracts and tracking pause state.
- Vault escrow and recoverable economy intents.
- Full rejection refund for player bounty requests.
- Per-player hunter contracts; no shared party hunting.
- Anti-farm checks remain in the settlement path.
- Stale tracker items are removed when the associated accepted contract is no longer active.
- Tracker state uses contract IDs stored in item PDC rather than trusting item display text.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 ✅ Inaccurate Compass Tracking
0.5.0 → Reputation Auto-Bounty
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

CI validates the packaged JAR, tracking classes, configuration, version metadata, Citizens dependency, and confirms that player `/bounty` has not been reintroduced.

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
