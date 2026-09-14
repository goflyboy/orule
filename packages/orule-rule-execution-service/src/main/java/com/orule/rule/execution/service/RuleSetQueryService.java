package com.orule.rule.execution.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.api.dto.RuleSetExecutionResultResponse;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.error.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

/**
 * Query-only service for async rule-set results (RFC-0040 §3.6 / TASK-2.2.2).
 *
 * <p>{@link #queryResult(String)} reads the persisted {@code execution_log}
 * row, deserializes the persisted {@code output_context} JSON, and projects it
 * into a {@link RuleSetExecutionResultResponse}. Used by
 * {@link com.orule.rule.execution.api.RuleSetExecutionController#queryRuleSetResult(String)}.
 */
@Service
public class RuleSetQueryService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetQueryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ExecutionLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public RuleSetQueryService(ExecutionLogRepository logRepo, ObjectMapper objectMapper) {
        this.logRepo = logRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RuleSetExecutionResultResponse queryResult(String taskId) {
        ExecutionLog row = logRepo.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        return new RuleSetExecutionResultResponse(
                row.getTaskId(),
                row.getStatus().name(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getDurationMs(),
                row.getTotalCount(),
                row.getSuccessCount(),
                row.getFailedCount(),
                deserializeContext(row.getOutputContext()),
                row.getErrorMessage()
        );
    }

    private Map<String, Object> deserializeContext(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize output context: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
