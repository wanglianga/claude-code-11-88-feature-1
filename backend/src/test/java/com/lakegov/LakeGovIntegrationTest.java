package com.lakegov;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：依赖本地 PostgreSQL（ccdev 库），应用启动时自动建表+种子。
 * 与 scripts/verify_e2e.py 覆盖相同核心规则，供 mvn test 使用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LakeGovIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpHeaders auth() {
        Map<String, Object> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) resp.get("token"));
        return h;
    }

    @Test
    @Order(1)
    void catalogSeededWithResearchASizes() {
        Map[] objects = rest.exchange("/api/catalog/objects", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(objects);
        assertEquals(10, objects.length);
        Map<String, Object> raw = List.of(objects).stream()
                .filter(o -> "RAW-IMG-A01".equals(o.get("uid"))).findFirst().orElseThrow();
        assertEquals(2_000_000_000_000L, ((Number) raw.get("byteSize")).longValue());
        assertEquals(64, ((String) raw.get("checksum")).length());
    }

    @Test
    @Order(2)
    void dependencyClosureContainsSevenObjectsWithSoftwareAndCalibration() {
        Map[] closure = rest.exchange("/api/retention/closure/RESULT-A01", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(closure);
        assertEquals(7, closure.length);
        var uids = List.of(closure).stream().map(o -> (String) o.get("uid")).toList();
        assertTrue(uids.containsAll(List.of(
                "RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                "ANN-A01", "CAL-A01", "ALGO-PKG-01")));
    }

    @Test
    @Order(3)
    void retentionRawImageTenYearsAndSoftwareExtended() {
        rest.exchange("/api/retention/recalculate", HttpMethod.POST,
                new HttpEntity<>(auth()), List.class);
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("2036-01-15", String.valueOf(byUid.get("RAW-IMG-A01").get("baseDeadline")));
        assertTrue(String.valueOf(byUid.get("ALGO-PKG-01").get("effectiveDeadline"))
                .compareTo("2034-02-05") >= 0);
    }

    @Test
    @Order(4)
    void litigationHoldPropagatesDownstreamAndReleases() {
        HttpHeaders h = auth();
        Map hold = rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", true,
                        "reason", "junit"), h), Map.class).getBody();
        @SuppressWarnings("unchecked")
        List<String> propagated = (List<String>) hold.get("propagatedTo");
        assertTrue(propagated.containsAll(List.of("MASK-A01", "FEAT-A01", "RESULT-A01")));
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("BLOCKED_HOLD", byUid.get("RESULT-A01").get("disposition"));
        rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", false,
                        "reason", "junit release"), h), Map.class);
    }

    @Test
    @Order(5)
    void missingCalibrationBlocksRestoreBeforeStart() {
        HttpHeaders h = auth();
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", false), h), Map.class);
        Map plan = rest.exchange("/api/restore/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUid", "RESULT-A01",
                        "strategy", "SCHEDULED_TIERING", "frequencyPerYear", 1), h), Map.class).getBody();
        assertEquals("UNSATISFIABLE", plan.get("status"));
        @SuppressWarnings("unchecked")
        List<Map> missing = (List<Map>) plan.get("missingItems");
        assertTrue(missing.stream().anyMatch(m -> "CAL-A01".equals(m.get("uid"))));
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", true), h), Map.class);
    }

    @Test
    @Order(6)
    void strategyComparisonBreakevenBetweenYearlyAndMonthly() {
        Map cmp = rest.exchange(
                "/api/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1&frequencies=12",
                HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        double breakeven = Double.parseDouble(String.valueOf(cmp.get("breakevenFrequencyPerYear")));
        assertTrue(breakeven > 1 && breakeven < 12, "breakeven=" + breakeven);
        @SuppressWarnings("unchecked")
        List<Map> scenarios = (List<Map>) cmp.get("scenarios");
        assertEquals("SCHEDULED_TIERING", scenarios.get(0).get("cheaperStrategy"));
        assertEquals("RESTORE_PACKAGE", scenarios.get(1).get("cheaperStrategy"));
    }

    @Test
    @Order(7)
    void monthlyBillingAllocationsReconcile() {
        HttpHeaders h = auth();
        Map bill = rest.exchange("/api/cost/billing/generate", HttpMethod.POST,
                new HttpEntity<>(Map.of("billingMonth", "2026-11"), h), Map.class).getBody();
        assertEquals(Boolean.TRUE, bill.get("allocationCheck"));
        assertNotNull(bill.get("grandTotal"));
    }

    @Test
    @Order(8)
    void snapshotCapturesCatalogLineageAndDecisions() {
        Map snap = rest.exchange("/api/snapshots", HttpMethod.POST,
                new HttpEntity<>(auth()), Map.class).getBody();
        assertEquals("RV-2026Q4", snap.get("rateVersion"));
        assertTrue(((Number) snap.get("catalogObjects")).intValue() >= 10);
        assertTrue(((Number) snap.get("lineageEdges")).intValue() >= 9);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM archive_snapshot", Integer.class);
        assertNotNull(rows);
        assertTrue(rows > 0);
    }

    @Test
    @Order(9)
    void integrityBudgetStratifiedSamplingAndNonIndependentReplica() {
        HttpHeaders h = auth();
        Map[] policies = rest.exchange("/api/integrity/policies", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        assertNotNull(policies);
        assertEquals(5, policies.length);
        // 连续三个 RESTORE_TIER 周期（仅 1 个恢复包对象），均受预算约束
        Map prev = null;
        for (int seq = 1; seq <= 3; seq++) {
            Map cycle = rest.exchange("/api/integrity/cycles", HttpMethod.POST,
                    new HttpEntity<>(Map.of("tierCode", "RESTORE_TIER",
                            "strategy", "STRATIFIED_FIXED"), h), Map.class).getBody();
            assertNotNull(cycle);
            assertEquals(Boolean.TRUE, cycle.get("withinBudget"));
            assertEquals(1, ((Number) cycle.get("sampledObjects")).intValue());
            assertTrue(((Number) cycle.get("sampledBytes")).longValue()
                    <= ((Number) cycle.get("byteBudget")).longValue());
            prev = cycle;
        }
        // 第三周期同一副本重复检查 → 至少一个非独立样本，且不计入缺陷率点估计
        @SuppressWarnings("unchecked")
        List<Map> items = (List<Map>) prev.get("items");
        assertTrue(items.stream().anyMatch(i -> Boolean.FALSE.equals(i.get("independentSample"))));
        assertEquals(0.0, Double.parseDouble(String.valueOf(prev.get("defectRatePoint"))));
        // Wilson 区间合法：lower <= point <= upper
        double lo = Double.parseDouble(String.valueOf(prev.get("defectRateLower")));
        double up = Double.parseDouble(String.valueOf(prev.get("defectRateUpper")));
        assertTrue(lo <= 0.0 && 0.0 <= up, "wilson interval invalid");
    }

    @Test
    @Order(10)
    void integrityBitRotFreezesDeletionAndRepairReleases() {
        HttpHeaders h = auth();
        // 放宽 COLD 名额/预算保证命中目标对象
        rest.exchange("/api/integrity/policies", HttpMethod.POST, new HttpEntity<>(Map.of(
                "tierCode", "COLD", "maxObjectRatio", 1.0,
                "maxByteBudget", 5_000_000_000_000L, "samplingStrategy", "ADAPTIVE",
                "auditCycleDays", 30, "evidenceRotationCycleDays", 180), h), Map.class);
        rest.exchange("/api/integrity/faults/bit-rot", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "FEAT-A01", "replicaNo", 2), h), Map.class);
        Map cycle = rest.exchange("/api/integrity/cycles", HttpMethod.POST,
                new HttpEntity<>(Map.of("tierCode", "COLD", "strategy", "ADAPTIVE"), h), Map.class).getBody();
        assertNotNull(cycle.get("uncoveredProbability"));
        @SuppressWarnings("unchecked")
        List<Map> items = (List<Map>) cycle.get("items");
        assertTrue(items.stream().anyMatch(i -> "FEAT-A01".equals(i.get("uid"))
                && "DEFECT_BIT_ROT".equals(i.get("resultStatus"))));
        // 哈希不符立即冻结删除
        Map check = rest.exchange("/api/retention/deletion-check/FEAT-A01", HttpMethod.GET,
                new HttpEntity<>(h), Map.class).getBody();
        assertEquals("BLOCKED_INTEGRITY_FREEZE", check.get("verdict"));
        assertEquals(Boolean.TRUE, check.get("integrityFrozen"));
        // 修复后解除冻结
        Map[] open = rest.exchange("/api/integrity/defects?status=OPEN", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        Map bitRot = java.util.Arrays.stream(open)
                .filter(d -> "FEAT-A01".equals(d.get("uid")) && "BIT_ROT".equals(d.get("defectType")))
                .findFirst().orElseThrow();
        Map repaired = rest.exchange("/api/integrity/defects/" + bitRot.get("id") + "/repair",
                HttpMethod.POST, new HttpEntity<>(h), Map.class).getBody();
        assertEquals("REPAIRED", repaired.get("resultStatus"));
        Map check2 = rest.exchange("/api/retention/deletion-check/FEAT-A01", HttpMethod.GET,
                new HttpEntity<>(h), Map.class).getBody();
        assertNotEquals("BLOCKED_INTEGRITY_FREEZE", check2.get("verdict"));
        // 复原策略与故障
        rest.exchange("/api/integrity/policies", HttpMethod.POST, new HttpEntity<>(Map.of(
                "tierCode", "COLD", "maxObjectRatio", 0.40,
                "maxByteBudget", 300_000_000_000L, "samplingStrategy", "STRATIFIED_FIXED",
                "auditCycleDays", 30, "evidenceRotationCycleDays", 180), h), Map.class);
        rest.exchange("/api/integrity/faults/clear", HttpMethod.POST,
                new HttpEntity<>(h), Map.class);
    }

    @Test
    @Order(11)
    void integrityCoverageGapBlockedWithoutHealthySourceThenEcRebuild() {
        HttpHeaders h = auth();
        rest.exchange("/api/integrity/faults/lost", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "MASK-A01"), h), Map.class);
        Map gap = rest.exchange("/api/integrity/coverage/scan-gaps", HttpMethod.POST,
                new HttpEntity<>(h), Map.class).getBody();
        assertTrue(((Number) gap.get("blindObjects")).intValue() >= 1);
        Map[] coverage = rest.exchange("/api/integrity/coverage?tier=COLD", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        assertTrue(java.util.Arrays.stream(coverage)
                .anyMatch(o -> "MASK-A01".equals(o.get("uid")) && "BLIND".equals(o.get("coverageStatus"))));
        Map[] open = rest.exchange("/api/integrity/defects?status=OPEN", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        Map gapDefect = java.util.Arrays.stream(open)
                .filter(d -> "MASK-A01".equals(d.get("uid")) && "COVERAGE_GAP".equals(d.get("defectType")))
                .findFirst().orElseThrow();
        // 无健康副本：修复被 409 阻断，缺陷保持 OPEN
        org.springframework.http.ResponseEntity<Map> blocked = rest.exchange(
                "/api/integrity/defects/" + gapDefect.get("id") + "/repair",
                HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, blocked.getStatusCode());
        // 恢复片段后经 EC 重建修复
        rest.exchange("/api/integrity/faults/clear", HttpMethod.POST,
                new HttpEntity<>(h), Map.class);
        Map rebuilt = rest.exchange("/api/integrity/defects/" + gapDefect.get("id") + "/repair",
                HttpMethod.POST, new HttpEntity<>(h), Map.class).getBody();
        assertEquals("REPAIRED", rebuilt.get("resultStatus"));
        assertTrue(String.valueOf(rebuilt.get("source")).contains("EC_RECONSTRUCT"));
        Map[] ec = rest.exchange("/api/integrity/ec-fragments?uid=MASK-A01", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        assertNotNull(ec);
        assertEquals(0L, ((Number) ec[0].get("badFragments")).longValue());
    }
}
