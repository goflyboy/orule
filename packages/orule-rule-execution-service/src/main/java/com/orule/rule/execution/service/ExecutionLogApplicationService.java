package com.orule.rule.execution.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.api.error.ExecutionLogDetail;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.error.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only service for the detailed execution log
 * (RFC-0040 §3.6 / §16.4 / TASK-2.2.3).
 *
 * <p>Returns an {@link ExecutionLogDetail} projection for
 * {@code GET /api/v1/executions/{taskId}/logs}. The {@code perRuleExecution}
 * list is empty in v0.7 because the worker doesn't yet persist per-rule rows
 * (see the TODO marker on RuleSetExecutorServiceImpl). The list shape is
 * already typed so a future Sprint only needs to fill the data, not the API.
 */
@Service
public class ExecutionLogApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogApplicationService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ExecutionLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public ExecutionLogApplicationService(ExecutionLogRepository logRepo, ObjectMapper objectMapper) {
        this.logRepo = logRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ExecutionLogDetail findByTaskId(String taskId) {
        ExecutionLog row = logRepo.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return new ExecutionLogDetail(
                row.getTaskId(),
                row.getExecutionType().name(),
                row.getRuleCode(),
                row.getRuleSetCode(),
                row.getExecutorType(),
                row.getStatus().name(),
                deserialize(row.getInputContext()),
                deserialize(row.getOutputContext()),
                row.getTotalCount(),
                row.getSuccessCount(),
                row.getFailedCount(),
                row.getErrorCode(),
                row.getErrorMessage(),
                List.of(),  // v0.7 placeholder; per-rule rows are not yet persisted.
                row.getTraceId(),
                row.getOperatorId(),
                row.getTenantId(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getDurationMs(),
                row.getCreatedAt()
        );
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize context JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
