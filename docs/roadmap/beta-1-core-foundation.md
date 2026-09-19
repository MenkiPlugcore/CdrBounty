# CdrBounty beta.1 — Core Foundation

**Status:** In Development / Core Implemented  
**Primary target:** Paper 1.21.11 / Java 21  
**Artifact version:** `0.1.0-beta.1`

## Goal

Build the smallest safe bounty system that can be trusted with real server economy data. This phase establishes lifecycle, persistence, validation, configuration, audit, crash-recovery, and regression-test foundations used by every later roadmap.

## Implementation Snapshot

Implemented in the repository:

- Java 21 Maven project for Paper 1.21.11.
- Vault economy adapter.
- SQLite persistence with WAL mode and dedicated database workers.
- Contribution-level bounty stacking.
- Explicit bounty lifecycle state machine.
- Two-phase placement using persisted economy-operation intents.
- Claim locking (`ACTIVE -> CLAIMING -> CLAIMED`) before payout.
- Crash-aware withdrawal, payout, and refund reconciliation.
- Basic persistent anti-farming.
- Expiration scan and configurable refund behavior.
- Admin add, cancel-all, inspect, history, debug, and reload controls.
- MENKIESTES SOFTWARE LICENSE v1.0.
- GitHub Actions Java 21 `mvn clean test package` build gate.
- Initial lifecycle and money-math regression tests.

Still required before marking beta.1 complete:

- CI must be green on the final beta.1 head commit.
- Staging-server smoke test against the actual Vault economy provider used by the server.
- Restart/crash scenario verification with a real Paper server.
- Concurrent placement/death stress verification.
- SQLite lock/failure simulation.
- Partial-value `/cdrbounty remove <player> <amount>` is intentionally deferred inside beta.1 until contribution ownership/refund splitting is implemented safely; `remove <player> all` is available now.

## Player Flow

1. Player places a bounty on another player.
2. CdrBounty writes a `PENDING` contribution and a withdrawal intent before touching Vault.
3. Reward is removed from the issuer.
4. The contribution becomes `ACTIVE` and persists across restart.
5. A valid killer eliminates the target.
6. CdrBounty validates anti-farm and world policy.
7. Eligible contributions are atomically moved to `CLAIMING` and a payout intent is persisted.
8. Vault payout occurs.
9. The claim becomes `CLAIMED` exactly once and pair history is updated.
10. Expired or administratively cancelled contributions follow configured refund policy.

If the process stops between a persisted economy intent and its database completion, startup recovery compares the recorded pre-transaction balance with the expected post-transaction balance. Ambiguous operations are frozen instead of blindly replayed.

## Bounty Lifecycle

Implemented states:

- `PENDING`
- `ACTIVE`
- `CLAIMING`
- `CLAIMED`
- `EXPIRED`
- `CANCELLED`
- `REFUNDED`
- `VOIDED`

Domain transitions are validated. Invalid transitions are rejected rather than silently accepted.

Core transitions:

```text
PENDING -> ACTIVE | CANCELLED | VOIDED
ACTIVE -> CLAIMING | EXPIRED | CANCELLED | VOIDED
CLAIMING -> CLAIMED | ACTIVE | VOIDED
EXPIRED -> REFUNDED | VOIDED
CANCELLED -> REFUNDED | VOIDED
CLAIMED / REFUNDED / VOIDED -> terminal
```

## Economy & Escrow

Implemented:

- Vault economy provider discovery.
- Minimum/maximum bounty values.
- Configurable placement fee percentage.
- `BigDecimal` normalization for deterministic internal money calculations.
- Contribution escrow ledger.
- Unique economy-operation IDs.
- Operation states such as `INTENT`, `COMMITTED`, `FAILED`, and `AMBIGUOUS`.
- Refund policy for expiration/cancellation.
- Startup reconciliation for interrupted withdrawal, payout, and refund operations.

Important limitation: Vault does not expose a cross-provider ACID transaction primitive. CdrBounty therefore persists an intent and pre-transaction balance before calling Vault, then reconciles interrupted operations on startup. If the observed balance cannot prove whether the external economy transaction happened, CdrBounty marks the operation ambiguous instead of risking double credit/debit.

## Placement

Implemented commands:

- `/bounty`
- `/bounty add <player> <amount>`
- `/bounty view <player>`
- `/bounty list`

Implemented configurable rules:

- Self-bounty placement.
- Offline targets.
- Minimum issuer playtime.
- Minimum/maximum bounty values.
- Maximum active bounty per target.
- Stacking multiple contributions on one target.
- Placement world blacklist.
- Bounty duration.

Unknown players that have never joined are rejected.

## Claim Validation

Implemented validation includes:

- Killer must resolve to a player.
- Victim must have active, non-expired contributions.
- Killer and victim UUID must differ.
- Claim preparation atomically locks contributions into `CLAIMING`.
- A locked/claimed contribution cannot be paid by a second death event.
- Claim world policy.
- Basic anti-farming policy.
- Economy payout result validation.

## Basic Anti-Farming

Implemented safeguards:

- Same killer/victim cooldown.
- Same-IP blocking (`BLOCK`) or disabled comparison (`IGNORE`).
- Repeated-pair detection window and limit.
- Minimum target survival period after a previous paid claim.
- `cdrbounty.bypass.antifarm` for authorized testing.

Advanced fraud scoring remains deferred to later milestones.

## Persistence

Storage provider for beta.1:

- SQLite.

Persistent entities/tables include:

- Player identity cache.
- Individual bounty contributions.
- Economy-operation ledger.
- Claim history.
- Anti-farm pair history.
- Administrative audit log.

UUID is authoritative player identity. Names are metadata only.

SQLite behavior is configured through `storage.yml` and defaults to WAL with a bounded busy timeout. Routine repository work is serialized on dedicated worker threads rather than the Paper main thread.

## Configuration

Implemented files:

- `config.yml` — economy, placement, anti-farm, claim, refund, runtime behavior.
- `messages.yml` — player/admin text.
- `storage.yml` — SQLite provider settings.
- `gui.yml` — reserved; GUI remains disabled in beta.1.

Configuration validates dangerous values and fails plugin startup rather than silently accepting invalid economy/storage settings. Storage path changes require restart rather than hot switching a live database.

## Administrative Controls

Implemented:

- `/cdrbounty reload`
- `/cdrbounty add <player> <amount>`
- `/cdrbounty remove <player> all`
- `/cdrbounty inspect <player>`
- `/cdrbounty history <player>`
- `/cdrbounty debug`

`/cdrbounty add` creates a system-funded bounty and writes an audit entry. System-funded contributions are never refunded to a fake/system account.

`/cdrbounty remove <player> all` processes each active contribution according to the configured cancellation refund policy. Exact partial-value removal remains pending because it must preserve per-issuer ownership and refund accounting.

## Permissions

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

## Architecture

Current packages/modules:

- `core`
- `bounty`
- `economy`
- `storage`
- `claim`
- `antifarm`
- `command`
- `config`

Domain lifecycle and money rules are kept out of command executors and event listeners.

## Crash-Recovery Model

### Placement

```text
DB: contribution PENDING + WITHDRAWAL INTENT
        ↓
Vault withdrawal
        ↓
DB: contribution ACTIVE + operation COMMITTED
```

At startup:

- Current balance equals recorded pre-balance: withdrawal did not occur -> contribution is voided.
- Current balance equals expected post-withdrawal balance: contribution is activated without another withdrawal.
- Anything else: operation becomes `AMBIGUOUS` for admin review.

### Claim

```text
DB: ACTIVE contributions -> CLAIMING + PAYOUT INTENT
        ↓
Vault deposit to killer
        ↓
DB: CLAIMING -> CLAIMED + operation COMMITTED
```

An interrupted payout is reconciled from pre/expected balance. CdrBounty does not blindly replay a payout whose outcome is uncertain.

### Refund

Expiration/admin cancellation uses the same persisted-intent pattern. Successful interrupted refunds are finalized without paying twice.

## Failure Cases / Verification Matrix

Must be covered before beta.1 completion:

- [ ] Server stops immediately after money withdrawal but before bounty activation.
- [ ] Server stops during payout.
- [ ] Server stops during refund.
- [ ] Duplicate/player death edge case cannot pay twice.
- [ ] Economy provider refuses withdrawal.
- [ ] Economy provider refuses payout.
- [ ] Economy provider refuses refund.
- [ ] Target changes username.
- [ ] Target remains offline for long periods.
- [ ] Bounty expires while server is offline.
- [ ] Multiple players place bounty on one target concurrently.
- [ ] Admin cancellation races with death processing.
- [ ] SQLite is temporarily unavailable/locked.
- [x] State-machine unit tests added.
- [x] Money normalization/fee tests added.
- [x] GitHub Actions Java 21 build/test gate added.

## Acceptance Criteria

beta.1 is complete only when:

- [x] Player placement flow and balance withdrawal are implemented.
- [x] Multiple contributions stack with separate ownership records.
- [x] Active bounties persist in SQLite.
- [x] Expiration scan is restart-safe and deterministic from timestamps.
- [x] Valid player kill payout flow is implemented.
- [x] Claim locking prevents a second payout path for the same contribution.
- [x] Basic anti-farm policy persists across restart.
- [x] Expiration/cancellation refund policy is implemented.
- [x] Admin system-funded additions are audited.
- [x] Routine database operations are moved off the main server thread.
- [ ] Final CI build/test is green.
- [ ] Staging smoke test is complete.
- [ ] Failure/concurrency matrix above is verified.
- [ ] Partial-value admin removal is implemented or explicitly moved to a later milestone with a compatibility note.

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
