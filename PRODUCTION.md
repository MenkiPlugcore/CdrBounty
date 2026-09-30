# CdrBounty 1.0.0 — Production Guide

CdrBounty 1.0.0 is the feature-frozen production release for Paper 1.21.11 / Java 21.

## Required Runtime

- Paper 1.21.11
- Java 21
- Vault
- a Vault-compatible economy provider
- Citizens

Optional integrations:

- CdrReputation — reputation-triggered SYSTEM bounties
- BetonQuest 3.2.0 — quest bounty actions/conditions
- CdrQuestJournal — journal/safe-turn-in orchestration through BetonQuest
- CdrVephilimEconomy RC7+ — wanted BUY-price surcharge
- Geyser/Floodgate — crossplay deployment

## Fresh Install

1. Stop the server.
2. Install the required dependencies.
3. Put `CdrBounty-1.0.0.jar` in `plugins/`.
4. Start the server once so CdrBounty creates its data/config files.
5. Create or select the Citizens NPC that will be the Bounty Master.
6. Look at that NPC and run `/cdrbounty npc bind`.
7. Run `/cdrbounty diagnose`.
8. Confirm `Production health: OK` before opening bounty gameplay to players.

Normal players do not use a `/bounty` command. Player interaction is through the bound Citizens Bounty Master NPC.

## Upgrade from 0.7.0 / 0.9.0

1. Stop the server completely. Do not use `/reload`.
2. Back up the server or at minimum `plugins/CdrBounty/`.
3. Remove the old CdrBounty JAR from `plugins/`.
4. Put `CdrBounty-1.0.0.jar` in `plugins/`.
5. Keep the existing `plugins/CdrBounty/` folder.
6. Start the server.
7. CdrBounty keeps existing values and migrates missing config keys. A config backup is created when migration is needed.
8. Run `/cdrbounty diagnose` and resolve any reported integrity/orphan/economy/NPC issues before production use.

The 1.0.0 release keeps `config-version: 9`; no destructive configuration reset is required when upgrading from 0.9.0.

## Production Smoke Test

Run these checks after a fresh install or upgrade:

1. `/cdrbounty diagnose` reports SQLite integrity `ok`, zero unexpected orphan rows, zero unresolved economy operations, and Bounty Master `status=OK`.
2. Right-click the bound Bounty Master as a Java player and, if applicable, a Bedrock/Geyser player.
3. Submit a player bounty. Confirm it enters `PENDING_APPROVAL` and is not visible/claimable before approval.
4. Reject one test request and confirm the requester receives the full configured refund.
5. Approve one test request and confirm it appears on the Bounty Board.
6. Accept the bounty and confirm one Bounty Tracker compass is issued.
7. Confirm the tracker is intentionally inaccurate and becomes `Signal Lost` cross-world.
8. Move the target to a configured pause world such as `lobby`; confirm the contract timer pauses and resumes after return.
9. Confirm same-IP / repeated-pair anti-farm rules block the configured abuse case.
10. If CdrReputation is installed, cross a configured threshold and confirm a SYSTEM bounty opens without manual approval.
11. If BetonQuest is installed, test one `cdrbounty_create` quest and confirm completion only after successful bounty settlement.
12. If CdrVephilimEconomy RC7+ is installed, confirm active wanted players see and are charged the same increased BUY price; SELL must remain unchanged.
13. Restart the server with active/pending data and run `/cdrbounty diagnose` again.

## Admin Commands

- `/cdrbounty approval` — open pending player bounty approval GUI
- `/cdrbounty diagnose` — production health/integrity diagnostics
- `/cdrbounty npc bind` — bind the Citizens NPC currently targeted by the admin
- `/cdrbounty npc info` — inspect current Bounty Master binding
- `/cdrbounty npc unbind` — remove the Bounty Master binding
- `/cdrbounty reload` — reload runtime-safe configuration
- `/cdrbounty add <player> <amount>` — administrator bounty contribution
- `/cdrbounty remove <player> all` — cancel active player-funded contributions for a target according to refund policy
- `/cdrbounty inspect <player>` — inspect active bounty value/contributions
- `/cdrbounty history <player>` — recent claim history
- `/cdrbounty debug` — lower-level runtime debug summary

## Permission Reference

Player permissions (default true):

- `cdrbounty.contract.create`
- `cdrbounty.contract.accept`
- `cdrbounty.contract.abandon`
- `cdrbounty.contract.private`
- `cdrbounty.contract.anonymous`
- `cdrbounty.contract.exclusive`

Administrator permissions (default op):

- `cdrbounty.admin`
- `cdrbounty.admin.reload`
- `cdrbounty.admin.modify`
- `cdrbounty.admin.inspect`
- `cdrbounty.admin.debug`
- `cdrbounty.admin.diagnose`
- `cdrbounty.admin.contracts`
- `cdrbounty.admin.npc`
- `cdrbounty.admin.approval`
- `cdrbounty.bypass.antifarm`

`cdrbounty.admin.diagnose` is separate from `cdrbounty.admin.debug` so a moderator can be granted production health checks without receiving debug access.

## Core Production Rules

- Player-created bounties require admin approval.
- SYSTEM and trusted quest bounties can bypass manual approval.
- Hunting contracts are per-player, not party-shared.
- Tracking is approximate and does not expose exact live coordinates.
- Target offline/safe-world periods pause the bounty timer.
- Pending approval requests do not make a player wanted for shop pricing.
- Shop surcharge affects BUY only; SELL remains unchanged.
- Reputation is external state; CdrBounty reacts to it rather than owning reputation.
- BetonQuest remains quest authority; CdrQuestJournal remains journal/safe-turn-in authority.
- Economy settlement is server-authoritative and uses recovery-aware persistence.

## Recommended Deployment Procedure

Always upgrade by stopping the server, replacing the JAR, and starting the server again. Avoid Bukkit/Paper `/reload` for production plugin upgrades.

After every version or infrastructure change, run `/cdrbounty diagnose` before allowing bounty transactions.
