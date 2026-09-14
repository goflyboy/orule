-- ============================================================
-- V1 (PostgreSQL): 创建 execution_log 表
-- 文件路径: src/main/resources/db/migration/postgresql/V1__create_execution_log.sql
-- 参考:   RFC-0040 §14.2（生产实施版本）
-- ============================================================

CREATE TABLE IF NOT EXISTS execution_log (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    execution_type  VARCHAR(16) NOT NULL,
    rule_code       VARCHAR(128),
    rule_set_code   VARCHAR(128),
    executor_type   VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    input_context   JSONB,
    output_context  JSONB,
    total_count     INTEGER,
    success_count   INTEGER,
    failed_count    INTEGER,
    error_code      VARCHAR(64),
    error_message   TEXT,
    trace_id        VARCHAR(64),
    operator_id     VARCHAR(64),
    tenant_id       VARCHAR(64),
    started_at      TIMESTAMP WITH TIME ZONE,
    finished_at     TIMESTAMP WITH TIME ZONE,
    duration_ms     BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_exec_task    UNIQUE (task_id),
    CONSTRAINT ck_exec_type    CHECK (execution_type IN ('RULE', 'RULE_SET')),
    CONSTRAINT ck_exec_status  CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'))
);

CREATE INDEX IF NOT EXISTS idx_exec_task           ON execution_log(task_id);
CREATE INDEX IF NOT EXISTS idx_exec_rule_code      ON execution_log(rule_code);
CREATE INDEX IF NOT EXISTS idx_exec_rule_set_code  ON execution_log(rule_set_code);
CREATE INDEX IF NOT EXISTS idx_exec_tenant_created ON execution_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_exec_trace          ON execution_log(trace_id);
CREATE INDEX IF NOT EXISTS idx_exec_status_created ON execution_log(status, created_at DESC);

COMMENT ON TABLE  execution_log IS '规则/规则集执行日志（RFC-0040 §3.3）';
COMMENT ON COLUMN execution_log.task_id        IS '任务 ID（UUID），业务方轮询键';
COMMENT ON COLUMN execution_log.execution_type IS 'RULE 同步单条 / RULE_SET 异步规则集';
COMMENT ON COLUMN execution_log.status         IS 'PENDING/RUNNING/SUCCESS/FAILED/PARTIAL_SUCCESS';
COMMENT ON COLUMN execution_log.input_context  IS '输入 ObjectInst 集合（深拷贝，JSONB 存储）';
COMMENT ON COLUMN execution_log.output_context IS '输出 ObjectInst 集合（含规则修改后的累积状态）';

-- ============================================================
-- 月分区（按 created_at 分区，规避 R-003 表膨胀风险）
-- ============================================================
-- 注：v1.0 暂不分区（数据量小），v1.2 引入以下分区策略：
--
-- ALTER TABLE execution_log PARTITION BY RANGE (created_at);
-- CREATE TABLE execution_log_2026_09 PARTITION OF execution_log
--   FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- CREATE TABLE execution_log_2026_10 PARTITION OF execution_log
--   FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
