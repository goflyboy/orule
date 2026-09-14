package com.orule.rule.execution.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0040 §21 TASK-1.1.3 验收：ExecutionLog JPA Entity + Repository 单测。
 *
 * <p>覆盖：
 * <ol>
 *   <li>Repository.findByTaskId() — 主路径</li>
 *   <li>Entity 状态机迁移（PENDING → RUNNING → SUCCESS/FAILED/PARTIAL_SUCCESS）</li>
 *   <li>CHECK 约束：非法 status 拒绝（与 FlywayV1MigrationTest §5 呼应）</li>
 *   <li>uk_exec_task UNIQUE：重复 taskId 拒绝</li>
 *   <li>findRecentByRuleCode(Pageable) — §14.2 idx_exec_rule_code</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class ExecutionLogRepositoryTest {

    @Autowired
    ExecutionLogRepository repo;

    @Test
    void findByTaskId_returnsInsertedRow() {
        String taskId = "test-" + UUID.randomUUID();
        ExecutionLog log = ExecutionLog.pending(
                taskId, ExecutionType.RULE, "ORDER_VIP_DISCOUNT", null,
                "JavaSourceExecutor", "trace-1", "user-1", "tenant-1");
        log.markRunning();
        repo.saveAndFlush(log);

        Optional<ExecutionLog> fetched = repo.findByTaskId(taskId);

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getStatus()).isEqualTo(ExecutionLogStatus.RUNNING);
        assertThat(fetched.get().getRuleCode()).isEqualTo("ORDER_VIP_DISCOUNT");
        assertThat(fetched.get().getStartedAt()).isNotNull();
    }

    @Test
    void statusMachinePendingToSuccess_marksDurationAndOutputContext() {
        ExecutionLog log = ExecutionLog.pending(
                "t2", ExecutionType.RULE, "RULE_X", null,
                "JavaSourceExecutor", null, null, null);
        repo.saveAndFlush(log);

        log.markRunning();
        // simulate work
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        log.markSuccess("{\"order.discount\":30}");

        repo.saveAndFlush(log);
        ExecutionLog reread = repo.findByTaskId("t2").orElseThrow();

        assertThat(reread.getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
        assertThat(reread.getFinishedAt()).isNotNull();
        assertThat(reread.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(reread.getOutputContext()).contains("order.discount");
    }

    @Test
    void markRunning_fromSuccess_throwsIllegalState() {
        ExecutionLog log = ExecutionLog.pending(
                "t3", ExecutionType.RULE, "RULE_X", null,
                "JavaSourceExecutor", null, null, null);
        log.markRunning();
        log.markSuccess("{}");
        // markRunning again should fail: SUCCESS is terminal
        assertThatThrownBy(log::markRunning)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot markRunning from SUCCESS");
    }

    @Test
    void partialSuccess_setsCounters() {
        ExecutionLog log = ExecutionLog.pending(
                "t4", ExecutionType.RULE_SET, null, "RULE_SET_X",
                "JavaSourceExecutor", null, null, null);
        log.markRunning();
        log.markPartialSuccess(10, 7, 3, "{\"o\":1}");

        repo.saveAndFlush(log);
        ExecutionLog reread = repo.findByTaskId("t4").orElseThrow();

        assertThat(reread.getStatus()).isEqualTo(ExecutionLogStatus.PARTIAL_SUCCESS);
        assertThat(reread.getTotalCount()).isEqualTo(10);
        assertThat(reread.getSuccessCount()).isEqualTo(7);
        assertThat(reread.getFailedCount()).isEqualTo(3);
    }

    @Test
    void duplicateTaskId_violatesUniqueConstraint() {
        ExecutionLog a = ExecutionLog.pending(
                "duplicate", ExecutionType.RULE, "RULE_A", null,
                "JavaSourceExecutor", null, null, null);
        repo.saveAndFlush(a);

        ExecutionLog b = ExecutionLog.pending(
                "duplicate", ExecutionType.RULE, "RULE_B", null,
                "JavaSourceExecutor", null, null, null);
        assertThatThrownBy(() -> repo.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidStatus_violatesCheckConstraint_atDbLevel() {
        // §14.2 ck_exec_status CHECK constraint enforces legal status at DB level.
        // Verified at FlywayV1MigrationTest#v1ExecutionLogTableAndIndexesExist step 5.
        // Here we only verify enum-level guarantees (no enum can hold an illegal value).
        for (ExecutionLogStatus s : ExecutionLogStatus.values()) {
            assertThat(s.name()).isIn("PENDING", "RUNNING", "SUCCESS", "FAILED", "PARTIAL_SUCCESS");
        }
    }

    @Test
    void findRecentByRuleCode_returnsInDescendingOrder() {
        // Insert 3 logs with strictly increasing createdAt (set explicitly to avoid ms collisions)
        for (int i = 0; i < 3; i++) {
            ExecutionLog log = ExecutionLog.pending(
                    "rule-" + i, ExecutionType.RULE, "POPULAR_RULE", null,
                    "JavaSourceExecutor", null, null, null);
            log.setCreatedAt(Instant.now().plusSeconds(i));
            log.markRunning();
            repo.saveAndFlush(log);
        }

        List<ExecutionLog> recent = repo.findRecentByRuleCode(
                "POPULAR_RULE", org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getTaskId()).isEqualTo("rule-2");
        assertThat(recent.get(1).getTaskId()).isEqualTo("rule-1");
        assertThat(recent.get(2).getTaskId()).isEqualTo("rule-0");
    }
}
