# CdrBounty

> **You don't claim bounties. You hunt people.**

CdrBounty is an NPC-driven bounty framework for Paper servers. Normal players interact through a Citizens Bounty Master NPC. Player bounty requests require administrator approval, hunters receive an intentionally inaccurate compass tracker, poor CdrReputation can create system bounties, BetonQuest can create private quest-specific hunts, and active bounties can apply economic consequences through the shop-pricing API.

## Current Release

**CdrBounty `1.0.0 — Production Stable`**

Target: Paper 1.21.11, Java 21, Vault, Citizens, and a Vault-compatible economy provider.

Optional integrations:
- CdrReputation — automatic system bounty thresholds.
- BetonQuest 3.2.0 — quest bounty actions and conditions.
- CdrQuestJournal — journal / safe turn-in through the BetonQuest chain.
- CdrVephilimEconomy RC7+ — higher BUY prices for players with active bounties.
- Geyser/Floodgate — crossplay deployment.

The 1.0.0 release is feature-frozen. Production installation, upgrade, command, permission, and smoke-test instructions are in [`PRODUCTION.md`](PRODUCTION.md).

## Player Flow

Normal players have **no `/bounty` command**.

```text
Citizens Bounty Master NPC
        ↓
Bounty Board
        ↓
Accept Contract
        ↓
Bounty Tracker Compass
        ↓
Approximate target direction
```

Player-created bounty:

```text
NPC Placement Wizard
↓
Vault escrow
↓
PENDING_APPROVAL
↓
/cdrbounty approval
├─ APPROVE → OPEN
└─ REJECT  → full requester refund
```

Only active `OPEN` / `RESERVED` contracts count as wanted for shop pricing.

## Production Safety

CdrBounty 1.0.0 includes the 0.9 hardening baseline:
- config schema `config-version: 9` with safe missing-key migration and pre-migration backup;
- `/cdrbounty diagnose` production health checks;
- SQLite `PRAGMA integrity_check` and orphan-data checks;
- unresolved economy-operation diagnostics;
- Citizens Bounty Master binding health reporting;
- integration presence reporting including Floodgate detection;
- stale/duplicate Bounty Tracker sanitation;
- optional hunter/target minimum-playtime claim gates;
- same-IP, pair cooldown, repeated-pair, and target-survival anti-farm protections;
- recovery-aware escrow/refund/payout persistence.

Upgrade from 0.7.0 or 0.9.0 does **not** require deleting `plugins/CdrBounty/`. Replace the JAR while the server is stopped, keep the data folder, start the server, then run `/cdrbounty diagnose`.

Optional anti-alt gates:

```yaml
claim:
  same-ip-policy: BLOCK
  killer-victim-cooldown-seconds: 3600
  repeated-pair-window-seconds: 86400
  repeated-pair-max-claims: 2
  minimum-hunter-playtime-seconds: 0
  minimum-target-playtime-seconds: 0
  minimum-survival-after-claim-seconds: 300
```

`0` disables the minimum-playtime gate.

## Shop Price Integration

CdrBounty publishes `CdrBountyShopApi` through Bukkit ServicesManager:

```java
boolean isWanted(UUID playerId);
BigDecimal activeBountyTotal(UUID playerId);
double shopBuyMultiplier(UUID playerId);
```

Default BUY multipliers:

```text
Active bounty >= 10,000    → 1.10x
Active bounty >= 50,000    → 1.25x
Active bounty >= 100,000   → 1.50x
Active bounty >= 500,000   → 2.00x
```

CdrVephilimEconomy RC7+ applies:

```text
market price
↓
personal BUY discount
↓
CdrBounty wanted multiplier
↓
FINAL BUY PRICE
```

SELL prices remain unchanged. GUI/form quotes and transaction revalidation must use the same final BUY quote.

## Quest Integration

Native BetonQuest 3.2.0 hooks:

```text
Actions:
cdrbounty_create <questKey> <targetNameOrUuid> <amount>
cdrbounty_cancel <questKey>

Conditions:
cdrbounty_has <questKey>
cdrbounty_active <questKey>
cdrbounty_completed <questKey>
```

Quest contracts are server-funded, `PRIVATE + EXCLUSIVE`, allowlisted to the quest player, auto-accepted, and use the same tracking, pause, anti-farm and settlement pipeline.

## Reputation Auto-Bounty

Default cumulative thresholds:

```text
Reputation <= -1000 → +25,000
Reputation <= -2000 → +25,000
Reputation <= -3500 → +50,000
```

Existing active SYSTEM bounties are topped up instead of duplicated.

## Inaccurate Bounty Tracker

```text
0–300 blocks      → ±25 blocks
301–1000          → ±50 blocks
1001–2000         → ±80 blocks
>2000             → ±120 blocks
```

Cross-world targets produce **Signal Lost**. Offline targets and targets in configured pause worlds such as `lobby` pause the active bounty timer. Duplicate/stale tracker items are sanitized automatically.

## Admin Commands

```text
/cdrbounty approval
/cdrbounty diagnose
/cdrbounty npc bind
/cdrbounty npc info
/cdrbounty npc unbind
/cdrbounty reload
/cdrbounty add <player> <amount>
/cdrbounty remove <player> all
/cdrbounty inspect <player>
/cdrbounty history <player>
/cdrbounty debug
```

See [`PRODUCTION.md`](PRODUCTION.md) for the full permission reference.

## Safety / Persistence

- SQLite-backed contracts, approval, tracking, reputation escalation and quest bindings.
- Recoverable Vault economy intents for player escrow/refunds/payouts.
- Same-IP, repeated pair, pair cooldown and target survival anti-farm checks.
- Optional minimum hunter/target playtime gates.
- Per-player hunting only; no shared party contracts.
- Tracker items are tied to contract UUIDs through PDC.
- Optional integrations remain soft dependencies.

## Roadmap

```text
0.3.0 ✅ Admin Approval
0.4.0 ✅ Inaccurate Compass Tracking
0.5.0 ✅ Reputation Auto-Bounty
0.6.0 ✅ Quest Integration
0.7.0 ✅ Shop Price Integration
0.9.0 ✅ Polish / Crossplay / Anti-Abuse
1.0.0 ✅ Production Stable
```

See [`ROADMAP.md`](ROADMAP.md).

## Build

```bash
mvn clean verify
```

CI validates the production JAR metadata, config schema, license packaging, diagnostics, integrations, final permissions, and confirms that a player `/bounty` command has not been reintroduced.

## License

CdrBounty is distributed under the **MENKIESTES SOFTWARE LICENSE v1.0** included in [`LICENSE`](LICENSE). Third-party dependencies remain under their respective licenses.
