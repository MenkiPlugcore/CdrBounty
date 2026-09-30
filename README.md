# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty hunting framework for Paper servers. The beta.1 escrow/recovery core and beta.2 structured contract engine remain intact, while normal players interact entirely through a Citizens Bounty Master NPC.

## Current Release

**CdrBounty `0.2.2-beta.2 — NPC UX Polish`**

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
           Target
             ↓
           Amount
             ↓
           Contract Options
             ├─ PUBLIC / PRIVATE
             ├─ EXCLUSIVE on/off
             └─ ANONYMOUS on/off
             ↓
           Conditions
             ├─ Any / Required / Forbidden World
             └─ Required Weapon
             ↓
           Confirmation
             ↓
           Vault escrow + contract creation
```

The chat steps are private: wizard input is cancelled from public chat. Supported control words are `batal` / `cancel` and, where applicable, `kembali` / `back`.

Chat-input stages expire after 60 seconds by default. Closing a GUI does not discard the current in-memory draft; clicking the Bounty Master again exposes **Lanjutkan Draft**. Drafts are not persisted across player disconnects or server restarts.

## Placement Confirmation

CdrBounty does not charge the issuer while they are still configuring the draft. The final confirmation screen shows:

- target
- gross bounty amount
- placement fee
- clean bounty reward
- issuer balance
- contract flags
- private hunter count
- contract duration
- kill conditions

Only pressing **CONFIRM BOUNTY** calls the existing ContractService/BountyPlacementService escrow pipeline.

## Contract Options

The NPC wizard exposes the beta.2 contract engine without restoring player commands:

- `PUBLIC` — visible and acceptable by eligible hunters.
- `PRIVATE` — only the issuer-selected hunter allowlist may participate.
- `EXCLUSIVE` — reserves the contract to one hunter.
- `ANONYMOUS` — hides the issuer from normal viewers.

Flags can be combined where valid, such as an anonymous exclusive bounty.

### Conditions

Current NPC-selectable conditions:

- **Required World** — target kill must happen in the selected world.
- **Forbidden World** — target kill must not happen in the selected world.
- **Required Weapon** — uses the material currently held in the issuer's main hand.

Condition validation still happens in the contract/claim domain rather than trusting the GUI.

## NPC Setup

1. Install Citizens, Vault, an economy provider, and CdrBounty.
2. Create/select the Citizens NPC you want to use as the Bounty Master.
3. Stand within 8 blocks and look directly at the NPC.
4. Run:

```text
/cdrbounty npc bind
```

Useful admin commands:

```text
/cdrbounty npc info
/cdrbounty npc unbind
/cdrbounty reload
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

The binding is persisted in `plugins/CdrBounty/npc.yml` using Citizens NPC UUID + ID.

## Configuration

```yaml
npc:
  input-timeout-seconds: 60
  click-cooldown-millis: 600
```

`input-timeout-seconds` applies while the wizard is waiting for chat input. `click-cooldown-millis` prevents duplicate menu opens from repeated Citizens interactions.

## Contract / Economy Safety

- Per-player hunter acceptance; no party/shared hunting.
- Private contract value is not exposed to unrelated players.
- Existing anti-farm checks remain in the death-settlement path.
- Vault escrow, payout intents, refund handling, SQLite persistence, and crash recovery still use the proven beta.1 pipeline.
- Contract payout still requires the hunter to have accepted the structured contract and to satisfy all configured conditions.
- GUI actions call the same domain services as the underlying contract engine; the GUI does not bypass validation.

## Build

```bash
mvn clean verify
```

The CI build validates the packaged JAR, Citizens dependency, NPC UX classes, version metadata, configuration defaults, and verifies that a player-facing `/bounty` command has not been reintroduced.

## Roadmap

The next major milestone is **`v0.3.0 — Hunter System`**: hunter profiles, statistics, rank/progression, streaks, and hunting performance while keeping bounty access NPC-only.

See [`ROADMAP.md`](ROADMAP.md) and [`docs/roadmap/`](docs/roadmap/).

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
