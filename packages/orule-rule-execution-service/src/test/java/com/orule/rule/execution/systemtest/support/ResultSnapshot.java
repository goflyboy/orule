package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

/**
 * Snapshot of the most recent HTTP call, used by the base class for failure
 * diagnostics ({@code @AfterEach dumpOnFailure}). Not part of the user-facing
 * DSL — tests do not touch this directly.
 */
public final class ResultSnapshot {

    final String operation;
    final Object request;
    final ResponseEntity<String> response;
    final ExecutionLogRepository logRepo;
    private final ObjectMapper mapper;

    ResultSnapshot(String operation,
                   Object request,
                   ResponseEntity<String> response,
                   ExecutionLogRepository logRepo) {
        this.operation = operation;
        this.request = request;
        this.response = response;
        this.logRepo = logRepo;
        this.mapper = new ObjectMapper();
    }

    boolean indicatesFailure() {
        HttpStatus s = HttpStatus.resolve(response.getStatusCode().value());
        if (s != null && (s.is4xxClientError() || s.is5xxServerError())) {
            return true;
        }
        // Also dump when the HTTP body is HTTP 200 but business-level
        // success=false (EVAL_FAILED / RUNTIME_ERROR / RULE_NOT_FOUND etc.).
        try {
            JsonNode body = mapper.readTree(response.getBody());
            if (body.has("success") && !body.path("success").asBoolean(true)) {
                return true;
            }
        } catch (Exception ignored) {
            // body unparseable — already covered by HTTP error path above
        }
        return false;
    }

    /** Best-effort fetch of the most recent log row; null when no row exists. */
    ExecutionLog fetchLogRow() {
        try {
            JsonNode body = mapper.readTree(response.getBody());
            String taskId = body.path("taskId").asText(null);
            if (taskId == null || taskId.isBlank()) {
                return null;
            }
            Optional<ExecutionLog> row = logRepo.findByTaskId(taskId);
            return row.orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
