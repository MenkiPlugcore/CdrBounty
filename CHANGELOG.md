# Changelog

## 0.2.1-beta.2 — NPC-Only Access

- Removed the player-facing `/bounty` command from plugin registration.
- Added Citizens as a required runtime dependency.
- Added a persistent Bounty Master NPC binding in `npc.yml`.
- Added `/cdrbounty npc bind|info|unbind` for administrators.
- Right-clicking the bound NPC opens the Bounty Master menu.
- Contract browsing, accepting, and abandoning stay GUI-driven.
- Added NPC bounty placement flow using GUI + private chat prompts for target and amount.
- Chat input used by the placement wizard is cancelled from public chat.
- NPC placement creates a standard PUBLIC structured contract and reuses the beta.2 escrow/recovery pipeline.
- Designed for both Java and Bedrock/Geyser interaction without player commands or contract UUID typing.

## 0.2.0-beta.2 — Contract Engine

- Added persistent structured contracts linked to beta.1 escrow contributions.
- Added PUBLIC, PRIVATE, EXCLUSIVE, and ANONYMOUS flags.
- Added hunter accept/abandon state and reservation limits.
- Added private allowlists and viewer-safe bounty totals.
- Added required-world, forbidden-world, and required-weapon conditions.
- Added contract browser inventory GUI.
- Reworked death settlement to select only eligible legacy/accepted contract contributions while retaining beta.1 anti-farm and recovery.
- Added contract reconciliation after economy recovery.
- Added contract regression tests and JAR validation in CI.

## 0.1.0-beta.1 — Core Foundation

- Vault-backed bounty placement and payout.
- SQLite persistence.
- Recoverable economy intents and claim locking.
- Anti-farm checks.
- Expiration/refund processing.
- Admin inspection/history/debug tooling.
