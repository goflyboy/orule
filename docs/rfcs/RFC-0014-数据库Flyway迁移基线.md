# RFC-0014: 数据库 Flyway 迁移基线（V1~V5）

> **状态**：SUPERSEDED · **优先级**：P0 · **预计工作量**：2d · **阶段**：S1
> **RFC-0031 修订**：本 RFC 已被 [RFC-0031-Type系统重构.md](RFC-0031-Type系统重构.md) 部分覆盖；
> 具体差异：`attribute_type` 表增加 `type_json` 列、`enum_type` / `enum_value` 表删除、
> `function_lib.signature` 改为 JSON。详见 RFC-0031 §3.2。

---

## 1. 摘要

按 `04-数据模型.md` 的 6 大数据域 + 26 张表定义，编写 Flyway 迁移脚本 V1~V5，初始化完整 schema。

---

## 2. 动机

- MVP 启动需要完整的数据库 schema（依据 `04-数据模型.md §4.x` 的全部表）
- Flyway 是统一迁移工具（依据 `07-部署视图 §7.4.3`）
- 支撑后续 RFC-0015（元数据 CRUD）、RFC-0016（规则 CRUD）的基础

---

## 3. 详细设计

### 3.1 迁移脚本清单

```
packages/orule-server/src/main/resources/db/migration/
├── V1__init_metadata.sql      # 元数据域（5 张表）
├── V2__init_rule.sql          # 规则域（4 张表）
├── V3__init_artifact.sql      # 制品域（5 张表）
├── V4__init_execution.sql     # 执行域（3 张表）
└── V5__init_seed.sql          # 种子数据
```

### 3.2 V1__init_metadata.sql（元数据域）

依据 `04-数据模型.md §4.3`：

```sql
-- V1：元数据域（DomainType / ObjectType / AttributeType / EnumType / FunctionLib）

CREATE TABLE domain_type (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_code (code)
);

CREATE TABLE object_type (
    id              VARCHAR(36)  NOT NULL,
    domain_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_domain_code (domain_id, code),
    CONSTRAINT fk_object_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE TABLE attribute_type (
    id              VARCHAR(36)  NOT NULL,
    object_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    data_type       VARCHAR(32)  NOT NULL,  -- string / int / decimal / boolean / date / enum / object / array
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value   VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_code (object_id, code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE TABLE enum_type (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enum_code (code)
);

CREATE TABLE enum_value (
    id              VARCHAR(36)  NOT NULL,
    enum_id         VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enum_value_code (enum_id, code),
    CONSTRAINT fk_enum_value_enum FOREIGN KEY (enum_id) REFERENCES enum_type(id)
);

CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    signature       VARCHAR(255) NOT NULL,  -- 如 max(a, b)
    description     TEXT,
    category        VARCHAR(64),           -- math / string / date / aggregate
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_code (code)
);
```

### 3.3 V2__init_rule.sql（规则域）

依据 `04-数据模型.md §4.4`：

```sql
-- V2：规则域（Rule / RuleVersion / RuleTestCase）

CREATE TABLE rule_set (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    domain_id       VARCHAR(36)  NOT NULL,
    owner_code      VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'MAINTENANCE',  -- MAINTENANCE / ACTIVE / RETIRED
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ruleset_code (code),
    CONSTRAINT fk_ruleset_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE TABLE rule (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    sort_order      INT          NOT NULL DEFAULT 0,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_set_code (rule_set_id, code),
    CONSTRAINT fk_rule_ruleset FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
);

CREATE TABLE rule_version (
    id              VARCHAR(36)  NOT NULL,
    rule_id         VARCHAR(36)  NOT NULL,
    version         INT          NOT NULL,                      -- 1, 2, 3, ...
    status          VARCHAR(16)  NOT NULL DEFAULT 'MAINTENANCE', -- MAINTENANCE / PUBLISHED / RETIRED
    description     TEXT,
    simple_ts       TEXT,                                        -- 编译后的 SimpleTS 源码（中间态）
    groovy_source   MEDIUMTEXT,                                  -- 编译后的 Groovy 源码
    changelog       TEXT,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP    NULL,
    retired_at      TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_version (rule_id, version),
    CONSTRAINT fk_rule_version_rule FOREIGN KEY (rule_id) REFERENCES rule(id)
);

CREATE INDEX idx_rule_version_status ON rule_version(status);
CREATE INDEX idx_rule_version_published ON rule_version(rule_id, status);
```

> **注意**：依据 `04-数据模型.md §4.4.4`，RuleVersion 同时保存 `simple_ts` 和 `groovy_source`，因为 simple_ts 是中间态编辑视图，需要回放编辑。

### 3.4 V3__init_artifact.sql（制品域）

依据 `04-数据模型.md §4.5`：

```sql
-- V3：制品域（RuleArtifact / RuleSetArtifact / ArtifactStorage）

CREATE TABLE rule_artifact (
    id              VARCHAR(36)  NOT NULL,
    rule_version_id VARCHAR(36)  NOT NULL,
    storage_type    VARCHAR(16)  NOT NULL,        -- local / s3 / oss / minio
    storage_path    VARCHAR(512) NOT NULL,        -- 制品相对路径
    storage_url     VARCHAR(512),                 -- 可下载 URL
    file_size       BIGINT       NOT NULL DEFAULT 0,
    sha256          VARCHAR(64)  NOT NULL,        -- 制品校验
    compile_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING / SUCCESS / FAILED
    compile_log     TEXT,                          -- 编译日志（失败时记录）
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_artifact_version (rule_version_id),
    CONSTRAINT fk_rule_artifact_version FOREIGN KEY (rule_version_id) REFERENCES rule_version(id)
);

CREATE TABLE rule_set_artifact (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    version         INT          NOT NULL,        -- 规则集版本
    storage_type    VARCHAR(16)  NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    storage_url     VARCHAR(512),
    file_size       BIGINT       NOT NULL DEFAULT 0,
    sha256          VARCHAR(64)  NOT NULL,
    rule_count      INT          NOT NULL DEFAULT 0,
    included_versions JSONB,                      -- 包含的 rule_version_id 列表
    concurrency     INT          NOT NULL DEFAULT 1, -- ADR-006-fix2 并发度
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ruleset_artifact_version (rule_set_id, version),
    CONSTRAINT fk_ruleset_artifact_set FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
);

CREATE TABLE artifact_storage_config (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,        -- 配置唯一标识
    storage_type    VARCHAR(16)  NOT NULL,        -- local / s3 / oss / minio
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    config_json     TEXT         NOT NULL,        -- 各类型配置（S3 bucket / OSS endpoint 等）
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_config_code (code)
);
```

### 3.5 V4__init_execution.sql（执行域）

```sql
-- V4：执行域（Execution / ExecutionLog / ExecutionTestCase）

CREATE TABLE execution (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    rule_set_artifact_id VARCHAR(36) NOT NULL,
    trigger_type    VARCHAR(16)  NOT NULL,        -- MANUAL / TEST / SCHEDULED
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING / RUNNING / SUCCESS / FAILED / PARTIAL
    total_count     INT          NOT NULL DEFAULT 0,
    success_count   INT          NOT NULL DEFAULT 0,
    failed_count    INT          NOT NULL DEFAULT 0,
    input_data      JSONB,                          -- 输入数据快照
    output_data     JSONB,                          -- 输出结果
    error_message   TEXT,
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP    NULL,
    duration_ms     BIGINT,
    trace_id        VARCHAR(64),                    -- X-Trace-Id 关联
    PRIMARY KEY (id),
    CONSTRAINT fk_exec_ruleset FOREIGN KEY (rule_set_id) REFERENCES rule_set(id),
    CONSTRAINT fk_exec_artifact FOREIGN KEY (rule_set_artifact_id) REFERENCES rule_set_artifact(id)
);

CREATE INDEX idx_exec_status ON execution(status);
CREATE INDEX idx_exec_started ON execution(started_at);

CREATE TABLE execution_log (
    id              BIGINT       AUTO_INCREMENT,
    execution_id    VARCHAR(36)  NOT NULL,
    rule_id         VARCHAR(36),
    rule_version_id VARCHAR(36),
    input_data      JSONB,
    output_data     JSONB,
    status          VARCHAR(16)  NOT NULL,
    error_message   TEXT,
    duration_ms     BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_log_exec FOREIGN KEY (execution_id) REFERENCES execution(id)
);

CREATE INDEX idx_log_execution ON execution_log(execution_id);

CREATE TABLE rule_test_case (
    id              VARCHAR(36)  NOT NULL,
    rule_id         VARCHAR(36)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    input_json      JSONB        NOT NULL,
    expected_json   JSONB        NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_result     VARCHAR(16),                   -- PASS / FAIL / NULL
    last_run_at     TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_testcase_rule FOREIGN KEY (rule_id) REFERENCES rule(id)
);

CREATE INDEX idx_testcase_rule ON rule_test_case(rule_id);
CREATE INDEX idx_testcase_active ON rule_test_case(is_active);
```

### 3.6 V5__init_seed.sql（种子数据）

```sql
-- V5：种子数据（内置 DomainType / EnumType / FunctionLib 示例）

-- 内置 DomainType 示例
INSERT INTO domain_type (id, code, name, description, owner_code) VALUES
  ('dom-order', 'ORDER', '订单域', '订单相关规则', 'system'),
  ('dom-customer', 'CUSTOMER', '客户域', '客户相关规则', 'system');

-- 内置 EnumType 示例
INSERT INTO enum_type (id, code, name, description) VALUES
  ('enum-tier', 'CustomerTier', '客户等级', 'VIP / Gold / Silver / Bronze'),
  ('enum-pay', 'PayMethod', '支付方式', 'ALIPAY / WECHAT / CARD / CASH');

INSERT INTO enum_value (id, enum_id, code, name, sort_order) VALUES
  ('enum-tier-vip', 'enum-tier', 'VIP', 'VIP 客户', 1),
  ('enum-tier-gold', 'enum-tier', 'GOLD', '黄金客户', 2),
  ('enum-tier-silver', 'enum-tier', 'SILVER', '白银客户', 3),
  ('enum-tier-bronze', 'enum-tier', 'BRONZE', '青铜客户', 4);

-- 内置 FunctionLib 示例
INSERT INTO function_lib (id, code, name, signature, description, category, is_builtin) VALUES
  ('func-max', 'max', '最大值', 'max(a, b)', '取两个数的最大值', 'math', TRUE),
  ('func-min', 'min', '最小值', 'min(a, b)', '取两个数的最小值', 'math', TRUE),
  ('func-abs', 'abs', '绝对值', 'abs(a)', '取绝对值', 'math', TRUE),
  ('func-round', 'round', '四舍五入', 'round(a, n)', '保留 n 位小数', 'math', TRUE),
  ('func-upper', 'upper', '转大写', 'upper(s)', '字符串转大写', 'string', TRUE),
  ('func-lower', 'lower', '转小写', 'lower(s)', '字符串转小写', 'string', TRUE),
  ('func-contains', 'contains', '包含', 'contains(s, sub)', '字符串包含', 'string', TRUE),
  ('func-now', 'now', '当前时间', 'now()', '当前时间戳', 'date', TRUE),
  ('func-days', 'days', '天数差', 'days(d1, d2)', '两个日期的天数差', 'date', TRUE);
```

---

## 4. 影响面

- 新增 5 个 SQL 迁移文件 + 26 张表
- 数据库 schema 一次性建立（MVP）
- 不影响现有数据

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| Flyway 启动 | `mvn spring-boot:run` 自动执行 V1~V5 |
| Schema 校验 | `mvn spring-boot:run` + `ddl-auto: validate` 无报错 |
| 表数量 | `SHOW TABLES` 返回 26 张表 |
| 种子数据 | `SELECT * FROM domain_type` 返回 2 条 |
| H2 + MySQL 双兼容 | 切换 profile 都能成功迁移 |

---

## 6. 风险

- **R1**：H2 vs MySQL 方言差异（JSONB / MEDIUMTEXT） → 缓解：用 SQL 标准语法；H2 用 `LONGVARCHAR`，MySQL 用 `MEDIUMTEXT`
- **R2**：种子数据更新困难 → 缓解：种子数据用 `INSERT IGNORE`（或 `MERGE`）
- **R3**：26 张表一次性太大 → 缓解：每张表都按 RFC-0015/0016 等单独 PR 验证

---

## 7. 实施步骤

```
1. 创建 db/migration/ 目录
2. 编写 V1__init_metadata.sql
3. 编写 V2__init_rule.sql
4. 编写 V3__init_artifact.sql
5. 编写 V4__init_execution.sql
6. 编写 V5__init_seed.sql
7. 启动 server 验证 Flyway 执行
8. 用 H2 Console 检查表结构和数据
9. 切换到 MySQL profile 再次验证
```

---

## 8. 关联

- 上游：RFC-0012（Maven 骨架）
- 下游：RFC-0015（元数据 CRUD）、RFC-0016（规则 CRUD）、RFC-0017（ArtifactStorage）
- ADR：—
