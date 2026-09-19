# CdrBounty beta.2 — Contract Engine

**Status:** Planned

## Goal

Turn a bounty from a single number into a structured hunting contract with ownership, visibility, reservation, conditions, expiry, and reward composition.

## Contract Types

Initial planned types:

- `PUBLIC` — any eligible player may claim.
- `PRIVATE` — only explicitly allowed hunters may participate.
- `EXCLUSIVE` — one hunter or team reserves the contract.
- `ANONYMOUS` — issuer identity is hidden from normal players.
- `ASSASSINATION` — requires configured objectives/conditions.

Types should be composable where safe, for example an anonymous exclusive contract.

## Contract Data Model

Minimum fields:

- Contract UUID.
- Target UUID.
- Issuer UUID or system issuer.
- Type/flags.
- State.
- Created time.
- Activation time.
- Expiration time.
- Reward definition.
- Accepted hunter(s).
- Reservation limit.
- Conditions.
- Completion evidence.
- Settlement reference.

## Lifecycle

Planned states:

- `DRAFT`
- `OPEN`
- `RESERVED`
- `IN_PROGRESS`
- `COMPLETED`
- `FAILED`
- `EXPIRED`
- `CANCELLED`
- `VOIDED`

State transitions must remain deterministic and idempotent.

## Condition Framework

The condition system should be extensible rather than hard-coded into each contract type.

Initial candidates:

- Required world.
- Forbidden world.
- Required weapon/material category.
- Time limit after acceptance.
- Solo completion.
- No assistance.
- Minimum/maximum distance.
- Target health threshold at engagement start.
- Hunter survival requirement.

Each condition should expose:

- Validation at creation time.
- Runtime tracking requirements.
- Completion result.
- Human-readable description.
- Serialization form.

## Reward Composition

A contract reward may contain one or more providers:

- Vault currency.
- ItemStack rewards.
- Console commands.
- Experience.

Future integrations may add custom reward providers through the public API.

Every reward component must participate in settlement tracking so partial failures are detectable and recoverable.

## Contract Acceptance

Planned commands/UI actions:

- `/bounty hunt`
- `/bounty accept <contract>`
- `/bounty abandon <contract>`
- `/bounty contracts`
- `/bounty create <player>`

Rules include:

- Maximum active contracts per hunter.
- Exclusive reservation timeout.
- Optional abandonment penalty.
- Rank requirements reserved for v0.3.0.
- Prevent issuer/target from accepting where inappropriate.

## GUI

Contract browser should expose:

- Target.
- Reward.
- Contract type.
- Remaining time.
- Hunter slot usage.
- Conditions.
- Visibility status.
- Accept/abandon state.

GUI clicks must call the same domain services as commands; GUI logic must not bypass validation.

## Permissions

Planned additions:

- `cdrbounty.contract.create`
- `cdrbounty.contract.accept`
- `cdrbounty.contract.abandon`
- `cdrbounty.contract.private`
- `cdrbounty.contract.anonymous`
- `cdrbounty.contract.exclusive`
- `cdrbounty.admin.contracts`

## Events

Internal event design should prepare for later public API exposure:

- Contract created.
- Contract opened.
- Contract accepted.
- Contract abandoned.
- Contract completed.
- Contract failed.
- Contract expired.
- Contract cancelled.

Events that allow cancellation must document exactly when cancellation is still safe relative to escrow settlement.

## Persistence

New persistent state:

- Contracts.
- Contract participants.
- Conditions and progress.
- Reward components.
- Completion evidence.
- Reservation/abandonment history.

Migration from beta.1 data must preserve all existing bounty value and ownership information.

## Acceptance Criteria

beta.2 is complete when:

- Public, private, anonymous, and exclusive contracts can be created and completed.
- Reservation limits are enforced consistently across command and GUI paths.
- Contract expiry survives restart.
- Conditions serialize and restore correctly.
- At least the initial condition set has regression coverage.
- Reward settlement cannot execute twice.
- Partial reward failure is recorded and recoverable by admin tooling.
- Existing beta.1 bounties migrate without value loss.
- Contract history clearly records issuer, hunter, target, conditions, and result subject to visibility permissions.

## Explicitly Out of Scope

- Hunter rank/reputation progression.
- Intel purchases and tracking compass.
- Heat-generated contracts.
- Alive capture contracts.
- Team contracts.
- Public API stability guarantee.
