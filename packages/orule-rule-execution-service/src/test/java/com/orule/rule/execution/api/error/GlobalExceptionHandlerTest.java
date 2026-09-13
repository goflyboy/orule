package com.orule.rule.execution.api.error;

import com.orule.rule.execution.error.ExecutorNotRegisteredException;
import com.orule.rule.execution.error.RateLimitExceededException;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleNotFoundException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.error.RuleSetNotFoundException;
import com.orule.rule.execution.error.TaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 §16.6 + TASK-1.1.5 验收：9 种错误码（含 success=true）全部映射正确。
 *
 * <p>本测试直接 new GlobalExceptionHandler 并调用各 handleXxx 方法，避免启动完整 Web 容器。
 * 真正端到端验证留给 Sprint 1.4 引入 MockMvc 后。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void ruleNotFound_returns404() {
        ResponseEntity<ErrorResponse> r = handler.handleRuleNotFound(new RuleNotFoundException("ORDER_VIP_DISCOUNT"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().errorCode()).isEqualTo("RULE_NOT_FOUND");
        assertThat(r.getBody().success()).isFalse();
        assertThat(r.getBody().errorMessage()).contains("ORDER_VIP_DISCOUNT");
        assertThat(r.getBody().httpStatus()).isEqualTo(404);
    }

    @Test
    void ruleSetNotFound_returns404() {
        ResponseEntity<ErrorResponse> r = handler.handleRuleSetNotFound(new RuleSetNotFoundException("ORDER_PROMOTION_SUITE"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().errorCode()).isEqualTo("RULE_SET_NOT_FOUND");
    }

    @Test
    void taskNotFound_returns404() {
        ResponseEntity<ErrorResponse> r = handler.handleTaskNotFound(new TaskNotFoundException("task-xyz"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().errorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void rateLimit_returns429_withRetryAfterHeader() {
        ResponseEntity<ErrorResponse> r = handler.handleRateLimit(new RateLimitExceededException("too many", 3500));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody().errorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3"); // 3500ms / 1000 = 3
    }

    @Test
    void rateLimit_minimumRetryAfterIs1Second() {
        ResponseEntity<ErrorResponse> r = handler.handleRateLimit(new RateLimitExceededException("burst", 100));
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void executorNotRegistered_returns500() {
        ResponseEntity<ErrorResponse> r = handler.handleExecutorNotRegistered(new ExecutorNotRegisteredException("RustExecutor"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().errorCode()).isEqualTo("EXECUTOR_NOT_REGISTERED");
    }

    @Test
    void evalFailed_returnsHttp200_businessError() {
        // RuleEvalException is a RuleExecutionException subclass with EVAL_FAILED -> mapped to HTTP 200
        ResponseEntity<ErrorResponse> r = handler.handleRuleExecution(new RuleEvalException("bad AST"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().errorCode()).isEqualTo("EVAL_FAILED");
        assertThat(r.getBody().success()).isFalse();
    }

    @Test
    void runtimeError_returnsHttp200_businessError() {
        ResponseEntity<ErrorResponse> r = handler.handleRuleExecution(new RuleRuntimeException("NPE in rule"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().errorCode()).isEqualTo("RUNTIME_ERROR");
    }

    @Test
    void unknownException_returns500_internalError() {
        ResponseEntity<ErrorResponse> r = handler.handleUnknown(new RuntimeException("oops"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.getBody().errorMessage()).contains("RuntimeException");
    }
}
