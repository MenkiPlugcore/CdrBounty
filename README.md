# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is a gameplay-first bounty hunting framework for Paper servers. It keeps the beta.1 escrow/recovery core and layers structured per-player hunting contracts on top of it.

## Current Release

**CdrBounty `0.2.0-beta.2 — Contract Engine`**

Target: Paper 1.21.11, Java 21, Vault + a Vault-compatible economy provider.

### Contract Engine

- Persistent contract UUID linked to the existing beta.1 escrow contribution.
- `PUBLIC`, `PRIVATE`, `EXCLUSIVE`, and `ANONYMOUS` contract flags.
- Lifecycle: `DRAFT -> OPEN/RESERVED -> CLAIMING -> COMPLETED`, with expiry/cancel/void recovery states.
- Per-player accept and abandon state; party/shared hunting is not used.
- Private hunter allowlists.
- Exclusive contracts reserve one hunter slot.
- Configurable max active contracts per hunter and reservation limit.
- Serializable conditions:
  - required world
  - forbidden world
  - required weapon/material
- Contract browser GUI via `/bounty hunt`.
- Visibility-safe `/bounty view` and `/bounty list`; private contract value is not leaked to unrelated players.
- Death settlement selects only legacy public bounty value plus structured contracts actually accepted by that hunter and whose conditions pass.
- Existing beta.1 anti-farm checks still run before payout.
- Existing beta.1 `claims` + `economy_operations` remain the payout source of truth, preserving crash recovery and double-settlement protection.
- Contract reconciliation completes/reopens state after restart based on the recovered beta.1 claim result.
- Contract history/audit persistence.

Vault currency is the supported beta.2 reward provider. Item/command/XP reward providers are intentionally deferred until the integration/API milestone so they can have explicit idempotency contracts rather than unsafe console-command retries.

## Player Commands

```text
/bounty add <player> <amount>
/bounty view <player>
/bounty list

/bounty hunt
/bounty contracts
/bounty create <player> <amount> [options...]
/bounty accept <contractUuid>
/bounty abandon <contractUuid>
```

Create options:

```text
public
private:Hunter1,Hunter2
anonymous
exclusive
world:<world>
forbidworld:<world>
weapon:<MATERIAL>
```

Example:

```text
/bounty create Bandit123 25000 exclusive anonymous world:worldrp weapon:DIAMOND_SWORD
```

## Administration

The beta.1 admin tools remain available:

```text
/cdrbounty reload
/cdrbounty add <player> <amount>
/cdrbounty remove <player> all
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

## Configuration

```yaml
contract:
  max-active-per-hunter: 3
  public-reservation-limit: 8
  sync-seconds: 30
```

Contract duration currently follows `placement.duration-seconds`, so escrow contribution and contract expiry stay aligned.

## Build

```bash
mvn clean verify
```

The GitHub Actions build also validates that the packaged JAR contains the Contract Engine and settlement classes.

## Roadmap

Next milestone after beta.2 is `v0.3.0 — Hunter System`.

See [`ROADMAP.md`](ROADMAP.md) and [`docs/roadmap/`](docs/roadmap/).

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
