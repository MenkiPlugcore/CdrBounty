# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is a gameplay-first bounty hunting framework for Minecraft servers. Instead of stopping at `place bounty -> kill target -> claim money`, CdrBounty is designed around contracts, hunter progression, intelligence gathering, heat/wanted status, target counterplay, capture mechanics, dynamic events, and extensibility.

## Project Status

**Phase:** `beta.2 — Contract Engine` (active development)

**Primary target:** Paper 1.21.11, Java 21

The beta.1 foundation remains intact: Vault-backed placement/payout, SQLite persistence, crash-aware economy intents, claim locking, anti-farming, expiry/refund processing, and admin tooling. beta.2 adds a structured contract domain and persistent contract storage on top of that foundation.

### beta.2 foundation now present

- Contract UUID + linked escrow contribution.
- PUBLIC / PRIVATE / EXCLUSIVE / ANONYMOUS flags.
- Deterministic lifecycle: DRAFT, OPEN, RESERVED, CLAIMING, COMPLETED, FAILED, EXPIRED, CANCELLED, VOIDED.
- Per-hunter acceptance/abandon state.
- Private allowlists and reservation limits.
- Serializable runtime conditions (required world, forbidden world, required weapon).
- Contract history/audit rows.
- Visibility filtering that does not expose private contract value through player-facing totals.
- Claim preparation designed to reuse beta.1 claim/economy tables so recovery remains compatible.

The command/GUI/death-settlement wiring is being completed in the same beta.2 milestone; do not treat this branch as production-ready yet.

## Existing beta.1 Commands

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

## Build

Requirements:

- JDK 21
- Maven 3.9+

```bash
mvn clean test package
```

Runtime requirements:

- Paper 1.21.11
- Vault
- A Vault-compatible economy provider

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) and [`docs/roadmap/`](docs/roadmap/).

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Source availability does not mean open-source redistribution or commercial use is permitted. Third-party dependencies remain under their respective licenses.
