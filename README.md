# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is a gameplay-first bounty hunting framework for Minecraft servers. Instead of stopping at `place bounty -> kill target -> claim money`, CdrBounty is designed around contracts, hunter progression, intelligence gathering, heat/wanted status, target counterplay, capture mechanics, dynamic events, and extensibility.

## Project Status

**Phase:** `beta.1 — Core Foundation` (active development)

**Primary target:** Paper 1.21.11, Java 21

The beta.1 source foundation is now implemented in the repository. It includes Vault-backed placement and payout, SQLite persistence, crash-aware economy intents, claim locking, basic anti-farming, expiration/refund processing, admin inspection/history controls, and Java 21 Maven CI. It is still a development build and should be validated on a staging server before production use.

## Core Design Pillars

1. **Contracts, not simple kill rewards** — public, private, anonymous, exclusive, and conditional hunts.
2. **A real hunter career** — licenses, reputation, ranks, statistics, progression, and leaderboards.
3. **Intel instead of free coordinates** — clues, tracking confidence, compass signals, pulses, and misinformation.
4. **Targets can fight the system** — scramblers, false trails, hunter kills, escapes, and survival bonuses.
5. **Dynamic wanted gameplay** — Heat, automatic system bounties, Most Wanted, Public Enemy, and bounty escalation.
6. **Abuse-resistant economy** — escrow, anti-farming, suspicious-claim scoring, audit logs, configurable payout rules.
7. **Platform for other plugins** — PlaceholderAPI, Vault, Citizens, WorldGuard, APIs, events, and optional integrations.

## beta.1 Commands

Player:

- `/bounty add <player> <amount>`
- `/bounty view <player>`
- `/bounty list`

Administration:

- `/cdrbounty reload`
- `/cdrbounty add <player> <amount>`
- `/cdrbounty remove <player> all`
- `/cdrbounty inspect <player>`
- `/cdrbounty history <player>`
- `/cdrbounty debug`

Partial-value admin removal is intentionally not enabled yet; cancellation currently operates on all active contributions for the target so issuer ownership and refunds remain deterministic.

## Build

Requirements:

- JDK 21
- Maven 3.9+

Build and test:

```bash
mvn clean test package
```

The packaged JAR is produced under `target/`. GitHub Actions also runs the Java 21 build/test gate on pushes and pull requests to `main`.

Runtime requirements for beta.1:

- Paper 1.21.11
- Vault
- A Vault-compatible economy provider

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for the complete development sequence.

Every roadmap phase has its own specification under [`docs/roadmap/`](docs/roadmap/).

## Planned Development Sequence

- `beta.1` — Core Foundation
- `beta.2` — Contract Engine
- `v0.3.0` — Hunter System
- `v0.4.0` — Intelligence & Tracking
- `v0.5.0` — Heat & Wanted System
- `v0.6.0` — Capture & Target Counterplay
- `v0.7.0` — Social Warfare & Dynamic Hunts
- `v0.8.0` — Integrations & Public API
- `v0.9.0` — Network, Storage & Production Hardening
- `v1.0.0` — Production Release

## Documentation

Start from [`docs/README.md`](docs/README.md).

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Source availability does not mean open-source redistribution or commercial use is permitted. Third-party dependencies remain under their respective licenses.
