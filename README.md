# 科研影像湖仓存储治理平台（Research Imaging Lakehouse Governance）

面向科研影像湖仓的存储治理平台，覆盖**对象目录、血缘 DAG、保留策略、恢复校验、成本分摊、归档快照、项目审阅、完整性审计与读量预算**八大能力。
技术栈：**Vue 3 + Vite + Pinia**（前端）、**Spring Boot 3 + Spring JDBC**（后端）、**PostgreSQL 18**（数据库，递归 CTE 实现血缘闭包与影响传播）。

## 1. 业务能力与题目对应

| 题目要求 | 实现位置 |
| --- | --- |
| 对象目录：对象ID/字节数/校验摘要/层/创建访问时间/责任项目/保留截止日 | `data_object` 表 + 「对象目录与分层」界面 |
| 血缘 DAG：输入输出方向、版本、处理配方、共享引用 | `lineage_edge` 表 + 递归 CTE（`LineageService`）+ SVG DAG 界面 |
| 保留策略：按类别×协议算最早可删除日；源未到期/冻结时派生物不得先销毁 | `retention_policy` + `RetentionService.recalculate`（祖先截止日传播） |
| 算法包/校准证据至少保留到最后依赖结果期限届满 | 同上，对 `SOFTWARE_PACKAGE`/`CALIBRATION` 做下游期限延展 |
| 恢复计划：沿血缘反向求依赖闭包，核对摘要/软件包/目录可用性 | `RestoreService.createPlan` |
| 4 小时 RTO、提前 72h 预约；缺件/超时启动前标记不可满足并列缺项 | 计划状态 `SATISFIABLE/UNSATISFIABLE`，执行阻断返回 409 |
| 只迁移结果无算法包，哈希通过也不可宣称复现 | 闭包缺 `SOFTWARE_PACKAGE` 即判缺件 |
| 两路线：预约分层 DAG 预取 vs 可校验恢复包（4h RTO 层） | `SCHEDULED_TIERING` / `RESTORE_PACKAGE` 双策略 + 盈亏平衡频次 |
| 成本账：日字节时长×层费率、取回费、恢复计算费 | `cost_daily`、`retrieval_event`、`CostService` |
| 独占归项目、共享按冻结引用/使用份额分摊，分摊总额=月账单 | `object_share` + `monthly_bill.allocation_check` 核对 |
| 归档快照：目录水位+依赖+费率版本，可回放迁移/保留/计费理由 | `archive_snapshot` + 「归档快照回放」界面 |
| 项目退出后共享对象仍被引用不得整体删除 | `ProjectService.withdraw` → `BLOCKED_SHARED_REFERENCE` |
| 校验失败/规则变化/争议冻结传播到所有依赖结果并保留原归档决定 | `governance_event` + 正向影响传播；快照不可变 |
| 完整性五本账：对象完整性账/校验覆盖地图/纠删码片段状态/读量预算/修复优先级 | `object_replica`、`audit_cycle`、`audit_sample_item`、`integrity_budget_policy`、`integrity_defect` + `IntegrityService` |
| 每存储层每周期校验范围不得超过声明的对象比例与字节预算 | `integrity_budget_policy` + 大对象部分扫描（`audit_verified_bytes` 跨周期分摊） |
| 双抽样路线：固定比例分层（可预期、反应慢）vs 自适应（告警/既往缺陷/证据价值，报告未覆盖概率） | `STRATIFIED_FIXED` / `ADAPTIVE`，均给 Wilson 95% 缺陷率区间且不读全量 |
| 不可替代证据最小清单在更长轮换周期内覆盖 | `irreplaceable_evidence` + `evidence_rotation_cycle_days`（180/360 天 vs 常规 30/90 天） |
| 位腐蚀/错误分层副本/覆盖盲区检出；哈希不符立即冻结删除 | `BIT_ROT`/`MISPLACED_REPLICA`/`COVERAGE_GAP` → `deletion_frozen`，销毁检查 `BLOCKED_INTEGRITY_FREEZE` |
| 同一副本重复检查不记为独立样本；修复按健康副本/EC k 片段重建并留痕 | `audit_sample_item.independent_sample` + `repair_verification` |

## 2. 目录结构

```
.
├── backend/            Spring Boot 3（Java 17 字节码，容器内 JDK 21/25 可运行）
│   ├── src/main/resources/db/schema.sql   数据库结构
│   ├── src/main/resources/db/data.sql     研究A/B 种子数据（幂等）
│   └── src/main/java/com/lakegov/         config/auth/web/service
├── frontend/           Vue 3 + Vite 八界面
├── scripts/verify_e2e.py  端到端业务验证脚本（仅标准库）
└── docker-compose.yml  一键启动 postgres + backend + frontend
```

## 3. 一键启动（Docker Compose）

```bash
docker compose up --build
# 前端 http://localhost:8088
# 后端 http://localhost:8080
# PostgreSQL localhost:5432 （库/用户/密码 ccdev/ccdev/ccdev）
```

容器首次启动自动建表并写入研究 A/B 种子数据、重算保留截止日。

## 4. 本地开发启动（容器内预装运行时）

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码 `ccdev`。

```bash
# 后端（JDK 25 + Maven）
cd backend
mvn -DskipTests spring-boot:run          # http://127.0.0.1:8080，自动建表+种子

# 前端（Node 22）
cd frontend
npm install --cache ./.npm-cache         # 宿主 npm 缓存权限受限时使用本地缓存
npm run dev                              # http://127.0.0.1:5173 （/api 代理到 8080）
```

## 5. 测试账号

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | 平台管理员（全部操作） |
| reviewer | review123 | 项目审阅人 |

账号仅用于隔离开发/测试，不是生产配置。

## 6. 验证方式

### 6.1 自动化端到端验证（推荐）

后端启动后执行：

```bash
python3 scripts/verify_e2e.py
```

脚本覆盖 90 项断言，包括：

1. 研究 A 数据量（原始 2TB、标注 80GB、校准 12GB、掩膜 200GB、特征 5GB、结果 1GB）与 sha256 摘要；
2. `RESULT-A01` 反向依赖闭包 = 7 个对象（含校准与算法包），深度正确；
3. 原始影像保留 10 年（2036-01-15）；算法包/校准延展到最后依赖结果期限；
4. 争议冻结、校验失败沿血缘传播到全部下游结果；
5. 年 1 次预约计划：72h 提前量、3h 预计恢复 ≤ 4h RTO、闭包 7 对象；
6. 月账单存储费手工口径核对、共享份额（掩膜 70/30、算法包 60/40）、`allocationCheck=true`；
7. 执行恢复后取回费/恢复计算费入账；
8. **年 1 次时预约分层更省；月 1 次时恢复包更省**，输出盈亏平衡频次（约 3.83 次/年）；
9. 故意移除校准文件 → 计划 `UNSATISFIABLE`、执行 409 阻断、恢复包校验失败；恢复后复原；
10. 移除算法包 → 即使哈希通过也判定不可复现；
11. 研究 A 退出 → 共享掩膜/算法包被研究 B 引用保护，不得整体删除；
12. 归档快照固定目录水位/9 条血缘边/费率版本，并可回放全部治理决定；
13. **完整性治理**：5 层预算策略与对象比例/字节硬顶；固定比例与自适应双路线均输出 Wilson 缺陷率区间；
    同一副本三次重复检查第三次起记为非独立样本；自适应报告未覆盖概率；
    位腐蚀检出即冻结删除（`BLOCKED_INTEGRITY_FREEZE`）并经健康副本修复解除；
    错误分层副本可疑优先校验；全片段丢失形成覆盖盲区、无健康源修复 409 阻断、恢复后 EC(4+2) 重建；
    大对象（2TB 原始影像）按字节预算部分扫描、跨周期轮换，全部周期 `withinBudget=true`。

### 6.2 界面手工验证路径

1. 登录后进入「恢复计划与校验」，点击「移除/恢复校准文件 CAL-A01」→「创建计划并检查」，观察红色 `UNSATISFIABLE` 与缺项清单，执行按钮禁用；
2. 恢复校准后创建 `RESTORE_PACKAGE` 路线、年频次 12 的计划，观察可满足；
3. 「成本账与分摊」生成 2026-11 账单，观察两项目费用、共享份额标签与分摊核对标签；对比表展示年 1 次/季 1 次/月 1 次的两路线费用；
4. 「保留策略与传播」对 `RAW-IMG-A01` 点「争议冻结」，观察下游 `RESULT-A01` 同步变为冻结阻断；
5. 「项目审阅」对 PROJ-A 点「项目退出」，观察共享对象保护清单；
6. 「归档快照回放」生成快照并切换页签回放当时的保留/冻结/迁移/退出决定；
7. 「完整性审计与预算」先点「注入位腐蚀」（如 FEAT-A01）→ 对 COLD 层「运行审计周期」，观察缺陷率 Wilson 区间、
   缺陷台账的删除冻结标签与覆盖地图变红；在缺陷台账点「修复并重校验」观察冻结解除；
   再点「注入片段丢失（盲区）」→「扫描覆盖盲区」观察 MASK-A01 变 BLIND、无健康源修复被阻断；
   「清除全部故障」后经 EC(4+2) 重建，剩余容忍度恢复为 2。

## 7. 计费口径与样例数值（费率版本 RV-2026Q4）

- 计量：1 GB = 10⁹ 字节；月按 30 天；日存储费 = GB × 层 GB·月费率 / 30。
- 研究 A 完整闭包单次按需恢复（年 1 次场景）：取回费约 **¥12.16** + 恢复计算费约 **¥4.98** = **¥17.14/次**。
- 30GB 恢复包驻留 RESTORE_TIER（¥0.18/GB·月）：月费 **¥5.40**，年费 **¥64.80**。
- 盈亏平衡：当年频次 > 约 **3.83 次/年** 时，恢复包路线的额外占用成本低于重复取回成本；即题设年 1 次预约取回更省，改为月 1 次后恢复包更省。
- 全部费率集中在 `storage_tier` 表，调整后重新生成账单即可，快照会固化当时费率版本。

## 8. 实际验证结论（2026-11-25 执行）

- 后端 `mvn -DskipTests package` 成功产出 `target/lakegov-backend-1.0.0.jar`；`mvn test` 集成测试 **11/11 通过**；
- 前端 `npm run build` 成功（100 modules transformed，新增第 9 个视图 `IntegrityView`），`npm run preview` 静态服务 HTTP 200；
- `python3 scripts/verify_e2e.py` **90 项断言全部通过**，完整输出见 `docs/verify_e2e_output.txt`；
- RBAC：reviewer 只读账号 GET 返回 200、完整性写操作（审计/故障注入/修复）返回 403；无令牌 401；admin 全部操作可用；
- 关键测算实测：单次按需恢复 **¥17.145**（取回 12.165 + 计算 4.98）、恢复包月费 **¥5.40**、盈亏平衡 **3.83 次/年**；年 1 次预约分层更省（¥17.15 vs ¥65.01），月 1 次恢复包更省（¥67.32 vs ¥205.74）；
- 完整性域实测：COLD 层 5 对象按 40% 名额抽 2、扫描字节始终 ≤ 300GB 预算；自适应路线对 DISK-COLD-02 告警媒介定向命中并检出 FEAT-A01 位腐蚀，缺陷率点估计 >0、Wilson 区间包含点估计；位腐蚀对象删除检查返回 `BLOCKED_INTEGRITY_FREEZE`，健康副本修复后解除；MASK-A01 全片段丢失被判 BLIND 且无健康源时修复 409 阻断，恢复后 EC(4+2) 重建、剩余容忍度回到 2；同一恢复包副本第三次重复检查记为非独立样本；
- 本容器无 docker 守护进程，Dockerfile/Compose 已交付但未在本机构建，已用预装 JDK 25 / Node 22 / PostgreSQL 18 完成等价验证。

## 9. 完整性治理设计要点

- **五本账**：对象完整性账（`data_object` 完整性分级 + `object_replica` 副本/EC 片段）、校验覆盖地图（`audit_sample_item` 聚合为 COVERED/DUE/OVERDUE/BLIND）、纠删码片段状态（EC k+m 容忍度 `toleranceRemaining`）、读量预算（`integrity_budget_policy` 每层声明比例与字节硬顶）、修复优先级（`integrity_defect.repair_priority`，证据/严重度/复发加权）。
- **不读全量对象的缺陷率估计**：以独立样本量 n 与缺陷数 d 计算 Wilson 95% 得分区间 `[lower, upper]`；同一副本重复检查 `independent_sample=false`，不进入 n，避免把重复观测误当独立样本。
- **字节预算硬约束与部分扫描**：超大对象（如 2TB 原始影像）单周期只扫描预算内一段，`audit_verified_bytes` 跨周期累计，完成全量轮换后清零；因此每个周期 `sampled_bytes ≤ byte_budget` 恒成立，且大对象在多个证据轮换周期内被完整覆盖。
- **两条路线互补**：固定比例分层覆盖频率可预期但对新故障反应慢；自适应按介质 SMART 告警、既往缺陷、证据价值加权，发现效率高但必须输出 `uncoveredProbability`（未被定向总体中本周期未抽中的比例），如实披露盲区概率。
- **删除冻结闭环**：哈希不符立即置 `checksum_status=FAILED` 与 `deletion_frozen=true`，沿血缘写 `INTEGRITY_DEFECT` 事件并传播下游；销毁检查返回 `BLOCKED_INTEGRITY_FREEZE`；仅当缺陷经健康副本或 EC 重建修复并重校验后才解除冻结。
