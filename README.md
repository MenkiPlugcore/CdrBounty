# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty hunting framework for Paper servers. The beta.1 escrow/recovery core and beta.2 structured contract engine remain intact, but normal players no longer use bounty commands.

## Current Release

**CdrBounty `0.2.1-beta.2 — NPC-Only Access`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, and a Vault-compatible economy provider.

## Player Flow

Normal players have no `/bounty` command.

```text
Citizens Bounty Master NPC
        ↓ right click
Bounty Master Menu
        ├─ Bounty Board
        │    └─ left click contract = accept
        │       right click contract = abandon
        │
        └─ Pasang Bounty
             └─ input target in chat
                input amount in chat
                → PUBLIC contract created
```

The placement chat wizard cancels its input from public chat. Type `batal` to cancel the wizard.

This interaction model avoids long commands and contract UUID typing, and is suitable for Java + Bedrock/Geyser players.

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

## Contract Engine

- `PUBLIC`, `PRIVATE`, `EXCLUSIVE`, `ANONYMOUS` contract flags remain supported by the domain layer.
- Per-player hunter acceptance; no party/shared quest behavior.
- Private allowlists and exclusive reservation limits remain supported.
- Required-world, forbidden-world, and required-weapon conditions remain supported.
- Existing anti-farm, Vault escrow, payout intents, refund, SQLite persistence, and crash recovery remain unchanged.
- `0.2.1-beta.2` exposes the normal NPC placement wizard as PUBLIC contracts. More advanced contract creation controls can be surfaced through later NPC GUI expansion without restoring player commands.

## Build

```bash
mvn clean verify
```

## Roadmap

After the NPC-only access patch, the next major milestone remains `v0.3.0 — Hunter System`.

See [`ROADMAP.md`](ROADMAP.md) and [`docs/roadmap/`](docs/roadmap/).

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
