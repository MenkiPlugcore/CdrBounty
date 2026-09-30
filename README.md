# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty hunting framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC, while player-created bounty requests require administrator approval before becoming active.

## Current Release

**CdrBounty `0.3.0 — Admin Approval`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, and a Vault-compatible economy provider.

## Player Flow

Normal players have **no `/bounty` command**.

```text
Citizens Bounty Master NPC
        ↓ right click
Bounty Master
        ├─ Bounty Board
        │    ├─ left click = accept
        │    └─ right click accepted contract = abandon
        │
        ├─ My Contracts
        │    └─ active contracts accepted by this player
        │
        └─ Pasang Bounty
             ↓
           Target + Amount + Options + Conditions
             ↓
           Confirmation
             ↓
           Vault escrow
             ↓
           PENDING_APPROVAL
             ↓
        Admin review
        ├─ APPROVE → OPEN
        └─ REJECT  → full requester refund
```

Player-created bounties are not visible on the Bounty Board, cannot be accepted, and cannot be claimed while they are `PENDING_APPROVAL`.

## Admin Approval

Administrators with `cdrbounty.admin.approval` can open:

```text
/cdrbounty approval
```

The GUI shows all pending requests with requester, target, reward, flags, conditions, and waiting time. Opening a request provides explicit **APPROVE** and **REJECT** actions.

Approval starts the bounty's active timer from the approval moment. Rejection returns the requester's full gross placement amount, including the placement fee portion that would otherwise have been consumed.

The rejection refund is recovery-safe: a persistent economy intent is written before Vault is called. If the server stops after money moves but before the contract is finalized, startup recovery completes the refund and marks the request rejected.

Funded contracts left in `DRAFT` by an interrupted submission are recovered into `PENDING_APPROVAL`, never silently opened.

## NPC Placement UX

The existing NPC wizard supports:

- `PUBLIC` / `PRIVATE`
- `EXCLUSIVE`
- `ANONYMOUS`
- required world
- forbidden world
- required weapon
- confirmation before escrow
- `batal` / `cancel`
- `kembali` / `back`
- chat-input timeout
- resumable in-memory drafts

## NPC Setup

1. Install Citizens, Vault, an economy provider, and CdrBounty.
2. Create/select the Citizens NPC used as Bounty Master.
3. Stand within 8 blocks and look directly at the NPC.
4. Run:

```text
/cdrbounty npc bind
```

Useful admin commands:

```text
/cdrbounty approval
/cdrbounty npc info
/cdrbounty npc unbind
/cdrbounty reload
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

## Contract / Economy Safety

- Per-player hunter acceptance; no party/shared hunting.
- Pending requests are neither accept-able nor claimable.
- Admin approval is permission-gated.
- Rejection performs a full recoverable refund.
- Existing anti-farm checks remain in the death-settlement path.
- Vault escrow, payout intents, refund handling, SQLite persistence, and crash recovery remain authoritative.
- GUI actions call domain services; UI never bypasses validation.

## Build

```bash
mvn clean verify
```

CI validates the packaged JAR, approval classes, Citizens dependency, version metadata, permission registration, and verifies that player `/bounty` has not been reintroduced.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 → Inaccurate Compass Tracking
0.5.0 → Reputation Auto-Bounty
0.6.0 → Quest Integration
0.7.0 → Shop Price Integration
0.9.0 → Polish / Crossplay / Anti-Abuse
1.0.0 → Production
```

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
