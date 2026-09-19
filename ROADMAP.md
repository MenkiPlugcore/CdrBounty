# CdrBounty Development Roadmap

CdrBounty is intentionally being built in layers. Each phase must leave the plugin in a stable, testable state before the next gameplay system is added.

## Release Philosophy

- **Data integrity first.** Money, items, contracts, and claims must never duplicate or disappear silently.
- **Server-authoritative logic.** Rewards and contract completion are validated by the server, not by GUI state.
- **Configurable by default.** Gameplay rules should be adjustable without modifying source code.
- **Soft dependencies where possible.** Optional integrations must not prevent CdrBounty from starting.
- **Async where safe, sync where Bukkit requires it.** Database and expensive I/O must not block the main thread.
- **Migration-safe storage.** Every persistent schema change must have an explicit migration path.
- **Auditability.** Important economy and administrative actions should be traceable.

## Roadmap Overview

| Version | Codename | Main Goal | Exit Requirement |
|---|---|---|---|
| `beta.1` | Core Foundation | Safe bounty lifecycle and persistence | Bounty can be placed, stored, claimed, expired, refunded, and audited safely |
| `beta.2` | Contract Engine | Turn bounties into structured hunting contracts | Multiple contract types and conditions operate reliably |
| `v0.3.0` | Hunter System | Add hunter identity and progression | License, reputation, ranks, stats, and contract reservations work end-to-end |
| `v0.4.0` | Intelligence | Make tracking a gameplay system | Hunters locate targets through progressive intel instead of free coordinates |
| `v0.5.0` | Wanted | Dynamic Heat and Most Wanted gameplay | Server-generated wanted states and bounty escalation are stable |
| `v0.6.0` | Capture | Give targets counterplay and alive contracts | Dead/Alive capture flow, escape tools, jail, and bail are abuse-resistant |
| `v0.7.0` | Social Warfare | Create emergent multiplayer bounty conflicts | Chains, teams, rival hunters, and dynamic hunt events work safely |
| `v0.8.0` | Ecosystem | Integrations and developer API | Stable API/events plus supported external integrations |
| `v0.9.0` | Hardening | Network, storage, migration, performance | Multi-server/data consistency and production stress requirements pass |
| `v1.0.0` | Production | Stable public release | Full regression suite, documentation, migration path, and release package complete |

---

## beta.1 — Core Foundation

**Documentation:** [`docs/roadmap/beta-1-core-foundation.md`](docs/roadmap/beta-1-core-foundation.md)

Foundation for every later system.

Major scope:
- Plugin bootstrap and module structure
- Vault economy adapter
- Bounty placement and stacking
- Escrow ledger
- Kill attribution and claim validation
- Expiration/refund lifecycle
- SQLite persistence
- Offline-player support
- Basic anti-farming
- Admin commands and audit logs
- Config/messages separation

No advanced Hunter, Intel, or Heat mechanics belong in this phase.

---

## beta.2 — Contract Engine

**Documentation:** [`docs/roadmap/beta-2-contract-engine.md`](docs/roadmap/beta-2-contract-engine.md)

Transforms a bounty amount into a real contract model.

Major scope:
- Public contracts
- Private contracts
- Anonymous issuers
- Exclusive contracts
- Contract acceptance and abandonment
- Expiration and reservation limits
- Condition framework
- Reward composition
- Contract browser GUI
- Contract lifecycle events

---

## v0.3.0 — Hunter System

**Documentation:** [`docs/roadmap/v0.3.0-hunter-system.md`](docs/roadmap/v0.3.0-hunter-system.md)

Creates a persistent bounty-hunter career.

Major scope:
- Hunter license
- Hunter reputation
- Rank progression
- Rank unlock requirements
- Hunter profile
- Contract statistics
- Perfect-hunt statistics groundwork
- Contract reservation limits
- Leaderboards
- Season-ready data model

---

## v0.4.0 — Intelligence & Tracking

**Documentation:** [`docs/roadmap/v0.4.0-intelligence-tracking.md`](docs/roadmap/v0.4.0-intelligence-tracking.md)

Replaces free target coordinates with progressive information gathering.

Major scope:
- Intel confidence system
- Dimension/biome/radius clues
- Timed exact-location pulses
- Hunter Compass
- Signal strength and distance bands
- Last-seen records
- Target proximity warning
- Intel cooldown/cost model
- Privacy-safe tracking rules

---

## v0.5.0 — Heat & Wanted System

**Documentation:** [`docs/roadmap/v0.5.0-heat-wanted.md`](docs/roadmap/v0.5.0-heat-wanted.md)

Lets server activity generate bounty gameplay automatically.

Major scope:
- Heat engine
- Configurable Heat sources
- Wanted tiers
- Automatic system bounty
- Most Wanted board
- Public Enemy state
- Dynamic bounty escalation
- Survival timers
- Hunter-kill escalation
- Legendary bounty thresholds

---

## v0.6.0 — Capture & Target Counterplay

**Documentation:** [`docs/roadmap/v0.6.0-capture-counterplay.md`](docs/roadmap/v0.6.0-capture-counterplay.md)

Makes being hunted an active gameplay role instead of passive punishment.

Major scope:
- Dead / Alive / Dead-or-Alive contracts
- Capture state machine
- Delivery to bounty authority/NPC
- Jail sentences
- Bail
- Hunter Compass scrambler
- False trails / decoys
- Escape handling
- Hunter-kill bonuses and consequences
- Revenge contracts

---

## v0.7.0 — Social Warfare & Dynamic Hunts

**Documentation:** [`docs/roadmap/v0.7.0-social-warfare.md`](docs/roadmap/v0.7.0-social-warfare.md)

Creates emergent player stories around the bounty ecosystem.

Major scope:
- Bounty chains
- Hunter teams
- Shared/individual payouts
- Rival hunter rules
- Betrayal safeguards
- Target allies
- Dynamic hunt events
- Legendary hunt broadcasts
- Seasonal categories
- Global activity feed hooks

---

## v0.8.0 — Integrations & Public API

**Documentation:** [`docs/roadmap/v0.8.0-integrations-api.md`](docs/roadmap/v0.8.0-integrations-api.md)

Turns CdrBounty into an extensible server platform.

Major scope:
- Stable Java API
- Bukkit events
- PlaceholderAPI
- Citizens
- WorldGuard
- LuckPerms
- Vault abstraction cleanup
- Optional AuraSkills hooks
- Optional party/guild/faction adapters
- Discord webhook hooks
- Extension/provider interfaces

---

## v0.9.0 — Network, Storage & Production Hardening

**Documentation:** [`docs/roadmap/v0.9.0-production-hardening.md`](docs/roadmap/v0.9.0-production-hardening.md)

Prepares CdrBounty for larger servers and networks.

Major scope:
- MySQL/MariaDB provider
- Connection pooling
- Schema migrations
- Cross-server consistency model
- Optional proxy/network messaging layer
- Crash/restart recovery
- Idempotent reward settlement
- Performance profiling
- Abuse/race-condition regression tests
- Backup/restore guidance

---

## v1.0.0 — Production Release

**Documentation:** [`docs/roadmap/v1.0.0-production-release.md`](docs/roadmap/v1.0.0-production-release.md)

The first stable release.

Major scope:
- Configuration freeze for v1 API guarantees
- Complete permission reference
- Complete command reference
- Administrator guide
- Migration guide
- Public API documentation
- Full regression matrix
- Fresh-install test
- Upgrade test
- Failure/recovery test
- Release artifacts and checksums
- Final changelog

---

## Features Deliberately Deferred Until After v1.0

These ideas are valid, but should not destabilize the first release:

- Web dashboard
- Redis-backed network event bus
- Native Bedrock Forms integration
- Built-in NPC implementation without Citizens
- Advanced machine-learning-style fraud scoring
- Marketplace/cloud contract synchronization
- Cross-network global seasons

## Definition of Done for Every Roadmap Phase

A phase is only complete when:

1. Its documented functional requirements are implemented.
2. Persistent data survives clean restart and forced shutdown scenarios relevant to the feature.
3. Permissions are defined for every user/admin action.
4. User-facing text is configurable where reasonable.
5. No known economy duplication or double-settlement path remains.
6. Existing regression tests still pass.
7. New behavior has regression coverage.
8. Documentation reflects the shipped behavior.
9. Upgrade/migration impact is recorded.
10. A changelog entry is ready before tagging the release.

## Documentation Index

See [`docs/README.md`](docs/README.md) for the documentation map and ownership rules.
