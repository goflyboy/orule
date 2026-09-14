package com.orule.rule.execution.api.error;

import com.orule.rule.execution.error.ExecutorNotRegisteredException;
import com.orule.rule.execution.error.RateLimitExceededException;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleExecutionException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.error.RuleSetNotFoundException;
import com.orule.rule.execution.error.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Global exception handler (RFC-0040 §3.5 / §16.6 + TASK-1.1.5).
 *
 * <p>Maps all 9 §16.6 error codes (including INTERNAL_ERROR):
 * <table>
 *   <tr><th>HTTP</th><th>errorCode</th><th>Exception</th></tr>
 *   <tr><td>200</td><td>success=true</td><td>Normal business (handled by Controllers)</td></tr>
 *   <tr><td>200</td><td>EVAL_FAILED</td><td>{@link RuleEvalException}</td></tr>
 *   <tr><td>200</td><td>RUNTIME_ERROR</td><td>{@link RuleRuntimeException}</td></tr>
 *   <tr><td>404</td><td>RULE_NOT_FOUND</td><td>{@link RuleNotFoundException}</td></tr>
 *   <tr><td>404</td><td>RULE_SET_NOT_FOUND</td><td>{@link RuleSetNotFoundException}</td></tr>
 *   <tr><td>404</td><td>TASK_NOT_FOUND</td><td>{@link TaskNotFoundException}</td></tr>
 *   <tr><td>429</td><td>RATE_LIMIT_EXCEEDED</td><td>{@link RateLimitExceededException}</td></tr>
 *   <tr><td>500</td><td>EXECUTOR_NOT_REGISTERED</td><td>{@link ExecutorNotRegisteredException}</td></tr>
 *   <tr><td>500</td><td>INTERNAL_ERROR</td><td>Other uncaught exceptions</td></tr>
 * </table>
 *
 * <p>Note: {@link RuleEvalException} / {@link RuleRuntimeException} actually return HTTP 200
 * because they express "rule execution failure", not "HTTP protocol error".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // === §16.6 HTTP 404 ===

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(RuleNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(RuleSetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleSetNotFound(RuleSetNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFound(TaskNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex);
    }

    // === §16.6 HTTP 429 ===

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        HttpHeaders headers = new HttpHeaders();
        long retryAfterSec = Math.max(1L, ex.getRetryAfterMs() / 1000L);
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSec));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(buildBody(HttpStatus.TOO_MANY_REQUESTS, ex.getErrorCode(), ex.getMessage()));
    }

    // === §16.6 HTTP 500 ===

    @ExceptionHandler(ExecutorNotRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleExecutorNotRegistered(ExecutorNotRegisteredException ex) {
        log.error("Executor not registered", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    @ExceptionHandler(RuleExecutionException.class)
    public ResponseEntity<ErrorResponse> handleRuleExecution(RuleExecutionException ex) {
        // EVAL_FAILED / RUNTIME_ERROR -> HTTP 200 (business error, not HTTP error)
        if ("EVAL_FAILED".equals(ex.getErrorCode()) || "RUNTIME_ERROR".equals(ex.getErrorCode())) {
            log.warn("Rule execution business error: code={}, msg={}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.ok(buildBody(HttpStatus.OK, ex.getErrorCode(), ex.getMessage()));
        }
        // Other RuleExecutionException subclasses not in the table -> 500 INTERNAL_ERROR
        log.error("Unmapped RuleExecutionException", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest()
                .body(buildBody(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorResponse body = buildBody(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Internal error: " + ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // === Helpers ===

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, RuleExecutionException ex) {
        return ResponseEntity.status(status)
                .body(buildBody(status, ex.getErrorCode(), ex.getMessage()));
    }

    private static ErrorResponse buildBody(HttpStatus status, String code, String msg) {
        // traceId propagation: replaced by MDC/Span lookup once Sprint 1.4 wires OpenTelemetry
        String traceId = UUID.randomUUID().toString().replace("-", "");
        return new ErrorResponse(false, code, msg, traceId, status.value());
    }
}
