# CdrBounty Development Roadmap

CdrBounty stays intentionally focused: **NPC bounty gameplay, moderation, tracking, reputation/quest integration, and economic consequences**. It is not intended to become a large hunter-career or capture/jail framework.

## Core Rules

- Normal players use the Citizens Bounty Master NPC; no player `/bounty` command.
- Player-created bounty requests require admin/owner approval before activation.
- System-generated bounties may bypass manual approval when a trusted integration creates them.
- Hunter contracts are per-player, not shared party contracts.
- Bounty tracking is approximate, never an exact live coordinate feed.
- Offline targets and targets in configured safe worlds such as `lobby` pause the active bounty timer.
- Reputation remains a separate system; CdrBounty reacts to reputation state instead of owning it.
- Economy settlement remains server-authoritative and recoverable.

## Roadmap

| Version | Main Goal | Status |
|---|---|---|
| `0.1.0-beta.1` | Core escrow, SQLite, payout/refund, anti-farm | ✅ |
| `0.2.0-beta.2` | Structured Contract Engine | ✅ |
| `0.2.1-beta.2` | NPC-only player access | ✅ |
| `0.2.2-beta.2` | Full NPC bounty GUI / placement wizard | ✅ |
| `0.3.0` | Admin Approval for player bounty requests | ✅ |
| `0.4.0` | Inaccurate Compass Tracking + paused timer | ✅ |
| `0.5.0` | CdrReputation automatic system bounty | ✅ |
| `0.6.0` | BetonQuest / CdrQuestJournal integration | Next |
| `0.7.0` | Shop price integration for wanted players | Planned |
| `0.9.0` | Polish, crossplay, anti-abuse, diagnostics | Planned |
| `1.0.0` | Production stable | Planned |

## v0.5.0 — Reputation Auto-Bounty ✅

Implemented behavior:
- runtime hook to CdrReputation through Bukkit ServicesManager and `ReputationChangeEvent`;
- CdrReputation remains an optional soft dependency;
- configurable negative reputation thresholds;
- system-funded PUBLIC contracts bypass manual approval;
- cumulative escalation when one reputation change crosses multiple thresholds;
- persistent anti-duplicate threshold state;
- configurable recovery/reset threshold to rearm future escalation;
- automatic system contracts use the same Bounty Board, hunter acceptance, tracker, pause timer, anti-farm, and payout path;
- system contribution + contract + escalation state + audit are committed atomically in SQLite;
- future CdrReport can apply a validated reputation penalty and naturally trigger this integration.

Default cumulative result:

```text
Rep <= -1000 → total automatic escalation 25k
Rep <= -2000 → total automatic escalation 50k
Rep <= -3500 → total automatic escalation 100k
```

## v0.6.0 — Quest Integration

Integrate with BetonQuest and CdrQuestJournal without creating another quest engine.

Planned hooks:
- create/cancel a trusted system bounty from quest actions;
- query active/completed bounty state from quest conditions;
- allow bounty completion to advance a quest objective;
- preserve CdrQuestJournal safe turn-in as quest reward authority when applicable;
- keep player bounty placement inside the Bounty Master NPC rather than adding gameplay commands.

## v0.7.0 — Wanted Shop Price Integration

Expose a small public service/API for economy plugins such as CdrVephilimEconomy.

Core API intent:
- `isWanted(UUID)`;
- active bounty total;
- configurable BUY price multiplier.

Wanted players can pay higher shop BUY prices while SELL values remain unchanged. The same multiplier must be applied to both GUI quotes and transaction validation so displayed and charged prices cannot diverge.

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
