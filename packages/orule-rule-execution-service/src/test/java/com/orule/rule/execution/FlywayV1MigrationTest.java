package com.orule.rule.execution;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 §14.2 + TASK-1.1.2 验证：
 * Flyway H2 兼容脚本 V1 必须能成功建表，且 §3.3 全部列与索引生效。
 *
 * 测试目的：
 *  1. 不让 schema 漂移变成"启动 OK 但表不存在"的隐性失败；
 *  2. 用例一旦变绿，意味着后续 EF/Repository 在 H2 环境下可放心编写。
 */
@SpringBootTest
class FlywayV1MigrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v1ExecutionLogTableAndIndexesExist() {
        // 1. 触发 Flyway 已 apply V1（H2 dev profile）
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        for (org.flywaydb.core.api.MigrationInfo mi : flyway.info().applied()) {
            versions.add(Integer.parseInt(mi.getVersion().getVersion()));
        }
        assertThat(versions).containsExactly(1);

        // 2. 表存在
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Long.class, "execution_log");
        assertThat(count).isEqualTo(1L);

        // 3. §3.3 必备列齐全（关键列）
        Long colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name IN (?,?,?,?,?,?,?,?)",
                Long.class,
                "execution_log",
                "task_id", "execution_type", "rule_code", "rule_set_code",
                "executor_type", "status", "input_context", "output_context");
        assertThat(colCount).isEqualTo(8L);

        // 4. §14.2 必备索引齐全
        Long idxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes WHERE table_name = ? AND index_name LIKE 'idx_exec_%'",
                Long.class, "execution_log");
        assertThat(idxCount).isEqualTo(6L);

        // 5. CHECK 约束生效：非法 status 拒绝（H2 错误消息为 "Check constraint violation"）
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("INSERT INTO execution_log (task_id, execution_type, executor_type, status) VALUES (?,?,?,?)",
                        "x", "RULE", "JavaSourceExecutor", "INVALID_STATUS"))
                .hasMessageContaining("Check constraint"));
    }
}
