-- ============================================================
-- 科研影像湖仓存储治理平台 - 数据库结构
-- PostgreSQL 18
-- ============================================================

-- ---------- 应用用户（演示用，非生产配置） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN','REVIEWER')),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 项目 ----------
CREATE TABLE IF NOT EXISTS project (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(256) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','WITHDRAWN')),
    withdrawn_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 存储层与费率版本 ----------
CREATE TABLE IF NOT EXISTS storage_tier (
    code                    VARCHAR(32) PRIMARY KEY,
    name                    VARCHAR(64) NOT NULL,
    gb_month_rate           NUMERIC(12,6) NOT NULL,   -- 元 / GB·月
    retrieval_gb_rate       NUMERIC(12,6) NOT NULL,   -- 取回费 元/GB
    restore_compute_gb_rate NUMERIC(12,6) NOT NULL,   -- 恢复计算费 元/GB
    retrieval_hours         NUMERIC(8,2)  NOT NULL,   -- 该层取回耗时（小时）
    rto_four_hour_capable   BOOLEAN NOT NULL DEFAULT false, -- 是否满足4h恢复目标
    rate_version            VARCHAR(32) NOT NULL DEFAULT 'RV-2026Q4',
    description             VARCHAR(256)
);

-- ---------- 对象目录 ----------
CREATE TABLE IF NOT EXISTS data_object (
    id                      BIGSERIAL PRIMARY KEY,
    object_uid              VARCHAR(64) NOT NULL UNIQUE,
    name                    VARCHAR(256) NOT NULL,
    category                VARCHAR(48) NOT NULL,
    byte_size               BIGINT NOT NULL CHECK (byte_size >= 0),
    checksum                VARCHAR(128) NOT NULL,      -- sha256
    checksum_status         VARCHAR(16) NOT NULL DEFAULT 'OK'
                            CHECK (checksum_status IN ('OK','FAILED','UNVERIFIED')),
    tier_code               VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    owning_project_id       BIGINT NOT NULL REFERENCES project(id),
    created_at              TIMESTAMP NOT NULL,
    last_accessed_at        TIMESTAMP NOT NULL,
    base_retention_deadline DATE,                       -- 策略基础保留截止日
    retention_deadline      DATE,                       -- 经血缘/冻结传播后的有效截止日
    litigation_hold         BOOLEAN NOT NULL DEFAULT false,
    software_version        VARCHAR(64),                -- 算法包/派生处理的软件版本
    recipe                  TEXT,                       -- 处理配方
    directory_available     BOOLEAN NOT NULL DEFAULT true,
    is_restore_package      BOOLEAN NOT NULL DEFAULT false,
    package_target_uid      VARCHAR(64),                -- 恢复包对应的复现目标
    withdrawn               BOOLEAN NOT NULL DEFAULT false,
    note                    VARCHAR(512)
);
CREATE INDEX IF NOT EXISTS idx_object_project ON data_object(owning_project_id);
CREATE INDEX IF NOT EXISTS idx_object_category ON data_object(category);
CREATE INDEX IF NOT EXISTS idx_object_tier ON data_object(tier_code);

-- ---------- 血缘 DAG ----------
CREATE TABLE IF NOT EXISTS lineage_edge (
    id                  BIGSERIAL PRIMARY KEY,
    from_object_id      BIGINT NOT NULL REFERENCES data_object(id), -- 输入
    to_object_id        BIGINT NOT NULL REFERENCES data_object(id), -- 输出
    direction           VARCHAR(32) NOT NULL DEFAULT 'INPUT_TO_OUTPUT',
    edge_version        VARCHAR(32) NOT NULL DEFAULT 'v1',
    recipe              TEXT,
    software_version    VARCHAR(64),
    shared_reference    BOOLEAN NOT NULL DEFAULT false, -- 是否为跨项目共享引用
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (from_object_id, to_object_id),
    CHECK (from_object_id <> to_object_id)
);
CREATE INDEX IF NOT EXISTS idx_edge_from ON lineage_edge(from_object_id);
CREATE INDEX IF NOT EXISTS idx_edge_to ON lineage_edge(to_object_id);

-- ---------- 共享引用份额（冻结） ----------
CREATE TABLE IF NOT EXISTS object_share (
    id              BIGSERIAL PRIMARY KEY,
    object_id       BIGINT NOT NULL REFERENCES data_object(id),
    project_id      BIGINT NOT NULL REFERENCES project(id),
    frozen_share    NUMERIC(7,6) NOT NULL CHECK (frozen_share BETWEEN 0 AND 1),
    share_basis     VARCHAR(16) NOT NULL CHECK (share_basis IN ('REFERENCE','USAGE')),
    frozen_at       TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (object_id, project_id)
);

-- ---------- 保留策略（按对象类别 × 研究协议） ----------
CREATE TABLE IF NOT EXISTS retention_policy (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(48) NOT NULL,
    protocol_code       VARCHAR(32) NOT NULL,
    retention_years     NUMERIC(6,2) NOT NULL,
    rule_text           VARCHAR(512) NOT NULL,
    UNIQUE (category, protocol_code)
);

-- ---------- 成本日账（字节时长 × 层费率） ----------
CREATE TABLE IF NOT EXISTS cost_daily (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    day                 DATE NOT NULL,
    byte_hours          NUMERIC(20,3) NOT NULL,  -- 当日字节×24
    tier_code           VARCHAR(32) NOT NULL,
    gb_month_rate       NUMERIC(12,6) NOT NULL,  -- 费率版本快照
    storage_charge      NUMERIC(14,6) NOT NULL,  -- (GB) × 月费率/30
    UNIQUE (object_id, day)
);

-- ---------- 取回 / 恢复计算事件 ----------
CREATE TABLE IF NOT EXISTS retrieval_event (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    project_id          BIGINT NOT NULL REFERENCES project(id),
    plan_id             BIGINT,
    bytes               BIGINT NOT NULL,
    tier_code           VARCHAR(32) NOT NULL,
    retrieval_fee       NUMERIC(14,6) NOT NULL,
    restore_compute_fee NUMERIC(14,6) NOT NULL,
    event_time          TIMESTAMP NOT NULL DEFAULT now(),
    reason              VARCHAR(256)
);

-- ---------- 恢复计划 ----------
CREATE TABLE IF NOT EXISTS restore_plan (
    id                      BIGSERIAL PRIMARY KEY,
    target_object_id        BIGINT NOT NULL REFERENCES data_object(id),
    project_id              BIGINT NOT NULL REFERENCES project(id),
    strategy                VARCHAR(32) NOT NULL
                            CHECK (strategy IN ('SCHEDULED_TIERING','RESTORE_PACKAGE')),
    scheduled_at            TIMESTAMP,
    rto_hours               NUMERIC(6,2) NOT NULL DEFAULT 4,
    lead_hours              NUMERIC(6,2) NOT NULL DEFAULT 72,
    frequency_per_year      NUMERIC(6,2) NOT NULL DEFAULT 1,
    status                  VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','SATISFIABLE','UNSATISFIABLE','EXECUTED','BLOCKED')),
    estimated_hours         NUMERIC(8,2),
    closure_count           INT,
    missing_items           JSONB,
    per_run_retrieval_fee   NUMERIC(14,6),
    per_run_compute_fee     NUMERIC(14,6),
    package_monthly_fee     NUMERIC(14,6),
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    note                    VARCHAR(512)
);

-- ---------- 恢复包 ----------
CREATE TABLE IF NOT EXISTS restore_package (
    id                  BIGSERIAL PRIMARY KEY,
    target_object_id    BIGINT NOT NULL REFERENCES data_object(id),
    package_object_id   BIGINT NOT NULL REFERENCES data_object(id),
    project_id          BIGINT NOT NULL REFERENCES project(id),
    validated           BOOLEAN NOT NULL DEFAULT false,
    validation_detail   JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 月账单与项目分摊 ----------
CREATE TABLE IF NOT EXISTS monthly_bill (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL REFERENCES project(id),
    billing_month       CHAR(7) NOT NULL,           -- 'YYYY-MM'
    storage_fee         NUMERIC(14,6) NOT NULL,
    retrieval_fee       NUMERIC(14,6) NOT NULL,
    restore_compute_fee NUMERIC(14,6) NOT NULL,
    total_fee           NUMERIC(14,6) NOT NULL,
    allocation_json     JSONB NOT NULL,
    allocation_check    BOOLEAN NOT NULL,
    UNIQUE (project_id, billing_month)
);

-- ---------- 归档快照（目录水位 + 依赖 + 费率版本 + 决定） ----------
CREATE TABLE IF NOT EXISTS archive_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    rate_version    VARCHAR(32) NOT NULL,
    catalog_json    JSONB NOT NULL,   -- 目录水位
    lineage_json    JSONB NOT NULL,   -- 依赖
    billing_json    JSONB NOT NULL,   -- 账单与分摊
    decisions_json  JSONB NOT NULL,   -- 迁移/保留/计费理由（可回放）
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 治理事件（校验失败/规则变化/冻结，需传播） ----------
CREATE TABLE IF NOT EXISTS governance_event (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(48) NOT NULL
                    CHECK (event_type IN (
                        'CHECKSUM_FAILED','RETENTION_RULE_CHANGED','LITIGATION_HOLD',
                        'LITIGATION_HOLD_RELEASED','PROJECT_WITHDRAWN','RESTORE_BLOCKED',
                        'TIER_MIGRATION','SHARE_FROZEN','INTEGRITY_DEFECT','REPAIR_COMPLETED')),
    object_id       BIGINT REFERENCES data_object(id),
    project_id      BIGINT REFERENCES project(id),
    payload         JSONB,
    propagated      BOOLEAN NOT NULL DEFAULT false,
    affected_uids   JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================
-- 完整性治理域：对象完整性账 / 校验覆盖地图 / 纠删码片段状态 /
--              读量预算 / 修复优先级（独立于恢复依赖）
-- ============================================================

-- ---------- 对象目录补列：完整性分级、不可替代证据、删除冻结 ----------
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS integrity_class      VARCHAR(24) NOT NULL DEFAULT 'STANDARD'
    CHECK (integrity_class IN ('IRREPLACEABLE_EVIDENCE','CRITICAL','STANDARD'));
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS irreplaceable_evidence BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS deletion_frozen       BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS last_audited_at       TIMESTAMP;
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS audit_round_no        INT;
ALTER TABLE data_object ADD COLUMN IF NOT EXISTS audit_verified_bytes  BIGINT NOT NULL DEFAULT 0; -- 跨周期累计已校验字节（大对象部分扫描）

-- ---------- 完整性读量预算策略（每个存储层 × 审计周期声明的对象比例与字节预算） ----------
CREATE TABLE IF NOT EXISTS integrity_budget_policy (
    id                          BIGSERIAL PRIMARY KEY,
    tier_code                   VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    audit_cycle_days            INT NOT NULL DEFAULT 30,           -- 常规审计周期
    max_object_ratio            NUMERIC(7,6) NOT NULL CHECK (max_object_ratio BETWEEN 0 AND 1),
    max_byte_budget             BIGINT NOT NULL CHECK (max_byte_budget >= 0), -- 每周期可校验字节上限
    sampling_strategy           VARCHAR(16) NOT NULL DEFAULT 'STRATIFIED_FIXED'
                                CHECK (sampling_strategy IN ('STRATIFIED_FIXED','ADAPTIVE')),
    evidence_rotation_cycle_days INT NOT NULL DEFAULT 180,         -- 不可替代证据的更长轮换周期
    active                      BOOLEAN NOT NULL DEFAULT true,
    updated_at                  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tier_code)
);

-- ---------- 对象副本与纠删码片段状态（对象完整性账） ----------
CREATE TABLE IF NOT EXISTS object_replica (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    replica_no          INT NOT NULL,                  -- 副本序号；EC 对象为逻辑片段组
    placement_tier      VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    media_id            VARCHAR(64) NOT NULL,          -- 物理媒介（盘/机架/介质批次）
    replica_kind        VARCHAR(16) NOT NULL DEFAULT 'FULL_COPY'
                        CHECK (replica_kind IN ('FULL_COPY','EC_FRAGMENT')),
    ec_fragment_index   INT,                           -- EC 片段序号（k+m 中的编号）
    ec_k                INT,                           -- 数据片段数
    ec_m                INT,                           -- 冗余片段数
    fragment_status     VARCHAR(16) NOT NULL DEFAULT 'HEALTHY'
                        CHECK (fragment_status IN ('HEALTHY','DEGRADED','LOST','MISPLACED')),
    latent_corruption   BOOLEAN NOT NULL DEFAULT false,  -- 故障注入的潜在位腐蚀，审计扫描时检出
    expected_checksum   VARCHAR(128) NOT NULL,         -- 片段/副本期望摘要
    last_verified_at    TIMESTAMP,
    UNIQUE (object_id, replica_no, ec_fragment_index)
);
CREATE INDEX IF NOT EXISTS idx_replica_object ON object_replica(object_id);
CREATE INDEX IF NOT EXISTS idx_replica_media ON object_replica(media_id);

-- ---------- 介质告警（自适应抽样的输入） ----------
CREATE TABLE IF NOT EXISTS media_alert (
    id              BIGSERIAL PRIMARY KEY,
    media_id        VARCHAR(64) NOT NULL,
    tier_code       VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    severity        VARCHAR(8) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    alert_type      VARCHAR(32) NOT NULL,             -- SMART_ERROR / MEDIA_SCRUB_FAIL / TEMPERATURE ...
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_media_alert_active ON media_alert(active, media_id);

-- ---------- 审计周期（一次抽样审计运行；每个存储层一条预算执行记录） ----------
CREATE TABLE IF NOT EXISTS audit_cycle (
    id                  BIGSERIAL PRIMARY KEY,
    cycle_seq           INT NOT NULL,                 -- 周期序号（同层递增）
    tier_code           VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    strategy            VARCHAR(16) NOT NULL CHECK (strategy IN ('STRATIFIED_FIXED','ADAPTIVE')),
    started_at          TIMESTAMP NOT NULL DEFAULT now(),
    population_objects  INT NOT NULL,                 -- 该层对象总数（总体）
    population_bytes    BIGINT NOT NULL,
    sampled_objects     INT NOT NULL DEFAULT 0,
    sampled_bytes       BIGINT NOT NULL DEFAULT 0,
    byte_budget         BIGINT NOT NULL,              -- 声明的字节预算
    object_ratio_limit  NUMERIC(7,6) NOT NULL,        -- 声明的对象比例上限
    within_budget       BOOLEAN NOT NULL DEFAULT true,
    -- 该层缺陷率区间（Wilson 得分区间，不读取全量对象）
    defect_rate_lower   NUMERIC(9,6),
    defect_rate_upper   NUMERIC(9,6),
    defect_rate_point   NUMERIC(9,6),
    uncovered_probability NUMERIC(9,6),               -- 自适应路线：单缺陷对象未被抽中的概率
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','COMPLETED')),
    note                VARCHAR(512),
    UNIQUE (tier_code, cycle_seq)
);

-- ---------- 审计抽样项（覆盖地图：对象×周期×副本，含分层/年龄/价值层位） ----------
CREATE TABLE IF NOT EXISTS audit_sample_item (
    id                  BIGSERIAL PRIMARY KEY,
    cycle_id            BIGINT NOT NULL REFERENCES audit_cycle(id),
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    replica_id          BIGINT REFERENCES object_replica(id),
    tier_code           VARCHAR(32) NOT NULL,
    media_id            VARCHAR(64),
    age_band            VARCHAR(16) NOT NULL CHECK (age_band IN ('NEW','MID','AGED')),
    value_band          VARCHAR(24) NOT NULL
                        CHECK (value_band IN ('IRREPLACEABLE_EVIDENCE','CRITICAL','STANDARD')),
    sample_basis        VARCHAR(24) NOT NULL
                        CHECK (sample_basis IN (
                            'STRATUM_FIXED','EVIDENCE_ROTATION','PRIOR_DEFECT',
                            'MEDIA_ALERT','HIGH_VALUE')),
    independent_sample  BOOLEAN NOT NULL DEFAULT true, -- 同副本重复检查不计为独立样本
    dedup_key           VARCHAR(128),                  -- (object,replica) 去重键
    result_status       VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (result_status IN ('PENDING','VERIFIED','DEFECT_BIT_ROT','DEFECT_MISPLACED')),
    bytes_scanned       BIGINT NOT NULL DEFAULT 0,
    checked_at          TIMESTAMP,
    UNIQUE (cycle_id, object_id, replica_id)
);
CREATE INDEX IF NOT EXISTS idx_sample_cycle ON audit_sample_item(cycle_id);
CREATE INDEX IF NOT EXISTS idx_sample_object ON audit_sample_item(object_id);

-- ---------- 完整性缺陷台账（位腐蚀 / 错误分层副本 / 覆盖盲区） ----------
CREATE TABLE IF NOT EXISTS integrity_defect (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    replica_id          BIGINT REFERENCES object_replica(id),
    cycle_id            BIGINT REFERENCES audit_cycle(id),
    defect_type         VARCHAR(24) NOT NULL
                        CHECK (defect_type IN ('BIT_ROT','MISPLACED_REPLICA','COVERAGE_GAP')),
    severity            VARCHAR(8) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    detail              JSONB,
    repair_priority     INT NOT NULL DEFAULT 100,      -- 数值越小优先级越高
    repair_status       VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (repair_status IN ('OPEN','REPAIRING','REPAIRED','ACCEPTED_RISK')),
    freeze_deletion     BOOLEAN NOT NULL DEFAULT true,  -- 哈希不符立即冻结删除
    detected_at         TIMESTAMP NOT NULL DEFAULT now(),
    repaired_at         TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_defect_status ON integrity_defect(repair_status, repair_priority);
CREATE INDEX IF NOT EXISTS idx_defect_object ON integrity_defect(object_id);

-- ---------- 修复验证台账（修复后重校验，独立样本留痕） ----------
CREATE TABLE IF NOT EXISTS repair_verification (
    id                  BIGSERIAL PRIMARY KEY,
    defect_id           BIGINT NOT NULL REFERENCES integrity_defect(id),
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    source_replica_id   BIGINT REFERENCES object_replica(id), -- 修复来源（健康副本/EC 重建）
    verified_checksum   VARCHAR(128),
    result_status       VARCHAR(16) NOT NULL CHECK (result_status IN ('REPAIRED','FAILED')),
    detail              JSONB,
    verified_at         TIMESTAMP NOT NULL DEFAULT now()
);
