# CdrBounty Development Roadmap

CdrBounty stays intentionally focused: **NPC bounty gameplay, moderation, tracking, reputation/quest integration, and economic consequences**. It is not intended to become a large hunter-career or capture/jail framework.

## Core Rules

- Normal players use the Citizens Bounty Master NPC; no player `/bounty` command.
- Player-created bounty requests require admin/owner approval before activation.
- Trusted system/quest integrations may bypass manual approval.
- Hunter contracts are per-player, not shared party contracts.
- Bounty tracking is approximate, never an exact live coordinate feed.
- Offline targets and targets in configured safe worlds such as `lobby` pause the active bounty timer.
- Reputation remains a separate system; CdrBounty reacts to reputation state instead of owning it.
- BetonQuest remains the quest-logic authority; CdrQuestJournal remains the journal/safe-turn-in authority.
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
| `0.6.0` | BetonQuest / CdrQuestJournal integration | ✅ |
| `0.7.0` | Shop price integration for wanted players | Next |
| `0.9.0` | Polish, crossplay, anti-abuse, diagnostics | Planned |
| `1.0.0` | Production stable | Planned |

## v0.5.0 — Reputation Auto-Bounty ✅

Implemented:
- CdrReputation event/API hook;
- configurable negative-reputation thresholds;
- trusted system-funded PUBLIC contracts;
- cumulative escalation with duplicate prevention;
- active system-contract top-up;
- reset/rearm threshold;
- shared tracker, pause, anti-farm, settlement, and payout pipeline.

## v0.6.0 — Quest Integration ✅

Implemented native BetonQuest 3.2.0 hooks:

```text
Actions:
cdrbounty_create <questKey> <targetNameOrUuid> <amount>
cdrbounty_cancel <questKey>

Conditions:
cdrbounty_has <questKey>
cdrbounty_active <questKey>
cdrbounty_completed <questKey>
```

Quest contract behavior:
- system-funded and bypasses admin approval;
- `PRIVATE + EXCLUSIVE`;
- allowlisted to the BetonQuest profile player;
- automatically accepted;
- tracker automatically issued;
- same anti-farm, pause timer, claim, and payout systems as normal bounties;
- duplicate create calls reuse the current active attempt;
- terminal attempts can be followed by a new contract for repeatable quest flows;
- persistent player + quest key bindings in SQLite;
- atomic contract/binding creation.

Recommended responsibility split:

```text
Citizens Quest NPC
        ↓
BetonQuest
  ├─ CdrQuestJournal lifecycle/progress
  └─ CdrBounty hunt state
        ↓
CdrReputation / external rewards
```

A successful `cdrbounty_completed` condition can gate `cdrjournal_progress`, followed by the normal `cdrjournal_prepare → rewards → cdrjournal_finalize` safe turn-in chain.

## v0.7.0 — Wanted Shop Price Integration

Expose a small public service/API for economy plugins such as CdrVephilimEconomy.

Core API intent:
- `isWanted(UUID)`;
- active bounty total;
- configurable BUY price multiplier.

Wanted players can pay higher shop BUY prices while SELL values remain unchanged. The multiplier must be used by both GUI quotes and transaction validation so displayed and charged prices cannot diverge.

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
