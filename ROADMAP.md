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
| `0.7.0` | Shop price integration for wanted players | ✅ |
| `0.9.0` | Polish, crossplay, anti-abuse, diagnostics | ✅ |
| `1.0.0` | Production stable / feature freeze | Next |

## v0.9.0 — Production Hardening ✅

Implemented:
- safe config migration with `config-version: 9` and pre-migration backup;
- `/cdrbounty diagnose` production health command;
- SQLite `PRAGMA integrity_check` and orphan-data checks;
- unresolved economy-operation diagnostics;
- Citizens Bounty Master resolution diagnostics;
- integration presence diagnostics including Floodgate detection;
- automatic stale/duplicate tracker sanitation;
- optional minimum hunter/target playtime claim gates;
- existing same-IP, pair cooldown, repeated-pair, and target-survival protections retained;
- startup production-health and crossplay logging;
- packaged artifact validation for migration/diagnostic classes and config schema.

## v1.0.0 — Production

Final feature freeze:
- no new gameplay systems;
- fresh-install smoke test;
- upgrade smoke test from 0.7/0.9 data;
- final administrator guide;
- permission/command reference;
- release checksum and artifact verification;
- production tag/release.

## After CdrBounty

The planned companion project is **CdrReport**: an NPC police/report system with admin GUI workflow (`OPEN → INVESTIGATING → RESOLVED/DISMISSED`). Validated reports can optionally apply CdrReputation penalties; reputation thresholds can then create automatic system bounties through CdrBounty.
