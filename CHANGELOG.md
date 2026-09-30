# Changelog

## 0.5.0 — Reputation Auto-Bounty

- Added optional runtime integration with CdrReputation through Bukkit ServicesManager and `ReputationChangeEvent`.
- Kept CdrReputation as a soft dependency; CdrBounty remains startable without it.
- Added configurable negative reputation thresholds that create system-funded PUBLIC bounty contracts automatically.
- System bounties bypass player-request admin approval and open immediately.
- Added cumulative threshold escalation so one large reputation drop can cross multiple configured tiers in one evaluation.
- Later threshold crossings now top up the same active `OPEN` / `RESERVED` SYSTEM contract instead of creating duplicate Bounty Board cards; its deadline is refreshed when necessary.
- A new SYSTEM contract is created only when the previously tracked system bounty is no longer active.
- Added persistent `reputation_auto_bounty_state` to prevent duplicate bounty issuance while a player remains in the same reputation tier.
- Added configurable `reset-threshold`; recovering above it rearms future automatic escalation.
- System bounty contribution, structured contract, escalation state, contract history, and audit record are written atomically in one SQLite transaction.
- Active SYSTEM bounty escalation updates contribution value, contract reward, threshold state, history, and audit atomically.
- Added startup/join reputation reconciliation for online players.
- Added optional system-bounty broadcast and direct target notification.
- Bounty Board now labels reputation-generated bounties with `Issuer: SYSTEM`.
- Reputation-generated contracts use the existing NPC board, hunter acceptance, inaccurate tracker, pause timer, anti-farm checks, and claim settlement.
- Added pure policy regression tests covering cumulative drops, escalation, duplicate prevention, and reset behavior.
- Added default thresholds: `-1000 +25k`, `-2000 +25k`, and `-3500 +50k`.

## 0.4.0 — Inaccurate Compass Tracking

- Added a per-contract **Bounty Tracker** compass when a hunter accepts a bounty.
- Tracker direction uses a regenerated random offset instead of exact target coordinates.
- Added configurable accuracy bands; default offset grows from ±25 blocks nearby to ±120 blocks at very long distance.
- Tracker reports `Signal Lost` when the target is offline, inside a configured safe/pause world, or in another world/dimension.
- Added left-click tracker reissue from accepted entries in **My Contracts** / Bounty Board.
- Abandoning a contract immediately removes its tracker; stale tracker items are cleaned during periodic refresh.
- Added persistent `contract_tracking_pause` heartbeat storage.
- Contract and escrow contribution deadlines now pause together while the target is offline or inside configured pause worlds such as `lobby`.
- Join, quit, and world-change events synchronize target availability immediately, with a periodic heartbeat as recovery fallback.
- Removed the immediate startup expiration scan so the tracking pause heartbeat initializes before the first scheduled expiration pass.
- Added `tracking.update-seconds`, `tracking.pause-scan-seconds`, `tracking.pause-worlds`, and configurable accuracy settings.
- Added tracker accuracy regression tests and CI validation for the tracking classes/configuration.

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
