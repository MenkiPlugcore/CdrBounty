# CdrBounty beta.1 — Core Foundation

**Status:** Planned  
**Primary target:** Paper 1.21.11 / Java 21

## Goal

Build the smallest safe bounty system that can be trusted with real server economy data. This phase establishes lifecycle, persistence, validation, configuration, and audit foundations used by every later roadmap.

## Player Flow

1. Player places a bounty on another player.
2. Reward is removed from the issuer and moved into plugin-controlled escrow.
3. Bounty becomes active and persists across restart.
4. A valid killer eliminates the target.
5. CdrBounty validates the claim.
6. Reward is settled exactly once.
7. Claim is recorded in history/audit storage.

Expired or administratively cancelled bounties must follow an explicit configurable refund policy.

## Functional Scope

### Bounty Lifecycle

Planned states:

- `PENDING`
- `ACTIVE`
- `CLAIMING`
- `CLAIMED`
- `EXPIRED`
- `CANCELLED`
- `REFUNDED`
- `VOIDED`

Transitions must be validated. Invalid state transitions should be rejected and logged rather than silently accepted.

### Economy & Escrow

- Vault economy adapter.
- Minimum/maximum bounty values.
- Configurable placement fee/tax.
- Plugin escrow ledger.
- Settlement records with unique transaction IDs.
- Refund policy for expiration/cancellation.
- Protection against double payout after duplicate death events, restart, or repeated processing.

### Placement

Initial command concepts:

- `/bounty`
- `/bounty add <player> <amount>`
- `/bounty view <player>`
- `/bounty list`

Rules should be configurable for:

- Self-bounty placement.
- Offline targets.
- Minimum playtime.
- Minimum balance.
- Maximum active bounty value.
- Stacking multiple placements onto one target.
- Blacklisted worlds.

### Claim Validation

At minimum validate:

- Killer is a player unless explicitly supported otherwise.
- Target had an active bounty at death resolution time.
- Killer and victim are not the same UUID.
- Claim has not already been settled.
- World is eligible.
- Basic anti-farming policy passes.
- Reward destination is available.

### Basic Anti-Farming

Initial safeguards:

- Same killer/victim cooldown.
- Same IP blocking or configurable penalty where server policy allows it.
- Repeated-pair detection window.
- Optional minimum target survival time after previous claim.
- Admin bypass permission for testing.

Advanced fraud scoring is intentionally deferred.

### Persistence

Initial storage provider:

- SQLite.

Minimum persistent entities:

- Player identity cache.
- Active bounty aggregate.
- Individual bounty contributions.
- Escrow transactions.
- Claim history.
- Anti-farm pair history.
- Administrative adjustments.

All records should use UUID as primary player identity. Names are cached metadata only.

### Configuration

Planned files:

- `config.yml` — core behavior.
- `messages.yml` — player/admin text.
- `storage.yml` — database provider settings.
- `gui.yml` — reserved for GUI layout where needed.

Configuration must fail safely: malformed values should be reported clearly rather than defaulting to dangerous economy behavior.

### Administrative Controls

Initial concepts:

- `/cdrbounty reload`
- `/cdrbounty add <player> <amount>`
- `/cdrbounty remove <player> [amount|all]`
- `/cdrbounty inspect <player>`
- `/cdrbounty history <player>`
- `/cdrbounty debug`

Every economy-mutating admin action must create an audit record.

## Planned Permissions

- `cdrbounty.use`
- `cdrbounty.add`
- `cdrbounty.view`
- `cdrbounty.list`
- `cdrbounty.admin`
- `cdrbounty.admin.reload`
- `cdrbounty.admin.modify`
- `cdrbounty.admin.inspect`
- `cdrbounty.admin.debug`
- `cdrbounty.bypass.antifarm`

Exact permission names may be refined before implementation, but changes must be reflected here.

## Architecture Expectations

Recommended initial modules/packages:

- `core`
- `bounty`
- `economy`
- `storage`
- `claim`
- `antifarm`
- `command`
- `config`
- `audit`
- `integration`

Domain logic should not be embedded directly in command executors or event listeners.

## Failure Cases That Must Be Tested

- Server stops immediately after money withdrawal but before bounty activation.
- Server stops during payout.
- Player dies twice through duplicate/event edge cases.
- Economy provider fails to deposit reward.
- Target changes username.
- Target is offline for long periods.
- Bounty expires while server is offline.
- Multiple players place bounty on the same target concurrently.
- Admin removes bounty during combat/death processing.
- SQLite is temporarily unavailable or locked.

## Acceptance Criteria

beta.1 is complete only when:

- A player can place a bounty and see the deducted balance.
- Multiple placements can stack without losing contribution ownership/history.
- Active bounty survives clean restart.
- Expiration is deterministic after restart.
- Valid player kill pays the configured reward.
- One death cannot pay twice.
- Invalid/blocked farming claims do not pay.
- Refund/cancellation policy behaves exactly as configured.
- Admin adjustments are audited.
- Core operations produce no synchronous database stalls under normal load testing.
- Automated regression tests cover lifecycle and settlement invariants.

## Explicitly Out of Scope

Not part of beta.1:

- Hunter licenses/ranks.
- Contract reservations.
- Conditional contract objectives.
- Intel and tracking.
- Heat/Most Wanted.
- Capture/jail.
- Bounty teams/chains.
- MySQL/network synchronization.
- Public developer API stability guarantees.

These systems depend on a safe bounty core and must not be pulled forward prematurely.
