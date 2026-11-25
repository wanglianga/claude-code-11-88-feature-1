package com.lakegov.web;

import com.lakegov.service.IntegrityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 完整性治理接口：读量预算、对象副本/EC 片段、双路线抽样审计、
 * 校验覆盖地图、缺陷台账与修复优先级。
 * 写操作仅 ADMIN；REVIEWER 只读由 AuthInterceptor 统一拦截。
 */
@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

    private final IntegrityService integrity;

    public IntegrityController(IntegrityService integrity) {
        this.integrity = integrity;
    }

    // ---------- 总览 ----------
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return integrity.stats();
    }

    // ---------- 读量预算策略 ----------
    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return integrity.policies();
    }

    @PostMapping("/policies")
    public Map<String, Object> updatePolicy(@RequestBody Map<String, Object> body) {
        return integrity.updatePolicy(
                (String) body.get("tierCode"),
                Double.parseDouble(body.get("maxObjectRatio").toString()),
                Long.parseLong(body.get("maxByteBudget").toString()),
                (String) body.get("samplingStrategy"),
                Integer.parseInt(body.get("auditCycleDays").toString()),
                Integer.parseInt(body.get("evidenceRotationCycleDays").toString()));
    }

    // ---------- 副本 / 纠删码片段 ----------
    @GetMapping("/replicas")
    public List<Map<String, Object>> replicas(@RequestParam(required = false) String uid) {
        return integrity.replicas(uid);
    }

    @GetMapping("/ec-fragments")
    public List<Map<String, Object>> ecFragments(@RequestParam(required = false) String uid) {
        return integrity.ecFragments(uid);
    }

    // ---------- 介质告警 ----------
    @GetMapping("/media-alerts")
    public List<Map<String, Object>> mediaAlerts() {
        return integrity.mediaAlerts();
    }

    @PostMapping("/media-alerts")
    public Map<String, Object> raiseAlert(@RequestBody Map<String, Object> body) {
        return integrity.raiseMediaAlert((String) body.get("mediaId"), (String) body.get("tierCode"),
                (String) body.get("severity"), (String) body.get("alertType"));
    }

    @PostMapping("/media-alerts/{id}/resolve")
    public Map<String, Object> resolveAlert(@PathVariable long id) {
        return integrity.resolveMediaAlert(id);
    }

    // ---------- 故障注入（演示） ----------
    @PostMapping("/faults/bit-rot")
    public Map<String, Object> injectBitRot(@RequestBody Map<String, Object> body) {
        Integer replicaNo = body.get("replicaNo") == null ? null
                : Integer.valueOf(body.get("replicaNo").toString());
        return integrity.injectBitRot((String) body.get("uid"), replicaNo);
    }

    @PostMapping("/faults/misplacement")
    public Map<String, Object> injectMisplacement(@RequestBody Map<String, Object> body) {
        Integer replicaNo = body.get("replicaNo") == null ? null
                : Integer.valueOf(body.get("replicaNo").toString());
        return integrity.injectMisplacement((String) body.get("uid"), replicaNo);
    }

    @PostMapping("/faults/lost")
    public Map<String, Object> injectLost(@RequestBody Map<String, Object> body) {
        Integer replicaNo = body.get("replicaNo") == null ? null
                : Integer.valueOf(body.get("replicaNo").toString());
        return integrity.injectLost((String) body.get("uid"), replicaNo);
    }

    @PostMapping("/faults/clear")
    public Map<String, Object> clearFaults() {
        return integrity.clearFaults();
    }

    // ---------- 审计周期 ----------
    @PostMapping("/cycles")
    public Map<String, Object> runCycle(@RequestBody Map<String, Object> body) {
        return integrity.runCycle((String) body.get("tierCode"),
                (String) body.get("strategy"));
    }

    @GetMapping("/cycles")
    public List<Map<String, Object>> cycles() {
        return integrity.cycles();
    }

    @GetMapping("/cycles/{id}")
    public Map<String, Object> cycleDetail(@PathVariable long id) {
        return integrity.cycleDetail(id);
    }

    // ---------- 覆盖地图 / 盲区 ----------
    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage(@RequestParam(required = false) String tier) {
        return integrity.coverageMap(tier);
    }

    @PostMapping("/coverage/scan-gaps")
    public Map<String, Object> scanGaps() {
        return integrity.scanCoverageGaps();
    }

    // ---------- 缺陷与修复 ----------
    @GetMapping("/defects")
    public List<Map<String, Object>> defects(@RequestParam(required = false) String status) {
        return integrity.defects(status);
    }

    @PostMapping("/defects/{id}/repair")
    public Map<String, Object> repair(@PathVariable long id) {
        return integrity.repairDefect(id);
    }

    @GetMapping("/repairs")
    public List<Map<String, Object>> repairs() {
        return integrity.repairVerifications();
    }
}
