package store.cadera.cdrbounty.contract;

import store.cadera.cdrbounty.config.PluginSettings;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SQLiteContractRepository implements ContractRepository {
    private final PluginSettings settings;
    private final ExecutorService executor;
    private Connection connection;

    public SQLiteContractRepository(PluginSettings settings) {
        this.settings = settings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CdrBounty-Contracts-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void initialize() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + settings.sqliteFile());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + settings.sqliteBusyTimeoutMs());
            statement.execute("PRAGMA journal_mode = " + settings.sqliteJournalMode());
            statement.execute("PRAGMA synchronous = " + settings.sqliteSynchronous());
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contracts (
                      id TEXT PRIMARY KEY,
                      contribution_id TEXT NOT NULL UNIQUE,
                      target_uuid TEXT NOT NULL,
                      issuer_uuid TEXT NOT NULL,
                      reward_amount TEXT NOT NULL,
                      flags TEXT NOT NULL,
                      state TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      activated_at INTEGER,
                      expires_at INTEGER NOT NULL,
                      reservation_limit INTEGER NOT NULL,
                      settlement_claim_id TEXT,
                      last_error TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_hunters (
                      contract_id TEXT NOT NULL,
                      hunter_uuid TEXT NOT NULL,
                      status TEXT NOT NULL,
                      accepted_at INTEGER NOT NULL,
                      abandoned_at INTEGER,
                      PRIMARY KEY(contract_id, hunter_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_allowlist (
                      contract_id TEXT NOT NULL,
                      hunter_uuid TEXT NOT NULL,
                      PRIMARY KEY(contract_id, hunter_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_conditions (
                      contract_id TEXT NOT NULL,
                      position INTEGER NOT NULL,
                      type TEXT NOT NULL,
                      value TEXT NOT NULL,
                      PRIMARY KEY(contract_id, position)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS contract_history (
                      id TEXT PRIMARY KEY,
                      contract_id TEXT NOT NULL,
                      actor_uuid TEXT,
                      action TEXT NOT NULL,
                      details TEXT,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_contract_target_state ON contracts(target_uuid,state,expires_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_contract_contribution ON contracts(contribution_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_contract_settlement ON contracts(settlement_claim_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_contract_hunter_status ON contract_hunters(hunter_uuid,status)");
        }
    }

    @Override
    public CompletableFuture<Void> createDraft(BountyContract contract, Set<UUID> allowedHunters,
                                                List<ContractCondition> conditions) {
        return runAsync(() -> inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO contracts(id,contribution_id,target_uuid,issuer_uuid,reward_amount,flags,state,
                    created_at,activated_at,expires_at,reservation_limit,settlement_claim_id,last_error)
                    VALUES(?,?,?,?,?,?,?,?,NULL,?,?,NULL,NULL)
                    """)) {
                ps.setString(1, contract.id().toString());
                ps.setString(2, contract.contributionId().toString());
                ps.setString(3, contract.targetUuid().toString());
                ps.setString(4, contract.issuerUuid().toString());
                ps.setString(5, contract.rewardAmount().toPlainString());
                ps.setString(6, ContractFlag.serialize(contract.flags()));
                ps.setString(7, ContractState.DRAFT.name());
                ps.setLong(8, contract.createdAt().toEpochMilli());
                ps.setLong(9, contract.expiresAt().toEpochMilli());
                ps.setInt(10, contract.reservationLimit());
                ps.executeUpdate();
            }
            for (UUID hunter : allowedHunters) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO contract_allowlist(contract_id,hunter_uuid) VALUES(?,?)")) {
                    ps.setString(1, contract.id().toString());
                    ps.setString(2, hunter.toString());
                    ps.executeUpdate();
                }
            }
            for (int i = 0; i < conditions.size(); i++) {
                ContractCondition condition = conditions.get(i);
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO contract_conditions(contract_id,position,type,value) VALUES(?,?,?,?)
                        """)) {
                    ps.setString(1, contract.id().toString());
                    ps.setInt(2, i);
                    ps.setString(3, condition.type().name());
                    ps.setString(4, condition.value());
                    ps.executeUpdate();
                }
            }
            history(contract.id(), contract.issuerUuid(), "CREATED_DRAFT", "flags=" + ContractFlag.serialize(contract.flags()), contract.createdAt());
        }));
    }

    @Override
    public CompletableFuture<Void> open(UUID contractId, BigDecimal rewardAmount, Instant activatedAt) {
        return runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state='OPEN',reward_amount=?,activated_at=?,last_error=NULL
                    WHERE id=? AND state='DRAFT'
                    """)) {
                ps.setString(1, rewardAmount.toPlainString());
                ps.setLong(2, activatedAt.toEpochMilli());
                ps.setString(3, contractId.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Contract is not DRAFT: " + contractId);
            }
            history(contractId, null, "OPENED", "reward=" + rewardAmount.toPlainString(), activatedAt);
        });
    }

    @Override
    public CompletableFuture<Void> voidDraft(UUID contractId, String reason) {
        return runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE contracts SET state='VOIDED',last_error=? WHERE id=? AND state='DRAFT'")) {
                ps.setString(1, truncate(reason));
                ps.setString(2, contractId.toString());
                ps.executeUpdate();
            }
            history(contractId, null, "VOIDED", reason, Instant.now());
        });
    }

    @Override
    public CompletableFuture<Optional<ContractView>> get(UUID contractId, UUID viewerUuid, boolean admin) {
        return supplyAsync(() -> {
            BountyContract contract = readContract(contractId);
            if (contract == null || !visible(contract, viewerUuid, admin)) return Optional.empty();
            return Optional.of(view(contract, viewerUuid, admin));
        });
    }

    @Override
    public CompletableFuture<List<ContractView>> listVisible(UUID viewerUuid, boolean admin, Instant now, int limit) {
        return supplyAsync(() -> {
            List<ContractView> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT * FROM contracts WHERE state IN ('OPEN','RESERVED') AND expires_at>?
                    ORDER BY reward_amount DESC,created_at ASC LIMIT ?
                    """)) {
                ps.setLong(1, now.toEpochMilli());
                ps.setInt(2, Math.max(1, Math.min(limit * 4, 1000)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && result.size() < limit) {
                        BountyContract contract = mapContract(rs);
                        if (visible(contract, viewerUuid, admin)) result.add(view(contract, viewerUuid, admin));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<ActionResult> accept(UUID contractId, UUID hunterUuid, Instant now, int maxActivePerHunter) {
        return supplyAsync(() -> inTransactionResult(() -> {
            BountyContract contract = readContract(contractId);
            if (contract == null) return ActionResult.fail("CONTRACT_NOT_FOUND");
            if (!contract.state().acceptingHunters()) return ActionResult.fail("CONTRACT_NOT_OPEN");
            if (!contract.expiresAt().isAfter(now)) return ActionResult.fail("CONTRACT_EXPIRED");
            if (hunterUuid.equals(contract.targetUuid())) return ActionResult.fail("TARGET_CANNOT_ACCEPT");
            if (hunterUuid.equals(contract.issuerUuid())) return ActionResult.fail("ISSUER_CANNOT_ACCEPT");
            if (contract.isPrivate() && !allowed(contract.id(), hunterUuid)) return ActionResult.fail("PRIVATE_CONTRACT");
            if (accepted(contract.id(), hunterUuid)) return ActionResult.fail("ALREADY_ACCEPTED");
            if (activeForHunter(hunterUuid) >= Math.max(1, maxActivePerHunter)) return ActionResult.fail("HUNTER_CONTRACT_LIMIT");
            int active = activeHunters(contract.id());
            int limit = contract.isExclusive() ? 1 : contract.reservationLimit();
            if (active >= limit) return ActionResult.fail("CONTRACT_FULL");

            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO contract_hunters(contract_id,hunter_uuid,status,accepted_at,abandoned_at)
                    VALUES(?,?,'ACTIVE',?,NULL)
                    ON CONFLICT(contract_id,hunter_uuid) DO UPDATE SET status='ACTIVE',accepted_at=excluded.accepted_at,abandoned_at=NULL
                    """)) {
                ps.setString(1, contract.id().toString());
                ps.setString(2, hunterUuid.toString());
                ps.setLong(3, now.toEpochMilli());
                ps.executeUpdate();
            }
            if (contract.isExclusive()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE contracts SET state='RESERVED' WHERE id=? AND state='OPEN'")) {
                    ps.setString(1, contract.id().toString());
                    ps.executeUpdate();
                }
            }
            history(contract.id(), hunterUuid, "ACCEPTED", null, now);
            return ActionResult.ok();
        }));
    }

    @Override
    public CompletableFuture<ActionResult> abandon(UUID contractId, UUID hunterUuid, Instant now) {
        return supplyAsync(() -> inTransactionResult(() -> {
            BountyContract contract = readContract(contractId);
            if (contract == null) return ActionResult.fail("CONTRACT_NOT_FOUND");
            if (contract.state() == ContractState.CLAIMING) return ActionResult.fail("CONTRACT_SETTLING");
            if (!accepted(contract.id(), hunterUuid)) return ActionResult.fail("NOT_ACCEPTED");
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contract_hunters SET status='ABANDONED',abandoned_at=?
                    WHERE contract_id=? AND hunter_uuid=? AND status='ACTIVE'
                    """)) {
                ps.setLong(1, now.toEpochMilli());
                ps.setString(2, contract.id().toString());
                ps.setString(3, hunterUuid.toString());
                if (ps.executeUpdate() != 1) return ActionResult.fail("NOT_ACCEPTED");
            }
            if (contract.isExclusive()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE contracts SET state='OPEN' WHERE id=? AND state='RESERVED'")) {
                    ps.setString(1, contract.id().toString());
                    ps.executeUpdate();
                }
            }
            history(contract.id(), hunterUuid, "ABANDONED", null, now);
            return ActionResult.ok();
        }));
    }

    @Override
    public CompletableFuture<BigDecimal> claimableAmount(UUID targetUuid, UUID hunterUuid, String world,
                                                          String weaponMaterial, Instant now) {
        return supplyAsync(() -> selectEligible(targetUuid, hunterUuid, world, weaponMaterial, now).stream()
                .map(EligibleContribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public CompletableFuture<ClaimPreparation> prepareClaim(UUID claimId, UUID payoutOperationId,
                                                             UUID targetUuid, UUID hunterUuid,
                                                             String world, String weaponMaterial,
                                                             Instant now, BigDecimal hunterBalanceBefore) {
        return supplyAsync(() -> inTransactionResult(() -> {
            List<EligibleContribution> selected = selectEligible(targetUuid, hunterUuid, world, weaponMaterial, now);
            if (selected.isEmpty()) throw new IllegalStateException("NO_ELIGIBLE_BOUNTY");
            BigDecimal total = selected.stream().map(EligibleContribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<UUID> contractIds = new HashSet<>();

            for (EligibleContribution item : selected) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE bounty_contributions SET state='CLAIMING',claim_id=?,updated_at=?
                        WHERE id=? AND state='ACTIVE' AND expires_at>?
                        """)) {
                    ps.setString(1, claimId.toString());
                    ps.setLong(2, now.toEpochMilli());
                    ps.setString(3, item.contributionId().toString());
                    ps.setLong(4, now.toEpochMilli());
                    if (ps.executeUpdate() != 1) throw new SQLException("Concurrent contribution mutation: " + item.contributionId());
                }
                if (item.contractId() != null) contractIds.add(item.contractId());
            }

            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO claims(id,target_uuid,killer_uuid,total_amount,world,status,created_at)
                    VALUES(?,?,?,?,?,'PREPARED',?)
                    """)) {
                ps.setString(1, claimId.toString());
                ps.setString(2, targetUuid.toString());
                ps.setString(3, hunterUuid.toString());
                ps.setString(4, total.toPlainString());
                ps.setString(5, world);
                ps.setLong(6, now.toEpochMilli());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO economy_operations(id,type,player_uuid,contribution_id,claim_id,amount,balance_before,balance_after,status,error,created_at,updated_at)
                    VALUES(?,'PAYOUT',?,NULL,?,?,?,NULL,'INTENT',NULL,?,?)
                    """)) {
                ps.setString(1, payoutOperationId.toString());
                ps.setString(2, hunterUuid.toString());
                ps.setString(3, claimId.toString());
                ps.setString(4, total.toPlainString());
                ps.setString(5, hunterBalanceBefore.toPlainString());
                ps.setLong(6, now.toEpochMilli());
                ps.setLong(7, now.toEpochMilli());
                ps.executeUpdate();
            }
            for (UUID contractId : contractIds) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE contracts SET state='CLAIMING',settlement_claim_id=?,last_error=NULL
                        WHERE id=? AND state IN ('OPEN','RESERVED')
                        """)) {
                    ps.setString(1, claimId.toString());
                    ps.setString(2, contractId.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Concurrent contract mutation: " + contractId);
                }
                history(contractId, hunterUuid, "CLAIM_PREPARED", "claim=" + claimId, now);
            }
            return new ClaimPreparation(claimId, targetUuid, hunterUuid, total, selected.size(), contractIds.size());
        }));
    }

    @Override
    public CompletableFuture<Void> completeContractsForClaim(UUID claimId, Instant completedAt) {
        return runAsync(() -> inTransaction(() -> completeContractsForClaimTx(claimId, completedAt)));
    }

    @Override
    public CompletableFuture<Void> releaseContractsForClaim(UUID claimId, String reason, Instant now) {
        return runAsync(() -> inTransaction(() -> releaseContractsForClaimTx(claimId, reason, now)));
    }

    @Override
    public CompletableFuture<Integer> reconcile() {
        return supplyAsync(() -> inTransactionResult(() -> {
            int changed = 0;
            List<UUID> draft = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM contracts WHERE state='DRAFT'")) {
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) draft.add(UUID.fromString(rs.getString(1))); }
            }
            for (UUID id : draft) {
                String contributionState = contributionState(id);
                if ("ACTIVE".equals(contributionState)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE contracts SET state='OPEN',activated_at=? WHERE id=? AND state='DRAFT'")) {
                        ps.setLong(1, System.currentTimeMillis());
                        ps.setString(2, id.toString());
                        changed += ps.executeUpdate();
                    }
                } else if (contributionState != null && !"PENDING".equals(contributionState)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE contracts SET state='VOIDED',last_error=? WHERE id=? AND state='DRAFT'")) {
                        ps.setString(1, "Recovered from contribution state " + contributionState);
                        ps.setString(2, id.toString());
                        changed += ps.executeUpdate();
                    }
                }
            }

            List<ClaimState> claims = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.settlement_claim_id,cl.status FROM contracts c
                    LEFT JOIN claims cl ON cl.id=c.settlement_claim_id
                    WHERE c.state='CLAIMING' AND c.settlement_claim_id IS NOT NULL
                    GROUP BY c.settlement_claim_id,cl.status
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) claims.add(new ClaimState(UUID.fromString(rs.getString(1)), rs.getString(2)));
                }
            }
            for (ClaimState claim : claims) {
                if ("PAID".equals(claim.status())) {
                    completeContractsForClaimTx(claim.claimId(), Instant.now());
                    changed++;
                } else if ("FAILED".equals(claim.status())) {
                    releaseContractsForClaimTx(claim.claimId(), "Recovered failed claim", Instant.now());
                    changed++;
                }
            }
            return changed;
        }));
    }

    @Override
    public CompletableFuture<Integer> syncTerminalStates(Instant now) {
        return supplyAsync(() -> inTransactionResult(() -> {
            int changed = 0;
            changed += syncState("EXPIRED", "EXPIRED", now);
            changed += syncState("REFUNDED", "EXPIRED", now);
            changed += syncState("CANCELLED", "CANCELLED", now);
            changed += syncState("VOIDED", "VOIDED", now);
            return changed;
        }));
    }

    @Override
    public CompletableFuture<BigDecimal> visibleTotal(UUID targetUuid, UUID viewerUuid, boolean admin, Instant now) {
        return supplyAsync(() -> visibleContributions(viewerUuid, admin, now).stream()
                .filter(v -> v.targetUuid().equals(targetUuid))
                .map(VisibleContribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public CompletableFuture<List<VisibleTargetTotal>> visibleTargetTotals(UUID viewerUuid, boolean admin,
                                                                            Instant now, int limit) {
        return supplyAsync(() -> {
            Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
            for (VisibleContribution item : visibleContributions(viewerUuid, admin, now)) {
                totals.merge(item.targetUuid(), item.amount(), BigDecimal::add);
            }
            return totals.entrySet().stream()
                    .map(e -> new VisibleTargetTotal(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(VisibleTargetTotal::amount).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        });
    }

    @Override
    public CompletableFuture<Integer> contractCount() {
        return supplyAsync(() -> {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM contracts")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private List<VisibleContribution> visibleContributions(UUID viewerUuid, boolean admin, Instant now) throws Exception {
        List<VisibleContribution> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT b.target_uuid,b.escrow_amount,c.id AS contract_id,c.issuer_uuid,c.flags,c.state,c.expires_at
                FROM bounty_contributions b LEFT JOIN contracts c ON c.contribution_id=b.id
                WHERE b.state='ACTIVE' AND b.expires_at>?
                """)) {
            ps.setLong(1, now.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String contractRaw = rs.getString("contract_id");
                    if (contractRaw != null) {
                        BountyContract contract = readContract(UUID.fromString(contractRaw));
                        if (contract == null || !visible(contract, viewerUuid, admin)) continue;
                    }
                    result.add(new VisibleContribution(UUID.fromString(rs.getString("target_uuid")), new BigDecimal(rs.getString("escrow_amount"))));
                }
            }
        }
        return result;
    }

    private List<EligibleContribution> selectEligible(UUID targetUuid, UUID hunterUuid, String world,
                                                       String weaponMaterial, Instant now) throws Exception {
        List<EligibleContribution> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT b.id,b.escrow_amount,c.id AS contract_id FROM bounty_contributions b
                LEFT JOIN contracts c ON c.contribution_id=b.id
                WHERE b.target_uuid=? AND b.state='ACTIVE' AND b.expires_at>?
                """)) {
            ps.setString(1, targetUuid.toString());
            ps.setLong(2, now.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID contributionId = UUID.fromString(rs.getString("id"));
                    BigDecimal amount = new BigDecimal(rs.getString("escrow_amount"));
                    String contractRaw = rs.getString("contract_id");
                    if (contractRaw == null) {
                        result.add(new EligibleContribution(contributionId, null, amount));
                        continue;
                    }
                    UUID contractId = UUID.fromString(contractRaw);
                    BountyContract contract = readContract(contractId);
                    if (contract == null || !contract.activeAt(now) || !accepted(contractId, hunterUuid)) continue;
                    boolean conditionsOk = true;
                    for (ContractCondition condition : conditions(contractId)) {
                        if (!condition.matches(world, weaponMaterial)) { conditionsOk = false; break; }
                    }
                    if (conditionsOk) result.add(new EligibleContribution(contributionId, contractId, amount));
                }
            }
        }
        return result;
    }

    private ContractView view(BountyContract contract, UUID viewerUuid, boolean admin) throws Exception {
        boolean issuerVisible = !contract.isAnonymous() || admin || (viewerUuid != null && viewerUuid.equals(contract.issuerUuid()));
        return new ContractView(contract, activeHunters(contract.id()), viewerUuid != null && accepted(contract.id(), viewerUuid),
                issuerVisible, conditions(contract.id()));
    }

    private boolean visible(BountyContract contract, UUID viewerUuid, boolean admin) throws Exception {
        if (admin) return true;
        if (viewerUuid != null && viewerUuid.equals(contract.issuerUuid())) return true;
        if (!contract.isPrivate()) return true;
        return viewerUuid != null && allowed(contract.id(), viewerUuid);
    }

    private boolean allowed(UUID contractId, UUID hunterUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM contract_allowlist WHERE contract_id=? AND hunter_uuid=?")) {
            ps.setString(1, contractId.toString());
            ps.setString(2, hunterUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean accepted(UUID contractId, UUID hunterUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM contract_hunters WHERE contract_id=? AND hunter_uuid=? AND status='ACTIVE'
                """)) {
            ps.setString(1, contractId.toString());
            ps.setString(2, hunterUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private int activeHunters(UUID contractId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM contract_hunters WHERE contract_id=? AND status='ACTIVE'")) {
            ps.setString(1, contractId.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private int activeForHunter(UUID hunterUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM contract_hunters h JOIN contracts c ON c.id=h.contract_id
                WHERE h.hunter_uuid=? AND h.status='ACTIVE' AND c.state IN ('OPEN','RESERVED','CLAIMING')
                """)) {
            ps.setString(1, hunterUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private List<ContractCondition> conditions(UUID contractId) throws SQLException {
        List<ContractCondition> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT type,value FROM contract_conditions WHERE contract_id=? ORDER BY position
                """)) {
            ps.setString(1, contractId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new ContractCondition(
                        ContractConditionType.valueOf(rs.getString("type")), rs.getString("value")));
            }
        }
        return List.copyOf(result);
    }

    private BountyContract readContract(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM contracts WHERE id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapContract(rs) : null; }
        }
    }

    private BountyContract mapContract(ResultSet rs) throws SQLException {
        long activated = rs.getLong("activated_at");
        String claimRaw = rs.getString("settlement_claim_id");
        return new BountyContract(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("contribution_id")),
                UUID.fromString(rs.getString("target_uuid")),
                UUID.fromString(rs.getString("issuer_uuid")),
                new BigDecimal(rs.getString("reward_amount")),
                ContractFlag.parse(rs.getString("flags")),
                ContractState.valueOf(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.wasNull() || activated == 0L ? null : Instant.ofEpochMilli(activated),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                rs.getInt("reservation_limit"),
                claimRaw == null ? null : UUID.fromString(claimRaw),
                rs.getString("last_error")
        );
    }

    private String contributionState(UUID contractId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT b.state FROM contracts c LEFT JOIN bounty_contributions b ON b.id=c.contribution_id WHERE c.id=?
                """)) {
            ps.setString(1, contractId.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private void completeContractsForClaimTx(UUID claimId, Instant completedAt) throws Exception {
        UUID hunter = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT killer_uuid FROM claims WHERE id=? AND status='PAID'")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) hunter = UUID.fromString(rs.getString(1)); }
        }
        if (hunter == null) return;
        List<UUID> ids = contractIdsForClaim(claimId);
        for (UUID id : ids) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state='COMPLETED',last_error=NULL WHERE id=? AND state='CLAIMING'
                    """)) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contract_hunters SET status=CASE WHEN hunter_uuid=? THEN 'COMPLETED' ELSE 'CANCELLED' END
                    WHERE contract_id=? AND status='ACTIVE'
                    """)) {
                ps.setString(1, hunter.toString());
                ps.setString(2, id.toString());
                ps.executeUpdate();
            }
            history(id, hunter, "COMPLETED", "claim=" + claimId, completedAt);
        }
    }

    private void releaseContractsForClaimTx(UUID claimId, String reason, Instant now) throws Exception {
        List<UUID> ids = contractIdsForClaim(claimId);
        for (UUID id : ids) {
            BountyContract contract = readContract(id);
            if (contract == null) continue;
            String next = contract.isExclusive() && activeHunters(id) > 0 ? "RESERVED" : "OPEN";
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE contracts SET state=?,settlement_claim_id=NULL,last_error=? WHERE id=? AND state='CLAIMING'
                    """)) {
                ps.setString(1, next);
                ps.setString(2, truncate(reason));
                ps.setString(3, id.toString());
                ps.executeUpdate();
            }
            history(id, null, "CLAIM_RELEASED", reason, now);
        }
    }

    private List<UUID> contractIdsForClaim(UUID claimId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM contracts WHERE settlement_claim_id=?")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(UUID.fromString(rs.getString(1))); }
        }
        return ids;
    }

    private int syncState(String contributionState, String contractState, Instant now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE contracts SET state=?,last_error=? WHERE id IN (
                  SELECT c.id FROM contracts c JOIN bounty_contributions b ON b.id=c.contribution_id
                  WHERE c.state IN ('OPEN','RESERVED') AND b.state=?
                )
                """)) {
            ps.setString(1, contractState);
            ps.setString(2, "Synced from contribution state " + contributionState + " at " + now);
            ps.setString(3, contributionState);
            return ps.executeUpdate();
        }
    }

    private void history(UUID contractId, UUID actorUuid, String action, String details, Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO contract_history(id,contract_id,actor_uuid,action,details,created_at) VALUES(?,?,?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, contractId.toString());
            ps.setString(3, actorUuid == null ? null : actorUuid.toString());
            ps.setString(4, action);
            ps.setString(5, truncate(details));
            ps.setLong(6, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    private void inTransaction(SqlRunnable action) throws Exception {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    private <T> T inTransactionResult(SqlSupplier<T> action) throws Exception {
        final Object[] result = new Object[1];
        inTransaction(() -> result[0] = action.get());
        @SuppressWarnings("unchecked") T cast = (T) result[0];
        return cast;
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try { runnable.run(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        if (connection != null) {
            try { connection.close(); }
            catch (SQLException ignored) {}
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record EligibleContribution(UUID contributionId, UUID contractId, BigDecimal amount) {}
    private record VisibleContribution(UUID targetUuid, BigDecimal amount) {}
    private record ClaimState(UUID claimId, String status) {}

    @FunctionalInterface private interface SqlRunnable { void run() throws Exception; }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}
