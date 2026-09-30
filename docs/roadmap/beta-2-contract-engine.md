# CdrBounty beta.2 — Contract Engine

**Status:** Implemented — `0.2.0-beta.2`

## Goal

Turn beta.1 bounty escrow into a structured, persistent hunting contract without replacing the proven beta.1 economy/recovery pipeline.

## Implemented Contract Flags

- `PUBLIC` — visible in the normal board; hunter must accept before structured contract value becomes claimable.
- `PRIVATE` — visible/acceptable only to allowlisted hunters, issuer, or authorized admin.
- `EXCLUSIVE` — reservation limit is one hunter and the contract transitions to `RESERVED` while accepted.
- `ANONYMOUS` — issuer identity is hidden from normal viewers.

Assassination-by-kill is the default beta.2 completion semantic rather than a separate flag. Capture and other completion modes remain later milestones.

## Persistence

Structured contracts link directly to beta.1 `bounty_contributions` through `contribution_id`. New tables are:

- `contracts`
- `contract_hunters`
- `contract_allowlist`
- `contract_conditions`
- `contract_history`

This means existing beta.1 bounty value is not migrated into a second economy system. Legacy beta.1 contributions with no contract remain valid public kill bounties.

## Lifecycle

Implemented states:

- `DRAFT`
- `OPEN`
- `RESERVED`
- `CLAIMING`
- `COMPLETED`
- `FAILED`
- `EXPIRED`
- `CANCELLED`
- `VOIDED`

Creation is recovery-aware: the contract DRAFT is persisted before the linked escrow placement. If escrow becomes ACTIVE but opening the contract is interrupted, reconciliation can promote the DRAFT to OPEN after restart.

Claim settlement reuses beta.1 `claims` and `economy_operations`, so payout intent, balance evidence, and recovery remain centralized.

## Conditions

Initial serializable conditions implemented with regression tests:

- `REQUIRED_WORLD`
- `FORBIDDEN_WORLD`
- `REQUIRED_WEAPON`

Conditions are evaluated when a hunter kills the target. A structured contribution is selected for settlement only if the hunter accepted that contract and all conditions pass.

## Acceptance / Reservation

Implemented:

- `/bounty hunt` GUI browser.
- `/bounty contracts` text browser.
- `/bounty accept <contractUuid>`.
- `/bounty abandon <contractUuid>`.
- Configurable active-contract limit per hunter.
- Configurable reservation limit for non-exclusive contracts.
- Exclusive single-hunter reservation.
- Issuer and target cannot accept their own contract.
- Private allowlist enforcement.

Command and GUI paths both call the same `ContractService` domain methods.

## Creation

```text
/bounty create <player> <amount> [options...]
```

Supported options:

- `public`
- `private:Hunter1,Hunter2`
- `anonymous`
- `exclusive`
- `world:<world>`
- `forbidworld:<world>`
- `weapon:<MATERIAL>`

The linked escrow still applies beta.1 amount, fee, stacking, maximum target value, world, playtime, and Vault balance validation.

## Visibility Safety

Player-facing `/bounty view`, `/bounty list`, and the contract browser use viewer-aware totals. Private contract value is not exposed to unrelated players. Admins with `cdrbounty.admin.contracts` can inspect all visible contract state.

## Settlement / Recovery

The death pipeline is:

```text
Player death
 -> eligible legacy + accepted structured contributions
 -> beta.1 AntiFarmService
 -> lock selected contributions + contracts
 -> create standard PREPARED claim + payout INTENT
 -> Vault deposit
 -> beta.1 completeClaim
 -> contract COMPLETED
```

On deposit failure, beta.1 restores locked contributions and beta.2 releases the contract. If a server crash occurs after money moves, beta.1 RecoveryService resolves the economy intent first; beta.2 reconciliation then derives the correct contract state from the recovered claim.

This preserves beta.1 double-settlement protection instead of creating a parallel payout implementation.

## Reward Scope

`0.2.0-beta.2` supports the existing Vault currency escrow provider. ItemStack, console command, and XP reward composition are deferred to the later integration/API milestone. Those providers require explicit idempotency/recovery contracts; blindly replaying external command rewards after a crash would be unsafe.

## Permissions

- `cdrbounty.contract.create`
- `cdrbounty.contract.accept`
- `cdrbounty.contract.abandon`
- `cdrbounty.contract.private`
- `cdrbounty.contract.anonymous`
- `cdrbounty.contract.exclusive`
- `cdrbounty.admin.contracts`

## Explicitly Out of Scope

- Hunter rank/reputation progression (`v0.3.0`).
- Intel purchases/tracking (`v0.4.0`).
- Heat/Wanted generated contracts (`v0.5.0`).
- Alive capture (`v0.6.0`).
- Team/party contracts.
- Stable public API guarantees (`v0.8.0`).
