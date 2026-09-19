# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is a gameplay-first bounty hunting framework for Minecraft servers. Instead of stopping at `place bounty -> kill target -> claim money`, CdrBounty is designed around contracts, hunter progression, intelligence gathering, heat/wanted status, target counterplay, capture mechanics, dynamic events, and extensibility.

## Project Status

**Phase:** Planning / Roadmap

**Primary target:** Paper 1.21.11, Java 21

No production build is available yet. Development will follow the roadmap documents in this repository.

## Core Design Pillars

1. **Contracts, not simple kill rewards** — public, private, anonymous, exclusive, and conditional hunts.
2. **A real hunter career** — licenses, reputation, ranks, statistics, progression, and leaderboards.
3. **Intel instead of free coordinates** — clues, tracking confidence, compass signals, pulses, and misinformation.
4. **Targets can fight the system** — scramblers, false trails, hunter kills, escapes, and survival bonuses.
5. **Dynamic wanted gameplay** — Heat, automatic system bounties, Most Wanted, Public Enemy, and bounty escalation.
6. **Abuse-resistant economy** — escrow, anti-farming, suspicious-claim scoring, audit logs, configurable payout rules.
7. **Platform for other plugins** — PlaceholderAPI, Vault, Citizens, WorldGuard, APIs, events, and optional integrations.

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

A project license will be added before the first public binary release. Third-party libraries and integrations remain subject to their respective licenses.
