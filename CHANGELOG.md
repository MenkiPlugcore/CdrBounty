# Changelog

## 0.3.0 — Admin Approval

- Added `PENDING_APPROVAL` and `REJECTED` contract states.
- Player-created bounties now enter admin review after escrow instead of opening immediately.
- Added `/cdrbounty approval` for a permission-gated in-game approval GUI.
- Approval GUI shows requester, target, reward, flags, conditions, and waiting time.
- APPROVE transitions the request to OPEN and starts a fresh full bounty duration from approval time.
- REJECT performs a full gross refund to the requester, including the placement-fee portion.
- Rejection refunds use persistent economy intents and remain recoverable across crashes/restarts.
- Startup recovery moves funded interrupted DRAFT contracts to PENDING_APPROVAL instead of opening them.
- Startup reconciliation finalizes pending requests whose rejection refund already completed.
- Added `cdrbounty.admin.approval` permission (default op).
- Added approval-state regression coverage and CI artifact validation.

## 0.2.2-beta.2 — NPC UX Polish

- Added a three-entry Bounty Master menu: Bounty Board, My Contracts, and Pasang Bounty.
- Added My Contracts view for each hunter with direct GUI abandon support.
- Replaced the simple PUBLIC-only placement prompt with a multi-stage NPC placement wizard.
- Added PUBLIC/PRIVATE visibility selection with private hunter allowlists.
- Added EXCLUSIVE and ANONYMOUS toggles without restoring any player commands.
- Added GUI selection for required/forbidden world conditions.
- Added required-weapon selection from the player's current main-hand item.
- Added a final confirmation screen showing target, nominal, placement fee, clean reward, balance, duration, flags, and conditions before escrow is charged.
- Added back/cancel controls throughout the placement flow.
- Added hidden chat-input timeout and `batal` / `cancel` / `kembali` handling.
- Added resumable placement drafts from the Bounty Master menu.
- Added NPC interaction click cooldown to reduce duplicate GUI opens.
- Added default `npc.input-timeout-seconds` and `npc.click-cooldown-millis` configuration.
- Kept all validation and escrow execution inside ContractService/BountyPlacementService.

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
