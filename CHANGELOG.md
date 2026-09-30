# Changelog

## 0.2.0-beta.2 — Contract Engine

- Added persistent structured contracts linked to beta.1 escrow contributions.
- Added PUBLIC, PRIVATE, EXCLUSIVE, and ANONYMOUS flags.
- Added hunter accept/abandon state and reservation limits.
- Added private allowlists and viewer-safe bounty totals.
- Added required-world, forbidden-world, and required-weapon conditions.
- Added `/bounty hunt`, `/bounty contracts`, `/bounty create`, `/bounty accept`, `/bounty abandon`.
- Added contract browser inventory GUI.
- Reworked death settlement to select only eligible legacy/accepted contract contributions while retaining beta.1 anti-farm and recovery.
- Added contract reconciliation after economy recovery.
- Added contract regression tests and JAR validation in CI.
- Updated CI actions for current Node runtime generations.

## 0.1.0-beta.1 — Core Foundation

- Vault-backed bounty placement and payout.
- SQLite persistence.
- Recoverable economy intents and claim locking.
- Anti-farm checks.
- Expiration/refund processing.
- Admin inspection/history/debug tooling.
