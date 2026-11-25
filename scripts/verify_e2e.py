#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
科研影像湖仓存储治理平台 —— 端到端业务验证脚本（仅标准库）。
覆盖题目全部硬性场景：
  1. 对象目录与研究A数据量
  2. 血缘反向依赖闭包
  3. 保留策略：10年原始影像、算法包/校准延展、冻结/校验失败传播
  4. 恢复计划：72h预约、4h RTO、缺件（移除校准）阻断、恢复包校验
  5. 成本：GB月费、取回费、恢复计算、共享分摊总额=月账单
  6. 两路线盈亏平衡（年1次 vs 月1次）
  7. 项目退出共享对象保护
  8. 归档快照回放
用法: python3 verify_e2e.py [base_url]
"""
import json
import sys
import urllib.request
import urllib.error

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080") + "/api"
PASS, FAIL = [], []


def call(method, path, body=None, token=None, allow_fail=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if allow_fail:
            return {"__http_status__": e.code, "__body__": payload}
        raise AssertionError(f"{method} {path} -> {e.code}: {payload}")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


def money(x):
    return round(float(x), 2)


# ---------- 登录 ----------
tok = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})["token"]
H = tok
check("登录获取令牌", bool(tok))

# ---------- 1. 对象目录 ----------
objs = {o["uid"]: o for o in call("GET", "/catalog/objects", token=H)}
check("目录对象数=10", len(objs) == 10, f"actual={len(objs)}")
expect_sizes = {
    "RAW-IMG-A01": 2_000_000_000_000, "ANN-A01": 80_000_000_000,
    "CAL-A01": 12_000_000_000, "MASK-A01": 200_000_000_000,
    "FEAT-A01": 5_000_000_000, "RESULT-A01": 1_000_000_000,
}
for uid, size in expect_sizes.items():
    check(f"对象 {uid} 字节数={size/1e9:.0f}GB", objs[uid]["byteSize"] == size)
check("每个对象有 sha256 校验摘要", all(len(o["checksum"]) == 64 for o in objs.values()))

# ---------- 2. 依赖闭包 ----------
closure = call("GET", "/retention/closure/RESULT-A01", token=H)
cuids = {c["uid"] for c in closure}
expect_closure = {"RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                  "ANN-A01", "CAL-A01", "ALGO-PKG-01"}
check("RESULT-A01 完整依赖闭包=7个对象且含算法包/校准", cuids == expect_closure, str(sorted(cuids)))
depth = {c["uid"]: c["depth"] for c in closure}
check("闭包深度：RESULT=0, FEAT=1, MASK=2, RAW=3",
      depth["RESULT-A01"] == 0 and depth["FEAT-A01"] == 1
      and depth["MASK-A01"] == 2 and depth["RAW-IMG-A01"] == 3, str(depth))

# ---------- 3. 保留策略 ----------
call("POST", "/retention/recalculate", token=H)
ov = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("原始影像保留10年（2036-01-15）",
      str(ov["RAW-IMG-A01"]["baseDeadline"]) == "2036-01-15", str(ov["RAW-IMG-A01"]["baseDeadline"]))
check("标注与原始同期限10年", str(ov["ANN-A01"]["baseDeadline"]) == "2036-01-20")
# 算法包基础5年(2031)，延展到最后依赖结果期限：FEAT 2034 / MASK 2034
check("算法包至少保留至最后依赖结果期限届满（≥2034）",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]) >= "2034-02-05",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]))
check("校准证据同样延展（≥2034）",
      str(ov["CAL-A01"]["effectiveDeadline"]) >= "2034-02-01",
      str(ov["CAL-A01"]["effectiveDeadline"]))
check("派生物不早于源对象到期（MASK 截止≥RAW）",
      ov["MASK-A01"]["effectiveDeadline"] is not None
      and ov["MASK-A01"]["effectiveDeadline"] >= ov["RAW-IMG-A01"]["effectiveDeadline"])

# 争议冻结传播
hold = call("POST", "/retention/hold",
            {"uid": "RAW-IMG-A01", "litigationHold": True, "reason": "e2e冻结测试"}, token=H)
check("冻结传播到全部下游依赖结果",
      set(hold["propagatedTo"]) >= {"RAW-IMG-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(hold["propagatedTo"]))
ov2 = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("冻结对象无截止日（无限期保留）", ov2["RAW-IMG-A01"]["disposition"] == "BLOCKED_HOLD")
check("冻结下游结果同样不可销毁", ov2["RESULT-A01"]["disposition"] == "BLOCKED_HOLD")
call("POST", "/retention/hold",
     {"uid": "RAW-IMG-A01", "litigationHold": False, "reason": "解除冻结"}, token=H)

# 校验失败传播
cf = call("POST", "/retention/checksum-failed",
          {"uid": "CAL-A01", "reason": "e2e注入校验失败"}, token=H)
check("校验失败传播到 MASK/FEAT/RESULT",
      set(cf["propagatedTo"]) >= {"CAL-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(cf["propagatedTo"]))
call("POST", "/retention/checksum-ok", {"uid": "CAL-A01"}, token=H)

# ---------- 4. 恢复计划（年1次，提前72h，4h RTO） ----------
plan_y = call("POST", "/restore/plans",
              {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
               "frequencyPerYear": 1}, token=H)
check("年1次预约计划可满足", plan_y["status"] == "SATISFIABLE", str(plan_y.get("missingItems")))
check("预约提前72小时", plan_y["leadHours"] == 72)
check("预计恢复时间≤4小时 RTO", plan_y["estimatedHours"] <= 4,
      f"{plan_y['estimatedHours']}h")
check("闭包对象数=7", plan_y["closureCount"] == 7)
per_run = money(plan_y["perRunRetrievalFee"] + plan_y["perRunComputeFee"])
print(f"    单次恢复：取回费={money(plan_y['perRunRetrievalFee'])} "
      f"恢复计算费={money(plan_y['perRunComputeFee'])} 合计={per_run} 元")

# 72h 预取日程
sched = call("GET", "/restore/prefetch-schedule?targetUid=RESULT-A01", token=H)
check("72h窗口内完成全部预取", all(s["within72hLead"] for s in sched),
      f"累计={sched[-1]['windowEndHour']}h")

# ---------- 5. 月账单与分摊（未发生取回，仅存储） ----------
bill1 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("分摊总额等于月账单（allocationCheck）", bill1["allocationCheck"] is True,
      f"总额={bill1['grandTotal']}")
bills_a = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-A"][0]
bills_b = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-B"][0]
print(f"    月存储费：PROJ-A={money(bills_a['storageFee'])} PROJ-B={money(bills_b['storageFee'])} "
      f"合计={money(bill1['grandTotal'])} 元/月")
# 手算研究A独占+共享存储费（GB×费率），含算法包 2GB×0.45（A占60%份额）
manual_exclusive = (2000 * 0.120 + 80 * 0.450 + 12 * 0.450 + 5 * 0.120
                    + 1 * 0.450 + 30 * 0.180)
manual_mask_a = 200 * 0.120 * 0.70          # 掩膜A承担70%
manual_algo_a = 2 * 0.450 * 0.60            # 算法包A承担60%
manual_shared = manual_exclusive + manual_mask_a + manual_algo_a
check("PROJ-A 存储费与手工口径一致（含共享份额扣减）",
      abs(money(bills_a["storageFee"]) - money(manual_shared)) < 0.05,
      f"api={money(bills_a['storageFee'])} manual={money(manual_shared)}")
# 共享份额校验：MASK 30% 应计入 PROJ-B
mask_alloc = [a for a in bill1["objectAllocations"] if a["uid"] == "MASK-A01"][0]
share_b = [x for x in mask_alloc["allocations"] if x["projectId"] == 2]
check("共享掩膜按冻结份额 30% 分摊给 PROJ-B",
      bool(share_b) and abs(float(share_b[0]["share"]) - 0.30) < 1e-6)
# 份额之和=1
for a in bill1["objectAllocations"]:
    if len(a["allocations"]) > 1:
        s = sum(float(x["share"]) for x in a["allocations"])
        check(f"共享对象 {a['uid']} 份额之和=1", abs(s - 1.0) < 1e-9)

# ---------- 6. 执行恢复计划，取回费/恢复计算入账 ----------
exe = call("POST", f"/restore/plans/{plan_y['planId']}/execute", token=H)
check("恢复计划执行成功且在RTO内", exe["status"] == "EXECUTED" and exe["completedWithinRto"])
check("恢复对象数=7", exe["restoredCount"] == 7)
bill2 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
b2a = [b for b in bill2["bills"] if b["projectCode"] == "PROJ-A"][0]
check("取回费已计入月账单", money(b2a["retrievalFee"]) > 0, f"{b2a['retrievalFee']}")
check("恢复计算费已计入月账单", money(b2a["restoreComputeFee"]) > 0, f"{b2a['restoreComputeFee']}")
check("含取回后分摊核对仍通过", bill2["allocationCheck"] is True)
annual_storage = money(bill2["grandTotal"]) * 12
print(f"    年存储费（两项目合计口径）≈{annual_storage} 元；"
      f"研究A单次恢复合计={per_run} 元，年1次取回年成本={per_run} 元")

# ---------- 7. 两路线盈亏平衡 ----------
cmp1 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1", token=H)
sc1 = cmp1["scenarios"][0]
check("年1次：按需预取路线更省",
      sc1["cheaperStrategy"] == "SCHEDULED_TIERING",
      f"按需={sc1['scheduledAnnualFee']} 恢复包={sc1['packageAnnualFee']}")
cmp12 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=12", token=H)
sc12 = cmp12["scenarios"][0]
check("月1次：恢复包路线更省（额外占用低于重复取回）",
      sc12["cheaperStrategy"] == "RESTORE_PACKAGE",
      f"按需={sc12['scheduledAnnualFee']} 恢复包={sc12['packageAnnualFee']}")
print(f"    盈亏平衡频次≈{cmp1['breakevenFrequencyPerYear']} 次/年；"
      f"恢复包月费={cmp1['packageMonthlyFee']} 元（30GB×0.18）")
be = float(cmp1["breakevenFrequencyPerYear"])
check("盈亏平衡点落在年1次与月1次之间", 1 < be < 12, f"f*={be}")

# ---------- 8. 故意移除校准文件 → 阻断 ----------
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": False}, token=H)
bad = call("POST", "/restore/plans",
           {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
            "frequencyPerYear": 1}, token=H)
check("缺校准件时计划启动前标记 UNSATISFIABLE", bad["status"] == "UNSATISFIABLE")
missing_uids = {m["uid"] for m in bad["missingItems"]}
check("缺件清单列出 CAL-A01", "CAL-A01" in missing_uids, str(missing_uids))
blocked = call("POST", f"/restore/plans/{bad['planId']}/execute", token=H, allow_fail=True)
check("不可满足计划执行被阻断（HTTP 409）", blocked.get("__http_status__") == 409,
      str(blocked.get("__http_status__")))
# 恢复包校验同样失败
pv = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("恢复包校验失败并指出校准缺件", pv["valid"] is False and
      any(m["uid"] == "CAL-A01" for m in pv["missingItems"]))
# 恢复后重新可满足
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": True}, token=H)
pv2 = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("校准恢复后恢复包校验通过", pv2["valid"] is True)
good = call("POST", "/restore/plans",
            {"targetUid": "RESULT-A01", "strategy": "RESTORE_PACKAGE",
             "frequencyPerYear": 12}, token=H)
check("恢复包路线月频计划可满足且≤4h",
      good["status"] == "SATISFIABLE" and good["estimatedHours"] <= 4)

# 算法包缺失场景：仅结果文件迁移，哈希通过也不可复现
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": False}, token=H)
no_algo = call("POST", "/restore/plans",
               {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
                "frequencyPerYear": 1}, token=H)
check("缺算法包时即使哈希通过也判定不可复现",
      any(m["category"] == "SOFTWARE_PACKAGE" for m in no_algo["missingItems"]))
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": True}, token=H)

# ---------- 9. 项目退出与共享保护 ----------
# 研究A退出，但其共享掩膜/算法包仍被活跃的研究B引用 → 不得整体删除
wd = call("POST", "/projects/PROJ-A/withdraw", {"reason": "e2e项目退出"}, token=H)
protected = {p["uid"] for p in wd["protectedSharedObjects"]}
check("项目A退出后共享掩膜/算法包被研究B引用保护，不得整体删除",
      {"MASK-A01", "ALGO-PKG-01"} <= protected, str(protected))
check("项目A独占对象标记退出（RAW-IMG-A01）", "RAW-IMG-A01" in set(wd["withdrawnObjects"]))
check("研究B对象不受项目A退出影响", "FEAT-B01" not in set(wd["withdrawnObjects"]))
dc = call("GET", "/retention/deletion-check/MASK-A01", token=H)
check("共享掩膜销毁检查=BLOCKED_SHARED_REFERENCE",
      dc["verdict"] == "BLOCKED_SHARED_REFERENCE", dc["verdict"])
call("POST", "/projects/PROJ-A/reactivate", token=H)

# ---------- 10. 归档快照回放 ----------
snap = call("POST", "/snapshots", token=H)
check("快照固定目录水位/依赖/费率版本",
      snap["catalogObjects"] >= 10 and snap["lineageEdges"] >= 9
      and snap["rateVersion"] == "RV-2026Q4", str(snap))
replay = call("GET", f"/snapshots/{snap['snapshotId']}/replay", token=H)
decisions = replay["decisions"] if isinstance(replay["decisions"], list) else []
kinds = {d.get("kind") for d in decisions if isinstance(d, dict)}
check("快照可回放保留/冻结/迁移/退出等治理决定",
      {"RETENTION", "LITIGATION_HOLD", "CHECKSUM_FAILED", "PROJECT_WITHDRAWN"} <= kinds,
      str(kinds))

# ---------- 11. 完整性治理：读量预算 / 双路线抽样 / 缺陷冻结 / 修复优先级 ----------
# 11.1 预算策略与对象完整性账
pol = {p["tierCode"]: p for p in call("GET", "/integrity/policies", token=H)}
check("完整性预算覆盖全部5个存储层", len(pol) == 5, str(sorted(pol)))
check("COLD 层对象比例上限=40%", abs(float(pol["COLD"]["maxObjectRatio"]) - 0.40) < 1e-9)
check("COLD 层字节预算=300GB 硬顶", pol["COLD"]["maxByteBudget"] == 300_000_000_000)
check("ARCHIVE 默认自适应路线", pol["ARCHIVE"]["samplingStrategy"] == "ADAPTIVE")
check("不可替代证据轮换周期长于常规周期",
      int(pol["COLD"]["evidenceRotationCycleDays"]) > int(pol["COLD"]["auditCycleDays"]))
st = call("GET", "/integrity/stats", token=H)
check("不可替代证据最小清单=4（2原始影像+标注+校准）", st["evidenceObjects"] == 4, str(st["evidenceObjects"]))
check("EC 纠删码对象=1（MASK-A01）", st["ecObjects"] == 1)
ec = call("GET", "/integrity/ec-fragments?uid=MASK-A01", token=H)
check("MASK-A01 为 EC(4+2) 共6片段", len(ec) == 6 and ec[0]["k"] == 4 and ec[0]["m"] == 2)
check("初始 EC 片段全部健康且在容忍度内",
      all(f["status"] == "HEALTHY" for f in ec) and ec[0]["withinTolerance"] is True)
alerts = call("GET", "/integrity/media-alerts", token=H)
check("介质告警含 DISK-COLD-02 HIGH（自适应输入）",
      any(a["mediaId"] == "DISK-COLD-02" and a["severity"] == "HIGH" and a["active"] for a in alerts))

# 11.2 固定比例路线受预算约束（RESTORE_TIER 仅1对象，每周期必抽，验证副本去重）
r1 = call("POST", "/integrity/cycles", {"tierCode": "RESTORE_TIER", "strategy": "STRATIFIED_FIXED"}, token=H)
r2 = call("POST", "/integrity/cycles", {"tierCode": "RESTORE_TIER", "strategy": "STRATIFIED_FIXED"}, token=H)
r3 = call("POST", "/integrity/cycles", {"tierCode": "RESTORE_TIER", "strategy": "STRATIFIED_FIXED"}, token=H)
for r in (r1, r2, r3):
    check(f"RESTORE_TIER 周期#{r['cycleSeq']} 仅抽1对象且不超字节预算",
          r["sampledObjects"] == 1 and r["withinBudget"] is True
          and r["sampledBytes"] <= r["byteBudget"])
check("同一副本第三次重复检查不记为独立样本",
      any(i["independentSample"] is False for i in r3["items"]),
      str([(i["uid"], i["independentSample"]) for i in r3["items"]]))
check("非独立样本被排除后缺陷率点估计=0（不读取全量）", float(r3["defectRatePoint"]) == 0.0)
# COLD 固定比例：抽样对象数不超过 floor(总体×比例)
cold_fixed = call("POST", "/integrity/cycles",
                  {"tierCode": "COLD", "strategy": "STRATIFIED_FIXED"}, token=H)
cap_cold = int(cold_fixed["populationObjects"] * float(cold_fixed["objectRatioLimit"]))
check("COLD 固定比例抽样数≤声明比例名额且字节不超预算",
      cold_fixed["sampledObjects"] <= max(1, cap_cold)
      and cold_fixed["sampledBytes"] <= cold_fixed["byteBudget"]
      and cold_fixed["withinBudget"] is True,
      f"sampled={cold_fixed['sampledObjects']} cap={cap_cold}")
check("固定比例路线给出 Wilson 缺陷率区间且含点估计",
      float(cold_fixed["defectRateLower"]) <= float(cold_fixed["defectRatePoint"])
      <= float(cold_fixed["defectRateUpper"]))

# 11.3 位腐蚀检出 → 立即冻结删除 → 血缘事件 → 修复解除（自适应路线）
call("POST", "/integrity/policies", {
    "tierCode": "COLD", "maxObjectRatio": 1.0, "maxByteBudget": 5_000_000_000_000,
    "samplingStrategy": "ADAPTIVE", "auditCycleDays": 30, "evidenceRotationCycleDays": 180}, token=H)
call("POST", "/integrity/faults/bit-rot", {"uid": "FEAT-A01", "replicaNo": 2}, token=H)
ada = call("POST", "/integrity/cycles", {"tierCode": "COLD", "strategy": "ADAPTIVE"}, token=H)
check("自适应路线报告未覆盖概率字段", ada["uncoveredProbability"] is not None
      and 0.0 <= float(ada["uncoveredProbability"]) <= 1.0)
bit_items = [i for i in ada["items"] if i["uid"] == "FEAT-A01" and i["resultStatus"] == "DEFECT_BIT_ROT"]
check("自适应抽样定向命中 DISK-COLD-02 告警媒介并检出 FEAT-A01 位腐蚀",
      bool(bit_items), str([(i["uid"], i["resultStatus"], i["sampleBasis"]) for i in ada["items"]]))
check("检出缺陷后缺陷率点估计>0", float(ada["defectRatePoint"]) > 0.0)
open_def = call("GET", "/integrity/defects?status=OPEN", token=H)
fd = [d for d in open_def if d["uid"] == "FEAT-A01" and d["defectType"] == "BIT_ROT"]
check("位腐蚀缺陷登记并默认冻结删除", bool(fd) and fd[0]["freezeDeletion"] is True
      and isinstance(fd[0]["repairPriority"], int))
dc = call("GET", "/retention/deletion-check/FEAT-A01", token=H)
check("哈希不符立即冻结删除（BLOCKED_INTEGRITY_FREEZE）",
      dc["verdict"] == "BLOCKED_INTEGRITY_FREEZE" and dc["integrityFrozen"] is True)
fo = {o["uid"]: o for o in call("GET", "/catalog/objects", token=H)}
check("对象目录校验状态=FAILED 且删除冻结标记=true",
      fo["FEAT-A01"]["checksumStatus"] == "FAILED" and fo["FEAT-A01"]["deletionFrozen"] is True)
rep = call("POST", f"/integrity/defects/{fd[0]['id']}/repair", {}, token=H)
check("健康副本重建修复成功并解除删除冻结",
      rep["resultStatus"] == "REPAIRED" and rep["source"] == "HEALTHY_REPLICA"
      and rep["deletionFrozenCleared"] is True)
dc2 = call("GET", "/retention/deletion-check/FEAT-A01", token=H)
check("修复后不再被完整性冻结阻断", dc2["verdict"] != "BLOCKED_INTEGRITY_FREEZE")
repairs = call("GET", "/integrity/repairs", token=H)
check("修复验证台账留痕 REPAIRED 且摘要匹配",
      any(r["uid"] == "FEAT-A01" and r["resultStatus"] == "REPAIRED" for r in repairs))
# 恢复 COLD 预算策略
call("POST", "/integrity/policies", {
    "tierCode": "COLD", "maxObjectRatio": 0.40, "maxByteBudget": 300_000_000_000,
    "samplingStrategy": "STRATIFIED_FIXED", "auditCycleDays": 30, "evidenceRotationCycleDays": 180}, token=H)

# 11.4 错误分层副本检出与修复（放宽 WARM 名额/预算保证抽中）
call("POST", "/integrity/policies", {
    "tierCode": "WARM", "maxObjectRatio": 1.0, "maxByteBudget": 200_000_000_000,
    "samplingStrategy": "STRATIFIED_FIXED", "auditCycleDays": 30, "evidenceRotationCycleDays": 180}, token=H)
call("POST", "/integrity/faults/misplacement", {"uid": "RESULT-A01", "replicaNo": 1}, token=H)
warm_c = call("POST", "/integrity/cycles", {"tierCode": "WARM", "strategy": "STRATIFIED_FIXED"}, token=H)
check("可疑副本优先校验，检出 RESULT-A01 错误分层副本",
      any(i["uid"] == "RESULT-A01" and i["resultStatus"] == "DEFECT_MISPLACED" for i in warm_c["items"]))
md = [d for d in call("GET", "/integrity/defects?status=OPEN", token=H)
      if d["uid"] == "RESULT-A01" and d["defectType"] == "MISPLACED_REPLICA"]
check("错误分层缺陷登记", bool(md))
mrep = call("POST", f"/integrity/defects/{md[0]['id']}/repair", {}, token=H)
check("错误分层副本经健康副本修复", mrep["resultStatus"] == "REPAIRED")
call("POST", "/integrity/policies", {
    "tierCode": "WARM", "maxObjectRatio": 0.50, "maxByteBudget": 20_000_000_000,
    "samplingStrategy": "STRATIFIED_FIXED", "auditCycleDays": 30, "evidenceRotationCycleDays": 180}, token=H)

# 11.5 覆盖盲区：全部片段丢失 → 盲区缺陷；无健康源修复阻断；恢复后 EC 重建
call("POST", "/integrity/faults/lost", {"uid": "MASK-A01"}, token=H)
gap = call("POST", "/integrity/coverage/scan-gaps", {}, token=H)
check("全片段丢失扫描出覆盖盲区并登记 COVERAGE_GAP",
      gap["blindObjects"] >= 1 and "MASK-A01" in gap["openedDefects"], str(gap))
cov = {o["uid"]: o for o in call("GET", "/integrity/coverage?tier=COLD", token=H)}
check("覆盖地图将 MASK-A01 标记为 BLIND", cov["MASK-A01"]["coverageStatus"] == "BLIND")
gd = [d for d in call("GET", "/integrity/defects?status=OPEN", token=H)
      if d["uid"] == "MASK-A01" and d["defectType"] == "COVERAGE_GAP"][0]
blocked = call("POST", f"/integrity/defects/{gd['id']}/repair", {}, token=H, allow_fail=True)
check("无健康副本/不足 k 片段时修复被阻断（HTTP 409，缺陷保持 OPEN）",
      blocked.get("__http_status__") == 409)
still_open = [d for d in call("GET", "/integrity/defects?status=OPEN", token=H) if d["id"] == gd["id"]]
check("阻断事务回滚后盲区缺陷仍为 OPEN", bool(still_open))
call("POST", "/integrity/faults/clear", {}, token=H)
grep2 = call("POST", f"/integrity/defects/{gd['id']}/repair", {}, token=H)
check("片段恢复后经 EC 重建（k=4）修复盲区",
      grep2["resultStatus"] == "REPAIRED" and "EC_RECONSTRUCT" in grep2["source"])
ec2 = call("GET", "/integrity/ec-fragments?uid=MASK-A01", token=H)
check("修复后 EC 坏片段=0、剩余容忍度=2",
      ec2[0]["badFragments"] == 0 and ec2[0]["toleranceRemaining"] == 2 and ec2[0]["withinTolerance"] is True)
cov2 = {o["uid"]: o for o in call("GET", "/integrity/coverage?tier=COLD", token=H)}
check("修复后 MASK-A01 不再是盲区", cov2["MASK-A01"]["coverageStatus"] != "BLIND")

# 11.6 收尾：无未修复缺陷、无残留故障、预算策略复原
call("POST", "/integrity/faults/clear", {}, token=H)
left_open = call("GET", "/integrity/defects?status=OPEN", token=H)
check("完整性缺陷全部修复（OPEN=0）", len(left_open) == 0, str([d["uid"] for d in left_open]))
pol2 = {p["tierCode"]: p for p in call("GET", "/integrity/policies", token=H)}
check("预算策略复原 COLD 40%/300GB、WARM 50%/20GB",
      abs(float(pol2["COLD"]["maxObjectRatio"]) - 0.40) < 1e-9
      and pol2["COLD"]["maxByteBudget"] == 300_000_000_000
      and abs(float(pol2["WARM"]["maxObjectRatio"]) - 0.50) < 1e-9)
# 审计周期历史可追溯且全部在预算内
hist = call("GET", "/integrity/cycles", token=H)
check("审计周期历史完整且无超预算周期", len(hist) >= 6 and all(c["withinBudget"] for c in hist))

# ---------- 汇总 ----------
print("\n================ 验证汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项：")
    for f in FAIL:
        print(" -", f)
    sys.exit(1)
print("全部场景验证通过")
