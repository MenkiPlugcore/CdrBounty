# CdrBounty Development Roadmap

CdrBounty stays intentionally focused: **NPC bounty gameplay, moderation, tracking, reputation/quest integration, and economic consequences**. It is not intended to become a large hunter-career or capture/jail framework.

## Core Rules

- Normal players use the Citizens Bounty Master NPC; no player `/bounty` command.
- Player-created bounty requests require admin/owner approval before activation.
- System-generated bounties may bypass manual approval when an integration explicitly creates them.
- Hunter contracts are per-player, not shared party contracts.
- Bounty tracking is approximate, never an exact live coordinate feed.
- Offline targets and targets in configured safe worlds such as `lobby` pause the active bounty timer.
- Economy settlement remains escrow-backed, recoverable, and server-authoritative.

## Roadmap

| Version | Main Goal | Status |
|---|---|---|
| `0.1.0-beta.1` | Core escrow, SQLite, payout/refund, anti-farm | ✅ |
| `0.2.0-beta.2` | Structured Contract Engine | ✅ |
| `0.2.1-beta.2` | NPC-only player access | ✅ |
| `0.2.2-beta.2` | Full NPC bounty GUI / placement wizard | ✅ |
| `0.3.0` | Admin Approval for player bounty requests | ✅ |
| `0.4.0` | Inaccurate Compass Tracking + paused timer | ✅ |
| `0.5.0` | CdrReputation automatic system bounty | Next |
| `0.6.0` | BetonQuest / CdrQuestJournal integration | Planned |
| `0.7.0` | Shop price integration for wanted players | Planned |
| `0.9.0` | Polish, crossplay, anti-abuse, diagnostics | Planned |
| `1.0.0` | Production stable | Planned |

## v0.5.0 — Reputation Auto-Bounty

CdrBounty will consume CdrReputation through a clean integration boundary.

Planned behavior:
- configurable negative-reputation thresholds;
- automatic server-funded bounty creation when a player crosses a threshold;
- no player approval required for a trusted system bounty;
- one active system bounty policy configurable to avoid duplicate escalation;
- reputation itself remains separate from bounty state;
- future CdrReport can reduce reputation only after an admin validates a report, which can then naturally trigger this system.

## v0.6.0 — Quest Integration

Integrate with BetonQuest and CdrQuestJournal without creating another quest engine.

Planned hooks include:
- create/cancel bounty from quest actions;
- query active/completed bounty state from quest conditions;
- allow a bounty completion to advance a quest objective;
- preserve CdrQuestJournal safe turn-in as the quest reward authority when applicable.

## v0.7.0 — Wanted Shop Price Integration

Expose a small public service/API for economy plugins such as CdrVephilimEconomy.

Core API intent:
- `isWanted(UUID)`;
- active bounty total;
- configurable BUY price multiplier.

Wanted players can pay higher shop BUY prices while SELL values remain unchanged. The same multiplier must be applied to both GUI quotes and transaction validation so the displayed and charged prices cannot diverge.

## v0.9.0 — Polish / Crossplay / Anti-Abuse

Final beta hardening:
- Java + Bedrock/Geyser interaction tests;
- tracker item cleanup/recovery;
- NPC binding validation;
- SQLite consistency diagnostics;
- restart/crash recovery regression;
- alt/same-IP/repeated-kill abuse review;
- config migration/default validation;
- performance pass;
- startup diagnostics and administrator audit improvements.

## v1.0.0 — Production

Feature freeze and production release:
- stable configuration;
- final administrator guide;
- upgrade/migration guide;
- permission/command reference;
- fresh-install and upgrade tests;
- release artifact validation and checksums.

## After CdrBounty

The planned companion project is **CdrReport**: an NPC police/report system with admin GUI workflow (`OPEN → INVESTIGATING → RESOLVED/DISMISSED`). Validated reports can optionally apply CdrReputation penalties; reputation thresholds can then create automatic system bounties through CdrBounty.
