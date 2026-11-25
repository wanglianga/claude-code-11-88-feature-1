package com.lakegov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 完整性治理服务（独立于恢复依赖的完整性域）：
 *
 * 建立五本账：
 *  1. 对象完整性账（data_object 完整性分级 + object_replica 副本/纠删码片段状态）；
 *  2. 校验覆盖地图（audit_sample_item + 按对象汇总的覆盖/到期/盲区状态）；
 *  3. 纠删码片段状态（EC k+m 片段 HEALTHY/DEGRADED/LOST/MISPLACED）；
 *  4. 读量预算（integrity_budget_policy：每存储层每周期的对象比例上限与字节预算硬顶）；
 *  5. 修复优先级（integrity_defect.repair_priority + repair_verification 修复台账）。
 *
 * 两条抽样路线（均不读取全量对象，均给出该层缺陷率区间）：
 *  - STRATIFIED_FIXED：按媒介/年龄/价值分层的固定比例抽样，覆盖频率可预期，对新故障反应慢；
 *  - ADAPTIVE：按既往错误、介质告警、证据价值自适应加权，发现效率高，但必须报告未覆盖概率。
 *
 * 硬规则：
 *  - 每个存储层每个审计周期的校验范围不得超过声明的对象比例和字节预算；
 *  - 只有不可替代证据的最小清单允许在更长轮换周期内覆盖；
 *  - 哈希不符立即冻结删除（deletion_frozen），并沿血缘登记治理事件；
 *  - 同一副本重复检查不记为独立样本，不进入缺陷率区间的样本量。
 */
@Service
public class IntegrityService {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntegrityService(JdbcTemplate jdbc, LineageService lineage) {
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    // ============================================================
    // 读量预算策略
    // ============================================================

    public List<Map<String, Object>> policies() {
        return jdbc.queryForList("""
                SELECT p.id, p.tier_code AS "tierCode", t.name AS "tierName",
                       p.audit_cycle_days AS "auditCycleDays",
                       p.max_object_ratio AS "maxObjectRatio",
                       p.max_byte_budget AS "maxByteBudget",
                       p.sampling_strategy AS "samplingStrategy",
                       p.evidence_rotation_cycle_days AS "evidenceRotationCycleDays",
                       p.active, p.updated_at AS "updatedAt"
                FROM integrity_budget_policy p JOIN storage_tier t ON t.code = p.tier_code
                ORDER BY p.tier_code
                """);
    }

    @Transactional
    public Map<String, Object> updatePolicy(String tierCode, double maxObjectRatio, long maxByteBudget,
                                            String strategy, int auditCycleDays, int evidenceRotationDays) {
        if (maxObjectRatio <= 0 || maxObjectRatio > 1) {
            throw new IllegalArgumentException("对象比例必须在 (0,1] 区间");
        }
        if (maxByteBudget <= 0) throw new IllegalArgumentException("字节预算必须为正数");
        if (!"STRATIFIED_FIXED".equals(strategy) && !"ADAPTIVE".equals(strategy)) {
            throw new IllegalArgumentException("未知抽样路线: " + strategy);
        }
        int updated = jdbc.update("""
                UPDATE integrity_budget_policy
                SET audit_cycle_days = ?, max_object_ratio = ?, max_byte_budget = ?,
                    sampling_strategy = ?, evidence_rotation_cycle_days = ?, active = true, updated_at = now()
                WHERE tier_code = ?
                """, auditCycleDays, maxObjectRatio, maxByteBudget, strategy, evidenceRotationDays, tierCode);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO integrity_budget_policy(tier_code, audit_cycle_days, max_object_ratio,
                        max_byte_budget, sampling_strategy, evidence_rotation_cycle_days, active)
                    VALUES (?,?,?,?,?,?,true)
                    """, tierCode, auditCycleDays, maxObjectRatio, maxByteBudget, strategy, evidenceRotationDays);
        }
        return Map.of("tierCode", tierCode, "updated", true);
    }

    // ============================================================
    // 对象完整性账：副本 / 纠删码片段
    // ============================================================

    public List<Map<String, Object>> replicas(String uid) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, o.object_uid AS uid, r.replica_no AS "replicaNo",
                       r.replica_kind AS "replicaKind", r.ec_fragment_index AS "ecFragmentIndex",
                       r.ec_k AS "ecK", r.ec_m AS "ecM", r.placement_tier AS "placementTier",
                       r.media_id AS "mediaId", r.fragment_status AS "fragmentStatus",
                       r.latent_corruption AS "latentCorruption",
                       r.last_verified_at AS "lastVerifiedAt"
                FROM object_replica r JOIN data_object o ON o.id = r.object_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (uid != null && !uid.isBlank()) {
            sql.append(" AND o.object_uid = ?");
            args.add(uid);
        }
        sql.append(" ORDER BY o.object_uid, r.replica_kind, r.ec_fragment_index NULLS FIRST, r.replica_no");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 纠删码片段状态（仅 EC_FRAGMENT），含 k+m 可容忍度判定 */
    public List<Map<String, Object>> ecFragments(String uid) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, o.object_uid AS uid, r.ec_fragment_index AS "fragmentIndex",
                       r.ec_k AS k, r.ec_m AS m, r.media_id AS "mediaId",
                       r.placement_tier AS "placementTier", r.fragment_status AS "status",
                       r.latent_corruption AS "latentCorruption",
                       r.last_verified_at AS "lastVerifiedAt"
                FROM object_replica r JOIN data_object o ON o.id = r.object_id
                WHERE r.replica_kind = 'EC_FRAGMENT'
                  AND (?::text IS NULL OR o.object_uid = ?)
                ORDER BY o.object_uid, r.ec_fragment_index
                """, uid, uid);
        for (Map<String, Object> row : rows) {
            int m = ((Number) row.get("m")).intValue();
            long bad = rows.stream()
                    .filter(x -> x.get("uid").equals(row.get("uid")))
                    .filter(x -> !"HEALTHY".equals(x.get("status"))
                            || Boolean.TRUE.equals(x.get("latentCorruption")))
                    .count();
            row.put("badFragments", bad);
            row.put("toleranceRemaining", m - bad);
            row.put("withinTolerance", bad <= m);
        }
        return rows;
    }

    // ============================================================
    // 介质告警（自适应抽样输入）
    // ============================================================

    public List<Map<String, Object>> mediaAlerts() {
        return jdbc.queryForList("""
                SELECT id, media_id AS "mediaId", tier_code AS "tierCode", severity,
                       alert_type AS "alertType", active, created_at AS "createdAt"
                FROM media_alert ORDER BY active DESC, id DESC
                """);
    }

    @Transactional
    public Map<String, Object> raiseMediaAlert(String mediaId, String tierCode, String severity, String alertType) {
        jdbc.update("""
                INSERT INTO media_alert(media_id, tier_code, severity, alert_type, active)
                VALUES (?,?,?,?,true)
                """, mediaId, tierCode, severity, alertType);
        return Map.of("mediaId", mediaId, "raised", true);
    }

    @Transactional
    public Map<String, Object> resolveMediaAlert(long id) {
        jdbc.update("UPDATE media_alert SET active = false WHERE id = ?", id);
        return Map.of("id", id, "active", false);
    }

    // ============================================================
    // 故障注入（演示位腐蚀 / 错误分层副本；生产中由扫描器发现）
    // ============================================================

    @Transactional
    public Map<String, Object> injectBitRot(String uid, Integer replicaNo) {
        long objectId = requireObject(uid);
        int n = jdbc.update("""
                UPDATE object_replica SET latent_corruption = true
                WHERE object_id = ? AND (?::int IS NULL OR replica_no = ?)
                """, objectId, replicaNo, replicaNo);
        if (n == 0) throw new IllegalArgumentException("对象无副本可注入: " + uid);
        return Map.of("uid", uid, "replicaNo", replicaNo == null ? "ALL" : replicaNo, "fault", "BIT_ROT");
    }

    @Transactional
    public Map<String, Object> injectMisplacement(String uid, Integer replicaNo) {
        long objectId = requireObject(uid);
        int n = jdbc.update("""
                UPDATE object_replica SET fragment_status = 'MISPLACED'
                WHERE object_id = ? AND replica_kind = 'FULL_COPY'
                  AND (?::int IS NULL OR replica_no = ?)
                """, objectId, replicaNo, replicaNo);
        if (n == 0) throw new IllegalArgumentException("对象无全量副本可注入错误分层: " + uid);
        return Map.of("uid", uid, "replicaNo", replicaNo == null ? "ALL" : replicaNo, "fault", "MISPLACED_REPLICA");
    }

    /** 注入片段/副本丢失（用于覆盖盲区演练）；不传 replicaNo 时丢失该对象全部副本 */
    @Transactional
    public Map<String, Object> injectLost(String uid, Integer replicaNo) {
        long objectId = requireObject(uid);
        int n = jdbc.update("""
                UPDATE object_replica SET fragment_status = 'LOST'
                WHERE object_id = ? AND (?::int IS NULL OR replica_no = ?)
                """, objectId, replicaNo, replicaNo);
        if (n == 0) throw new IllegalArgumentException("对象无副本可注入丢失: " + uid);
        return Map.of("uid", uid, "replicaNo", replicaNo == null ? "ALL" : replicaNo, "fault", "LOST");
    }

    @Transactional
    public Map<String, Object> clearFaults() {
        // 演练复位：所有非健康状态（含 LOST/DEGRADED/MISPLACED）与潜在腐蚀全部恢复健康
        int n = jdbc.update("UPDATE object_replica SET latent_corruption = false, " +
                "fragment_status = 'HEALTHY' WHERE fragment_status <> 'HEALTHY' OR latent_corruption = true");
        return Map.of("cleared", true, "replicasReset", n);
    }

    // ============================================================
    // 审计周期：两条抽样路线的核心
    // ============================================================

    @Transactional
    public Map<String, Object> runCycle(String tierCode, String strategyOverride) {
        Map<String, Object> policy = policyOf(tierCode);
        String strategy = strategyOverride != null ? strategyOverride
                : (String) policy.get("samplingStrategy");
        if (!"STRATIFIED_FIXED".equals(strategy) && !"ADAPTIVE".equals(strategy)) {
            throw new IllegalArgumentException("未知抽样路线: " + strategy);
        }
        int cycleDays = ((Number) policy.get("auditCycleDays")).intValue();
        int rotationDays = ((Number) policy.get("evidenceRotationCycleDays")).intValue();
        double ratio = ((Number) policy.get("maxObjectRatio")).doubleValue();
        long byteBudget = ((Number) policy.get("maxByteBudget")).longValue();

        List<Map<String, Object>> population = jdbc.queryForList("""
                SELECT id, object_uid AS uid, name, category, byte_size AS bytes,
                       integrity_class AS "integrityClass",
                       irreplaceable_evidence AS evidence,
                       audit_verified_bytes AS "verifiedBytes",
                       created_at AS "createdAt", last_audited_at AS "lastAuditedAt"
                FROM data_object WHERE tier_code = ? AND withdrawn = false
                ORDER BY id
                """, tierCode);
        int popCount = population.size();
        long popBytes = population.stream().map(o -> ((Number) o.get("bytes")).longValue())
                .reduce(0L, Long::sum);
        if (popCount == 0) throw new IllegalStateException("存储层 " + tierCode + " 无对象，无法建立审计周期");

        int capCount = (int) Math.floor(popCount * ratio);
        capCount = Math.max(1, Math.min(capCount, popCount));

        int cycleSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(cycle_seq),0) + 1 FROM audit_cycle WHERE tier_code = ?",
                Integer.class, tierCode);
        jdbc.update("""
                INSERT INTO audit_cycle(cycle_seq, tier_code, strategy, population_objects, population_bytes,
                    byte_budget, object_ratio_limit, status, note)
                VALUES (?,?,?,?,?,?,?, 'OPEN', ?)
                """, cycleSeq, tierCode, strategy, popCount, popBytes, byteBudget, ratio,
                "周期 #" + cycleSeq + " 路线=" + strategy);
        long cycleId = jdbc.queryForObject("SELECT currval('audit_cycle_id_seq')", Long.class);

        // 预取该层全部副本、告警与未修复缺陷，供抽样打分
        Map<Long, List<Map<String, Object>>> replicasByObject = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.id, r.object_id AS objectId, r.replica_no AS replicaNo, r.replica_kind AS kind,
                       r.ec_fragment_index AS frag, r.ec_k, r.ec_m, r.media_id AS media,
                       r.fragment_status AS status,
                       r.latent_corruption AS corrupt, r.last_verified_at AS verifiedAt
                FROM object_replica r JOIN data_object o ON o.id = r.object_id
                WHERE o.tier_code = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("objectId", rs.getLong("objectId"));
            row.put("replicaNo", rs.getObject("replicaNo"));
            row.put("kind", rs.getString("kind"));
            row.put("frag", rs.getObject("frag"));
            row.put("k", rs.getObject("ec_k"));
            row.put("m", rs.getObject("ec_m"));
            row.put("media", rs.getString("media"));
            row.put("status", rs.getString("status"));
            row.put("corrupt", rs.getBoolean("corrupt"));
            row.put("verifiedAt", rs.getTimestamp("verifiedAt"));
            replicasByObject.computeIfAbsent(rs.getLong("objectId"), k -> new ArrayList<>()).add(row);
        }, tierCode);

        Map<String, String> activeAlertMedia = new LinkedHashMap<>();
        jdbc.query("""
                SELECT media_id, severity FROM media_alert WHERE active = true AND tier_code = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    activeAlertMedia.put(rs.getString(1), rs.getString(2));
                }, tierCode);

        Map<Long, Long> openDefectCount = new LinkedHashMap<>();
        jdbc.query("""
                SELECT object_id, count(*) FROM integrity_defect
                WHERE repair_status IN ('OPEN','REPAIRING') GROUP BY object_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                openDefectCount.put(rs.getLong(1), rs.getLong(2)));

        // 构造候选并打分/分层
        LocalDateTime now = LocalDateTime.now();
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> o : population) {
            long oid = ((Number) o.get("id")).longValue();
            long ageDays = Duration.between(((java.sql.Timestamp) o.get("createdAt")).toLocalDateTime(), now)
                    .toDays();
            String ageBand = ageDays <= 120 ? "NEW" : ageDays <= 300 ? "MID" : "AGED";
            boolean rotationDue = Boolean.TRUE.equals(o.get("evidence"))
                    && (o.get("lastAuditedAt") == null
                        || Duration.between(((java.sql.Timestamp) o.get("lastAuditedAt")).toLocalDateTime(), now)
                                .toDays() >= rotationDays);
            List<Map<String, Object>> reps = replicasByObject.getOrDefault(oid, List.of());
            String alertLevel = reps.stream().map(r -> activeAlertMedia.get(r.get("media")))
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.comparingInt(this::alertRank)).orElse(null);
            Candidate c = new Candidate();
            c.object = o;
            c.ageBand = ageBand;
            c.rotationDue = rotationDue;
            c.alertLevel = alertLevel;
            c.priorDefects = openDefectCount.getOrDefault(oid, 0L);
            c.replicas = reps;
            c.score = adaptiveScore(c);
            candidates.add(c);
        }

        List<Candidate> selected;
        if ("STRATIFIED_FIXED".equals(strategy)) {
            selected = selectStratified(candidates, capCount, cycleSeq);
        } else {
            selected = selectAdaptive(candidates, capCount);
        }

        // 执行校验、写抽样项与缺陷台账。
        // 字节预算在执行期按“部分扫描”硬约束：单周期每对象只扫描预算内的一段字节，
        // 超大对象（如 2TB 原始影像）跨多个周期分摊，完成全量覆盖后清零重新轮换。
        long remainingBudget = byteBudget;
        long sampledBytes = 0L;
        int sampledObjects = 0;
        List<Candidate> scanned = new ArrayList<>();
        for (Candidate c : selected) {
            if (remainingBudget <= 0) break;
            long objectBytes = ((Number) c.object.get("bytes")).longValue();
            long alreadyVerified = c.object.get("verifiedBytes") == null
                    ? 0L : ((Number) c.object.get("verifiedBytes")).longValue();
            long uncovered = Math.max(0L, objectBytes - alreadyVerified);
            long scanBytes = Math.min(uncovered, remainingBudget);
            if (scanBytes <= 0) continue;
            long oid = ((Number) c.object.get("id")).longValue();
            boolean defective = inspectCandidate(cycleId, c, scanBytes, now);
            if (defective) c.defective = true;
            scanned.add(c);
            sampledBytes += scanBytes;
            sampledObjects++;
            remainingBudget -= scanBytes;
            long newVerified = alreadyVerified + scanBytes;
            boolean fullRotation = newVerified >= objectBytes;
            jdbc.update("""
                    UPDATE data_object
                    SET last_audited_at = ?, audit_round_no = ?,
                        audit_verified_bytes = ?
                    WHERE id = ?
                    """, java.sql.Timestamp.valueOf(now), cycleSeq,
                    fullRotation ? 0L : newVerified, oid);
        }
        int defectiveObjects = (int) scanned.stream().filter(x -> x.defective).count();

        // 独立样本量：同一副本在上一周期已查过、本次无替代副本而重复检查的项不计独立
        Integer independentN = jdbc.queryForObject("""
                SELECT count(DISTINCT object_id) FROM audit_sample_item
                WHERE cycle_id = ? AND independent_sample = true
                """, Integer.class, cycleId);
        Integer independentDefects = jdbc.queryForObject("""
                SELECT count(DISTINCT s.object_id) FROM audit_sample_item s
                WHERE s.cycle_id = ? AND s.independent_sample = true
                  AND s.result_status IN ('DEFECT_BIT_ROT','DEFECT_MISPLACED')
                """, Integer.class, cycleId);
        double[] interval = wilson(independentDefects == null ? 0 : independentDefects,
                independentN == null ? 0 : independentN);

        // 自适应路线必须报告未覆盖概率：未被定向（无告警/无既往缺陷）的总体中，本周期未抽样比例
        Double uncoveredProbability = null;
        if ("ADAPTIVE".equals(strategy)) {
            long untargetedTotal = candidates.stream()
                    .filter(c -> c.alertLevel == null && c.priorDefects == 0).count();
            long untargetedSampled = scanned.stream()
                    .filter(c -> c.alertLevel == null && c.priorDefects == 0).count();
            uncoveredProbability = untargetedTotal == 0 ? 0d
                    : (double) (untargetedTotal - untargetedSampled) / untargetedTotal;
        }

        boolean withinBudget = sampledObjects <= capCount && sampledBytes <= byteBudget;
        jdbc.update("""
                UPDATE audit_cycle
                SET sampled_objects = ?, sampled_bytes = ?, within_budget = ?,
                    defect_rate_lower = ?, defect_rate_point = ?, defect_rate_upper = ?,
                    uncovered_probability = ?, status = 'COMPLETED'
                WHERE id = ?
                """, sampledObjects, sampledBytes, withinBudget,
                interval[0], interval[1], interval[2], uncoveredProbability, cycleId);

        return cycleDetail(cycleId);
    }

    /**
     * 固定比例分层抽样：年龄×价值分层按总体占比分配名额，轮换到期证据优先。
     * 仅受对象比例名额约束；字节预算在执行期按部分扫描硬约束。
     */
    private List<Candidate> selectStratified(List<Candidate> candidates, int capCount, int cycleSeq) {
        Map<String, List<Candidate>> strata = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String key = c.ageBand + "|" + c.object.get("integrityClass");
            strata.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        // 层内确定性洗牌（按周期序号作种子，可复现）
        Random rnd = new Random(cycleSeq * 1_000_003L);
        strata.values().forEach(list -> list.sort(Comparator
                .comparing((Candidate c) -> !c.rotationDue)
                .thenComparing(c -> c.object.get("uid").toString())
                .thenComparingDouble(c -> rnd.nextDouble())));
        // 按总体占比分配名额（非空层至少 1 个，受总名额约束）
        Map<String, Integer> quota = new LinkedHashMap<>();
        int allocated = 0;
        for (Map.Entry<String, List<Candidate>> e : strata.entrySet()) {
            int q = Math.max(1, (int) Math.round((double) e.getValue().size() / candidates.size() * capCount));
            quota.put(e.getKey(), q);
            allocated += q;
        }
        // 名额超总配额时按最大层依次削减
        while (allocated > capCount) {
            String biggest = quota.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .max(Comparator.comparingInt(e -> e.getValue() * 1000
                            + strata.get(e.getKey()).size()))
                    .map(Map.Entry::getKey).orElse(null);
            if (biggest == null) break;
            quota.put(biggest, quota.get(biggest) - 1);
            allocated--;
        }
        List<Candidate> picked = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> e : strata.entrySet()) {
            int q = Math.min(quota.get(e.getKey()), e.getValue().size());
            for (Candidate c : e.getValue()) {
                if (q <= 0) break;
                c.basis = c.rotationDue ? "EVIDENCE_ROTATION" : "STRATUM_FIXED";
                picked.add(c);
                q--;
            }
        }
        return picked;
    }

    /** 自适应抽样：介质告警/既往缺陷/证据价值加权排序，受同一对象比例名额约束 */
    private List<Candidate> selectAdaptive(List<Candidate> candidates, int capCount) {
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble((Candidate c) -> -c.score)
                .thenComparing(c -> c.object.get("uid").toString()));
        List<Candidate> picked = new ArrayList<>();
        for (Candidate c : ranked) {
            if (picked.size() >= capCount) break;
            if (c.alertLevel != null) c.basis = "MEDIA_ALERT";
            else if (c.priorDefects > 0) c.basis = "PRIOR_DEFECT";
            else if (Boolean.TRUE.equals(c.object.get("evidence"))) c.basis = "HIGH_VALUE";
            else c.basis = "STRATUM_FIXED";
            picked.add(c);
        }
        return picked;
    }

    private int adaptiveScore(Candidate c) {
        int score = 0;
        if (c.alertLevel != null) score += alertRank(c.alertLevel) * 20;      // HIGH=60/MEDIUM=40/LOW=20
        if (c.priorDefects > 0) score += 50;
        if (Boolean.TRUE.equals(c.object.get("evidence"))) score += 20;
        else if ("CRITICAL".equals(c.object.get("integrityClass"))) score += 10;
        if ("AGED".equals(c.ageBand)) score += 10;
        else if ("MID".equals(c.ageBand)) score += 5;
        return score;
    }

    private int alertRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    /** 副本可疑度排序键：腐蚀最优先，其次错位/丢失/降级，健康副本最后 */
    private int suspectRank(Map<String, Object> replica) {
        if (Boolean.TRUE.equals(replica.get("corrupt"))) return 0;
        String status = (String) replica.get("status");
        if ("MISPLACED".equals(status) || "LOST".equals(status)) return 1;
        if ("DEGRADED".equals(status)) return 2;
        return 3;
    }

    /**
     * 对一个被抽中对象执行校验：选择副本（优先最久未验的健康副本），
     * 全量副本查位腐蚀/错误分层，EC 对象逐片段查状态并按 k+m 容忍度定级。
     * @param scanBytes 本周期对该对象允许扫描的字节（部分扫描；可能小于对象总字节）
     * @return 该对象本周期是否发现缺陷
     */
    private boolean inspectCandidate(long cycleId, Candidate c, long scanBytes, LocalDateTime now) {
        long oid = ((Number) c.object.get("id")).longValue();
        String uid = (String) c.object.get("uid");
        List<Map<String, Object>> reps = c.replicas;
        boolean defective = false;

        // 上一周期该对象抽中的副本（用于独立样本判定）
        List<Long> lastCycleReplicaIds = jdbc.queryForList("""
                SELECT replica_id FROM audit_sample_item
                WHERE object_id = ? AND replica_id IS NOT NULL
                ORDER BY id DESC LIMIT 3
                """, Long.class, oid);

        List<Map<String, Object>> fullCopies = reps.stream()
                .filter(r -> "FULL_COPY".equals(r.get("kind"))).toList();
        List<Map<String, Object>> ecFragments = reps.stream()
                .filter(r -> "EC_FRAGMENT".equals(r.get("kind"))).toList();

        if (!fullCopies.isEmpty()) {
            // 副本轮换：可疑副本（腐蚀/错位）优先，其次最久未校验，确保故障副本不会被健康副本长期遮蔽
            Map<String, Object> chosen = fullCopies.stream()
                    .min(Comparator.comparingInt((Map<String, Object> r) -> suspectRank(r))
                            .thenComparing(r -> r.get("verifiedAt") == null
                                    ? LocalDateTime.MIN
                                    : ((java.sql.Timestamp) r.get("verifiedAt")).toLocalDateTime()))
                    .orElse(fullCopies.get(0));
            long replicaId = ((Number) chosen.get("id")).longValue();
            // 同一副本重复检查不记为独立样本：该副本此前已被检查过即非独立
            boolean independent = !lastCycleReplicaIds.contains(replicaId);
            String result = "VERIFIED";
            if (Boolean.TRUE.equals(chosen.get("corrupt"))) {
                result = "DEFECT_BIT_ROT";
                defective = true;
                openDefect(oid, replicaId, cycleId, "BIT_ROT", severityOf(c, chosen),
                        Map.of("uid", uid, "mediaId", chosen.get("media"),
                                "reason", "扫描摘要与封存 sha256 不符（位腐蚀）",
                                "replicaNo", chosen.get("replicaNo")));
                freezeOnHashMismatch(oid, uid);
            } else if ("MISPLACED".equals(chosen.get("status"))) {
                result = "DEFECT_MISPLACED";
                defective = true;
                openDefect(oid, replicaId, cycleId, "MISPLACED_REPLICA", severityOf(c, chosen),
                        Map.of("uid", uid, "mediaId", chosen.get("media"),
                                "reason", "副本不在声明存储层/媒介（错误分层副本）",
                                "replicaNo", chosen.get("replicaNo")));
                freezeDeletionOnly(oid);
            }
            insertSampleItem(cycleId, oid, replicaId, c, chosen, result, scanBytes, independent, now);
            jdbc.update("UPDATE object_replica SET last_verified_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(now), replicaId);
        }

        if (!ecFragments.isEmpty()) {
            long bad = ecFragments.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("corrupt")) || !"HEALTHY".equals(r.get("status")))
                    .count();
            int m = ecFragments.stream().map(r -> ((Number) r.get("m")).intValue()).findFirst().orElse(0);
            long fragmentBytes = scanBytes / Math.max(1, ecFragments.size());
            for (Map<String, Object> frag : ecFragments) {
                long replicaId = ((Number) frag.get("id")).longValue();
                boolean independent = !lastCycleReplicaIds.contains(replicaId);
                String result = "VERIFIED";
                if (Boolean.TRUE.equals(frag.get("corrupt"))) {
                    result = "DEFECT_BIT_ROT";
                    defective = true;
                    String severity = bad > m ? "CRITICAL" : "HIGH";
                    openDefect(oid, replicaId, cycleId, "BIT_ROT", severity,
                            Map.of("uid", uid, "fragmentIndex", frag.get("frag"),
                                    "mediaId", frag.get("media"), "badFragments", bad, "toleranceM", m,
                                    "reason", "EC 片段位腐蚀"));
                    freezeOnHashMismatch(oid, uid);
                } else if ("LOST".equals(frag.get("status")) || "MISPLACED".equals(frag.get("status"))
                        || "DEGRADED".equals(frag.get("status"))) {
                    result = "DEFECT_MISPLACED";
                    defective = true;
                    String severity = bad > m ? "CRITICAL" : bad == m ? "HIGH" : "MEDIUM";
                    openDefect(oid, replicaId, cycleId,
                            "MISPLACED".equals(frag.get("status")) ? "MISPLACED_REPLICA" : "COVERAGE_GAP",
                            severity,
                            Map.of("uid", uid, "fragmentIndex", frag.get("frag"),
                                    "mediaId", frag.get("media"), "fragmentStatus", frag.get("status"),
                                    "badFragments", bad, "toleranceM", m,
                                    "reason", "EC 片段 " + frag.get("status")));
                    freezeDeletionOnly(oid);
                }
                insertSampleItem(cycleId, oid, replicaId, c, frag, result, fragmentBytes, independent, now);
                jdbc.update("UPDATE object_replica SET last_verified_at = ? WHERE id = ?",
                        java.sql.Timestamp.valueOf(now), replicaId);
            }
        }
        return defective;
    }

    private void insertSampleItem(long cycleId, long oid, long replicaId, Candidate c,
                                  Map<String, Object> replica, String result, long bytes,
                                  boolean independent, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO audit_sample_item(cycle_id, object_id, replica_id, tier_code, media_id,
                    age_band, value_band, sample_basis, independent_sample, dedup_key,
                    result_status, bytes_scanned, checked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (cycle_id, object_id, replica_id) DO NOTHING
                """, cycleId, oid, replicaId,
                jdbc.queryForObject("SELECT tier_code FROM data_object WHERE id = ?", String.class, oid),
                replica.get("media"), c.ageBand, c.object.get("integrityClass"), c.basis, independent,
                oid + ":" + replicaId, result, bytes, java.sql.Timestamp.valueOf(now));
    }

    private String severityOf(Candidate c, Map<String, Object> replica) {
        if (Boolean.TRUE.equals(c.object.get("evidence"))) return "CRITICAL";
        if ("HIGH".equals(c.alertLevel)) return "HIGH";
        if ("CRITICAL".equals(c.object.get("integrityClass"))) return "HIGH";
        return "MEDIUM";
    }

    /** 哈希不符：立即冻结删除 + 校验状态置失败 + 血缘影响传播事件 */
    private void freezeOnHashMismatch(long oid, String uid) {
        jdbc.update("UPDATE data_object SET deletion_frozen = true, checksum_status = 'FAILED' WHERE id = ?", oid);
        List<String> affected = lineage.impactPropagation(oid).stream()
                .map(m -> (String) m.get("uid")).toList();
        recordIntegrityEvent(uid, oid, Map.of("uid", uid, "action", "FREEZE_DELETION",
                "reason", "哈希不符立即冻结删除，并失效下游完整性声明"), affected);
    }

    /** 错误分层等不损坏内容的缺陷：仅冻结删除，保留校验状态 */
    private void freezeDeletionOnly(long oid) {
        jdbc.update("UPDATE data_object SET deletion_frozen = true WHERE id = ?", oid);
    }

    private void openDefect(long oid, Long replicaId, long cycleId, String type, String severity,
                            Map<String, Object> detail) {
        int priority = repairPriority(severity, oid);
        jdbc.update("""
                INSERT INTO integrity_defect(object_id, replica_id, cycle_id, defect_type, severity,
                    detail, repair_priority, repair_status, freeze_deletion)
                VALUES (?,?,?,?,?,?::jsonb,?, 'OPEN', true)
                """, oid, replicaId, cycleId, type, severity, writeJson(detail), priority);
        long defectId = jdbc.queryForObject("SELECT currval('integrity_defect_id_seq')", Long.class);
        jdbc.update("""
                INSERT INTO governance_event(event_type, object_id, payload, propagated, affected_uids)
                VALUES ('INTEGRITY_DEFECT', ?, ?::jsonb, true, ?::jsonb)
                """, oid, writeJson(Map.of("defectId", defectId, "type", type,
                        "severity", severity, "detail", detail)),
                writeJson(lineage.impactPropagation(oid).stream().map(m -> m.get("uid")).toList()));
    }

    private int repairPriority(String severity, long oid) {
        int base = switch (severity) {
            case "CRITICAL" -> 10;
            case "HIGH" -> 25;
            case "MEDIUM" -> 50;
            default -> 80;
        };
        Integer evidence = jdbc.queryForObject(
                "SELECT CASE WHEN irreplaceable_evidence THEN 1 ELSE 0 END FROM data_object WHERE id = ?",
                Integer.class, oid);
        if (evidence != null && evidence == 1) base -= 10;
        Long repeated = jdbc.queryForObject(
                "SELECT count(*) FROM integrity_defect WHERE object_id = ?", Long.class, oid);
        if (repeated != null && repeated > 1) base -= 5;
        return Math.max(1, Math.min(99, base));
    }

    // ============================================================
    // 审计周期查询与缺陷率区间
    // ============================================================

    public List<Map<String, Object>> cycles() {
        return jdbc.queryForList("""
                SELECT c.id, c.cycle_seq AS "cycleSeq", c.tier_code AS "tierCode", c.strategy,
                       c.started_at AS "startedAt", c.population_objects AS "populationObjects",
                       c.population_bytes AS "populationBytes", c.sampled_objects AS "sampledObjects",
                       c.sampled_bytes AS "sampledBytes", c.byte_budget AS "byteBudget",
                       c.object_ratio_limit AS "objectRatioLimit", c.within_budget AS "withinBudget",
                       c.defect_rate_lower AS "defectRateLower", c.defect_rate_point AS "defectRatePoint",
                       c.defect_rate_upper AS "defectRateUpper",
                       c.uncovered_probability AS "uncoveredProbability", c.status, c.note
                FROM audit_cycle c ORDER BY c.id DESC
                """);
    }

    public Map<String, Object> cycleDetail(long cycleId) {
        Map<String, Object> cycle = jdbc.queryForMap("""
                SELECT id, cycle_seq AS "cycleSeq", tier_code AS "tierCode", strategy,
                       started_at AS "startedAt", population_objects AS "populationObjects",
                       population_bytes AS "populationBytes", sampled_objects AS "sampledObjects",
                       sampled_bytes AS "sampledBytes", byte_budget AS "byteBudget",
                       object_ratio_limit AS "objectRatioLimit", within_budget AS "withinBudget",
                       defect_rate_lower AS "defectRateLower", defect_rate_point AS "defectRatePoint",
                       defect_rate_upper AS "defectRateUpper",
                       uncovered_probability AS "uncoveredProbability", status, note
                FROM audit_cycle WHERE id = ?
                """, cycleId);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT s.id, o.object_uid AS uid, o.name, o.category, r.replica_kind AS "replicaKind",
                       r.ec_fragment_index AS "ecFragmentIndex", s.media_id AS "mediaId",
                       s.age_band AS "ageBand", s.value_band AS "valueBand",
                       s.sample_basis AS "sampleBasis", s.independent_sample AS "independentSample",
                       s.result_status AS "resultStatus", s.bytes_scanned AS "bytesScanned",
                       s.checked_at AS "checkedAt"
                FROM audit_sample_item s
                JOIN data_object o ON o.id = s.object_id
                LEFT JOIN object_replica r ON r.id = s.replica_id
                WHERE s.cycle_id = ? ORDER BY s.id
                """, cycleId);
        Map<String, Object> result = new LinkedHashMap<>(cycle);
        result.put("items", items);
        return result;
    }

    // ============================================================
    // 校验覆盖地图与覆盖盲区
    // ============================================================

    public List<Map<String, Object>> coverageMap(String tierCode) {
        List<Map<String, Object>> objects = jdbc.queryForList("""
                SELECT o.id, o.object_uid AS uid, o.name, o.category, o.tier_code AS "tierCode",
                       o.integrity_class AS "integrityClass",
                       o.irreplaceable_evidence AS evidence, o.deletion_frozen AS "deletionFrozen",
                       o.checksum_status AS "checksumStatus",
                       o.last_audited_at AS "lastAuditedAt", o.audit_round_no AS "auditRoundNo",
                       p.audit_cycle_days AS "auditCycleDays",
                       p.evidence_rotation_cycle_days AS "rotationDays"
                FROM data_object o
                LEFT JOIN integrity_budget_policy p ON p.tier_code = o.tier_code
                WHERE (?::text IS NULL OR o.tier_code = ?) AND o.withdrawn = false
                ORDER BY o.tier_code, o.id
                """, tierCode, tierCode);
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> o : objects) {
            long oid = ((Number) o.get("id")).longValue();
            Map<String, Object> replicaAgg = jdbc.queryForMap("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE fragment_status = 'HEALTHY' AND latent_corruption = false) AS healthy,
                           count(*) FILTER (WHERE fragment_status = 'DEGRADED') AS degraded,
                           count(*) FILTER (WHERE fragment_status = 'LOST') AS lost,
                           count(*) FILTER (WHERE fragment_status = 'MISPLACED') AS misplaced,
                           count(*) FILTER (WHERE latent_corruption = true) AS corrupt
                    FROM object_replica WHERE object_id = ?
                    """, oid);
            o.put("replicas", replicaAgg);
            long openDefects = jdbc.queryForObject(
                    "SELECT count(*) FROM integrity_defect WHERE object_id = ? AND repair_status IN ('OPEN','REPAIRING')",
                    Long.class, oid);
            o.put("openDefects", openDefects);
            long healthy = ((Number) replicaAgg.get("healthy")).longValue();
            String coverage;
            if (healthy == 0) {
                coverage = "BLIND"; // 覆盖盲区：无任何健康副本/片段
            } else if (o.get("lastAuditedAt") == null) {
                coverage = Boolean.TRUE.equals(o.get("evidence")) ? "OVERDUE" : "DUE";
            } else {
                long days = Duration.between(
                        ((java.sql.Timestamp) o.get("lastAuditedAt")).toLocalDateTime(), now).toDays();
                int cycle = o.get("auditCycleDays") == null ? 30
                        : ((Number) o.get("auditCycleDays")).intValue();
                int rotation = o.get("rotationDays") == null ? 180
                        : ((Number) o.get("rotationDays")).intValue();
                int limit = Boolean.TRUE.equals(o.get("evidence")) ? rotation : cycle;
                coverage = days > limit ? "OVERDUE" : days > cycle ? "DUE" : "COVERED";
            }
            o.put("coverageStatus", coverage);
        }
        return objects;
    }

    /** 扫描覆盖盲区并登记 COVERAGE_GAP 缺陷（无健康副本的对象） */
    @Transactional
    public Map<String, Object> scanCoverageGaps() {
        List<Map<String, Object>> blind = jdbc.queryForList("""
                SELECT o.id, o.object_uid AS uid
                FROM data_object o
                WHERE o.withdrawn = false
                  AND NOT EXISTS (
                    SELECT 1 FROM object_replica r
                    WHERE r.object_id = o.id AND r.fragment_status = 'HEALTHY' AND r.latent_corruption = false)
                """);
        List<String> opened = new ArrayList<>();
        for (Map<String, Object> b : blind) {
            long oid = ((Number) b.get("id")).longValue();
            Integer exists = jdbc.queryForObject("""
                    SELECT count(*) FROM integrity_defect
                    WHERE object_id = ? AND defect_type = 'COVERAGE_GAP' AND repair_status IN ('OPEN','REPAIRING')
                    """, Integer.class, oid);
            if (exists == null || exists == 0) {
                jdbc.update("""
                        INSERT INTO integrity_defect(object_id, replica_id, cycle_id, defect_type, severity,
                            detail, repair_priority, repair_status, freeze_deletion)
                        VALUES (?, NULL, NULL, 'COVERAGE_GAP', 'CRITICAL', ?::jsonb, 5, 'OPEN', true)
                        """, oid,
                        writeJson(Map.of("uid", b.get("uid"), "reason", "无健康副本，形成覆盖盲区")));
                freezeDeletionOnly(oid);
                opened.add((String) b.get("uid"));
            }
        }
        return Map.of("blindObjects", blind.size(), "openedDefects", opened);
    }

    // ============================================================
    // 缺陷台账与修复
    // ============================================================

    public List<Map<String, Object>> defects(String status) {
        return jdbc.queryForList("""
                SELECT d.id, o.object_uid AS uid, o.category, o.tier_code AS "tierCode",
                       r.replica_kind AS "replicaKind", r.ec_fragment_index AS "ecFragmentIndex",
                       d.defect_type AS "defectType", d.severity, d.detail,
                       d.repair_priority AS "repairPriority", d.repair_status AS "repairStatus",
                       d.freeze_deletion AS "freezeDeletion", d.detected_at AS "detectedAt",
                       d.repaired_at AS "repairedAt"
                FROM integrity_defect d
                JOIN data_object o ON o.id = d.object_id
                LEFT JOIN object_replica r ON r.id = d.replica_id
                WHERE ?::text IS NULL OR d.repair_status = ?
                ORDER BY d.repair_priority, d.id
                """, status, status);
    }

    /**
     * 修复：优先用另一健康全量副本重建；EC 对象用任意 k 个健康片段重建。
     * 修复后重校验写修复验证台账；对象无其它未修复位腐蚀时解除删除冻结。
     */
    @Transactional
    public Map<String, Object> repairDefect(long defectId) {
        Map<String, Object> defect = jdbc.queryForMap("SELECT * FROM integrity_defect WHERE id = ?", defectId);
        if (!"OPEN".equals(defect.get("repair_status"))) {
            throw new IllegalStateException("缺陷 " + defectId + " 状态为 " + defect.get("repair_status") + "，不可修复");
        }
        long oid = ((Number) defect.get("object_id")).longValue();
        Long badReplicaId = defect.get("replica_id") == null ? null
                : ((Number) defect.get("replica_id")).longValue();
        jdbc.update("UPDATE integrity_defect SET repair_status = 'REPAIRING' WHERE id = ?", defectId);

        // 寻找健康来源：另一健康全量副本，或 EC 的 k 个健康片段
        List<Long> healthyFull = jdbc.queryForList("""
                SELECT id FROM object_replica
                WHERE object_id = ? AND replica_kind = 'FULL_COPY'
                  AND fragment_status = 'HEALTHY' AND latent_corruption = false
                  AND (?::bigint IS NULL OR id <> ?)
                ORDER BY last_verified_at NULLS FIRST
                """, Long.class, oid, badReplicaId, badReplicaId);
        List<Integer> ecKRows = jdbc.queryForList("""
                SELECT ec_k FROM object_replica
                WHERE object_id = ? AND replica_kind = 'EC_FRAGMENT' LIMIT 1
                """, Integer.class, oid);
        Integer ecK = ecKRows.isEmpty() ? null : ecKRows.get(0);
        List<Long> healthyFragments = ecK == null ? List.of() : jdbc.queryForList("""
                SELECT id FROM object_replica
                WHERE object_id = ? AND replica_kind = 'EC_FRAGMENT'
                  AND fragment_status = 'HEALTHY' AND latent_corruption = false
                LIMIT ?
                """, Long.class, oid, ecK);

        Long sourceId;
        String source;
        if (!healthyFull.isEmpty()) {
            sourceId = healthyFull.get(0);
            source = "HEALTHY_REPLICA";
        } else if (ecK != null && healthyFragments.size() >= ecK) {
            sourceId = healthyFragments.get(0);
            source = "EC_RECONSTRUCT(k=" + ecK + ")";
        } else {
            jdbc.update("""
                    INSERT INTO repair_verification(defect_id, object_id, source_replica_id,
                        result_status, detail)
                    VALUES (?, ?, NULL, 'FAILED', ?::jsonb)
                    """, defectId, oid, writeJson(Map.of("reason", "无健康副本/不足 k 个健康 EC 片段，无法重建")));
            throw new IllegalStateException("对象无可用健康修复源，缺陷 " + defectId + " 保持 OPEN");
        }

        // 重建坏副本/片段并复核摘要
        if (badReplicaId != null) {
            jdbc.update("""
                    UPDATE object_replica
                    SET fragment_status = 'HEALTHY', latent_corruption = false, last_verified_at = now()
                    WHERE id = ?
                    """, badReplicaId);
        }
        String checksum = jdbc.queryForObject("SELECT checksum FROM data_object WHERE id = ?",
                String.class, oid);
        jdbc.update("""
                INSERT INTO repair_verification(defect_id, object_id, source_replica_id,
                    verified_checksum, result_status, detail)
                VALUES (?,?,?,?, 'REPAIRED', ?::jsonb)
                """, defectId, oid, sourceId, checksum,
                writeJson(Map.of("source", source, "checksumMatch", true)));
        jdbc.update("UPDATE integrity_defect SET repair_status = 'REPAIRED', repaired_at = now() WHERE id = ?",
                defectId);

        // 该对象无其它未修复位腐蚀 → 解除删除冻结与失败状态
        Integer remainingBitRot = jdbc.queryForObject("""
                SELECT count(*) FROM integrity_defect
                WHERE object_id = ? AND defect_type = 'BIT_ROT' AND repair_status IN ('OPEN','REPAIRING')
                """, Integer.class, oid);
        Integer remainingAny = jdbc.queryForObject("""
                SELECT count(*) FROM integrity_defect
                WHERE object_id = ? AND repair_status IN ('OPEN','REPAIRING')
                """, Integer.class, oid);
        if (remainingBitRot != null && remainingBitRot == 0) {
            jdbc.update("UPDATE data_object SET checksum_status = 'OK' WHERE id = ? AND checksum_status = 'FAILED'", oid);
        }
        if (remainingAny != null && remainingAny == 0) {
            jdbc.update("UPDATE data_object SET deletion_frozen = false WHERE id = ?", oid);
        }
        String uid = jdbc.queryForObject("SELECT object_uid FROM data_object WHERE id = ?",
                String.class, oid);
        jdbc.update("""
                INSERT INTO governance_event(event_type, object_id, payload, propagated, affected_uids)
                VALUES ('REPAIR_COMPLETED', ?, ?::jsonb, true, ?::jsonb)
                """, oid, writeJson(Map.of("defectId", defectId, "uid", uid, "source", source)),
                writeJson(List.of(uid)));
        return Map.of("defectId", defectId, "resultStatus", "REPAIRED", "source", source,
                "deletionFrozenCleared", remainingAny != null && remainingAny == 0);
    }

    public List<Map<String, Object>> repairVerifications() {
        return jdbc.queryForList("""
                SELECT v.id, v.defect_id AS "defectId", o.object_uid AS uid,
                       v.source_replica_id AS "sourceReplicaId", v.verified_checksum AS "verifiedChecksum",
                       v.result_status AS "resultStatus", v.detail, v.verified_at AS "verifiedAt"
                FROM repair_verification v JOIN data_object o ON o.id = v.object_id
                ORDER BY v.id DESC
                """);
    }

    // ============================================================
    // 总览统计
    // ============================================================

    public Map<String, Object> stats() {
        List<Map<String, Object>> tierStats = jdbc.queryForList("""
                SELECT t.code AS "tierCode", t.name AS "tierName",
                       (SELECT count(*) FROM data_object o WHERE o.tier_code = t.code AND o.withdrawn = false)
                         AS "objectCount",
                       (SELECT count(*) FROM integrity_defect d
                          JOIN data_object o ON o.id = d.object_id
                          WHERE o.tier_code = t.code AND d.repair_status IN ('OPEN','REPAIRING'))
                         AS "openDefects",
                       (SELECT count(*) FROM data_object o
                          WHERE o.tier_code = t.code AND o.deletion_frozen = true)
                         AS "deletionFrozen"
                FROM storage_tier t ORDER BY t.code
                """);
        for (Map<String, Object> row : tierStats) {
            String tier = (String) row.get("tierCode");
            List<Map<String, Object>> cycleRows = jdbc.queryForList("""
                    SELECT id, strategy, sampled_objects AS n, sampled_bytes AS bytes,
                           byte_budget AS budget, defect_rate_lower AS lower,
                           defect_rate_point AS point, defect_rate_upper AS upper,
                           uncovered_probability AS uncovered, within_budget AS within
                    FROM audit_cycle WHERE tier_code = ? ORDER BY id DESC LIMIT 1
                    """, tier);
            row.put("latestCycle", cycleRows.isEmpty() ? null : cycleRows.get(0));
            Long blind = jdbc.queryForObject("""
                    SELECT count(*) FROM data_object o
                    WHERE o.tier_code = ? AND o.withdrawn = false
                      AND NOT EXISTS (SELECT 1 FROM object_replica r
                        WHERE r.object_id = o.id AND r.fragment_status = 'HEALTHY' AND r.latent_corruption = false)
                    """, Long.class, tier);
            row.put("blindSpots", blind);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tiers", tierStats);
        result.put("totalOpenDefects", jdbc.queryForObject(
                "SELECT count(*) FROM integrity_defect WHERE repair_status IN ('OPEN','REPAIRING')", Long.class));
        result.put("totalDeletionFrozen", jdbc.queryForObject(
                "SELECT count(*) FROM data_object WHERE deletion_frozen = true", Long.class));
        result.put("evidenceObjects", jdbc.queryForObject(
                "SELECT count(*) FROM data_object WHERE irreplaceable_evidence = true", Long.class));
        result.put("ecObjects", jdbc.queryForObject(
                "SELECT count(DISTINCT object_id) FROM object_replica WHERE replica_kind = 'EC_FRAGMENT'",
                Long.class));
        return result;
    }

    // ============================================================
    // 辅助
    // ============================================================

    private Map<String, Object> policyOf(String tierCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tier_code AS "tierCode", audit_cycle_days AS "auditCycleDays",
                       max_object_ratio AS "maxObjectRatio", max_byte_budget AS "maxByteBudget",
                       sampling_strategy AS "samplingStrategy",
                       evidence_rotation_cycle_days AS "evidenceRotationCycleDays"
                FROM integrity_budget_policy WHERE tier_code = ? AND active = true
                """, tierCode);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("存储层 " + tierCode + " 未声明完整性读量预算策略");
        }
        return rows.get(0);
    }

    private long requireObject(String uid) {
        Long id = jdbc.queryForObject("SELECT id FROM data_object WHERE object_uid = ?",
                Long.class, uid);
        if (id == null) throw new IllegalArgumentException("对象不存在: " + uid);
        return id;
    }

    private void recordIntegrityEvent(String uid, long oid, Map<String, Object> payload,
                                      List<String> affected) {
        jdbc.update("""
                INSERT INTO governance_event(event_type, object_id, payload, propagated, affected_uids)
                VALUES ('INTEGRITY_DEFECT', ?, ?::jsonb, true, ?::jsonb)
                """, oid, writeJson(payload), writeJson(affected));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Wilson 得分区间（95%）：在不读取全量对象的前提下，用独立样本量 n 与缺陷数 d
     * 估计该存储层缺陷率的置信区间 [lower, upper]，点估计 phat = d/n。
     */
    private double[] wilson(int defects, int n) {
        if (n == 0) return new double[]{0d, 0d, 0d};
        double phat = (double) defects / n;
        double z = 1.959964;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (phat + z2 / (2 * n)) / denom;
        double margin = z * Math.sqrt((phat * (1 - phat) + z2 / (4 * n)) / n) / denom;
        return new double[]{
                Math.max(0d, center - margin),
                phat,
                Math.min(1d, center + margin)};
    }

    /** 抽样候选内部结构 */
    private static class Candidate {
        Map<String, Object> object;
        List<Map<String, Object>> replicas;
        String ageBand;
        String alertLevel;
        boolean rotationDue;
        long priorDefects;
        int score;
        String basis;
        boolean defective;
    }
}
