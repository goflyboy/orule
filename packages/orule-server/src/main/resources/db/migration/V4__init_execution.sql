-- V4: 执行域（Execution / ExecutionLog / RuleTestCase）
-- RFC-0014 §3.5

CREATE TABLE execution (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    rule_set_artifact_id VARCHAR(36),
    trigger_type    VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    total_count     INT          NOT NULL DEFAULT 0,
    success_count   INT          NOT NULL DEFAULT 0,
    failed_count    INT          NOT NULL DEFAULT 0,
    input_data      CLOB,
    output_data     CLOB,
    error_message   TEXT,
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP    NULL,
    duration_ms     BIGINT,
    trace_id        VARCHAR(64),
    PRIMARY KEY (id),
    CONSTRAINT fk_exec_ruleset FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
);

CREATE INDEX idx_exec_status ON execution(status);
CREATE INDEX idx_exec_started ON execution(started_at);

CREATE TABLE execution_log (
    id              BIGINT       AUTO_INCREMENT,
    execution_id    VARCHAR(36)  NOT NULL,
    rule_id         VARCHAR(36),
    rule_version_id VARCHAR(36),
    input_data      CLOB,
    output_data     CLOB,
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
    input_json      CLOB        NOT NULL,
    expected_json   CLOB        NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_result     VARCHAR(16),
    last_run_at     TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_testcase_rule FOREIGN KEY (rule_id) REFERENCES rule(id)
);

CREATE INDEX idx_testcase_rule ON rule_test_case(rule_id);
CREATE INDEX idx_testcase_active ON rule_test_case(is_active);
