package com.orule.rule.execution.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.RuleExecutionServiceApplication;
import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.events.RuleSetCompletionPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;

/**
 * RFC-0041 §3.3 system-level (end-to-end) test for the rule execution service.
 *
 * <p>Boots the full Spring Boot application on a random Tomcat port via
 * {@link SpringBootTest.WebEnvironment#RANDOM_PORT}, then drives it with a real
 * HTTP client ({@link TestRestTemplate}) — the way an upstream service
 * (CustomerService, RFC-0040 §2) would call us in production.
 *
 * <p>Mocked boundary: only {@link RuleManagermentApiClient} (the Feign upstream
 * to {@code rule-management-service}). Every other bean — including the real
 * Groovy-backed {@code JavaSourceExecutor}, the JPA repository, the H2 + Flyway
 * stack, the {@code TenantInterceptor}, and Resilience4j — is the genuine
 * production wiring.
 *
 * <p>Goal: validate that what {@link com.orule.rule.execution.api.RuleExecutionControllerTest}
 * and {@link com.orule.rule.execution.api.RuleSetExecutionControllerTest} exercise
 * in isolation also holds when stitched together through a real servlet
 * container — especially:
 * <ol>
 *   <li>Groovy source compiles and executes inside {@code JavaSourceExecutor}.</li>
 *   <li>execution_log is persisted with correct status transitions
 *       (PENDING → RUNNING → SUCCESS / FAILED).</li>
 *   <li>The synchronous response body and the persisted log agree
 *       on taskId / outputContext / errorCode.</li>
 *   <li>RuleSet async path persists a terminal log row observable via
 *       {@code GET /api/v1/executions/{taskId}/logs}.</li>
 * </ol>
 *
 * <p>Why the Kafka consumer is disabled: dev profile default
 * {@code spring.kafka.bootstrap-servers=localhost:9092} is unreachable in CI;
 * the consumer would spam retries and slow tests down. We still want to drive
 * the async RuleSet path, so {@link RuleSetCompletionPublisher} is {@code @MockBean}'d
 * (no broker needed) while the rest of the JPA / executor path runs for real.
 */
@SpringBootTest(
        classes = RuleExecutionServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.bootstrap-servers=localhost:0",
                "orule.execution.pool.core-size=4",
                "orule.execution.pool.max-size=8",
                "orule.execution.pool.queue-capacity=16",
                "orule.execution.async.pool.core-size=4",
                "orule.execution.async.pool.max-size=8",
                "orule.execution.async.pool.queue-capacity=16"
        }
)
@DisplayName("CustomerService → rule-execution-service (system-level)")
public class CustomerServiceSystemTest {

    private static final String TENANT = "tenant-acme";
    private static final String OPERATOR = "user-1001";
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ExecutionLogRepository logRepo;

    @MockBean
    RuleManagermentApiClient ruleMgmt;

    @MockBean
    RuleSetCompletionPublisher ruleSetPublisher;

    @AfterEach
    void cleanUp() {
        // Worker thread outlives @Transactional scope; clear rows explicitly.
        logRepo.deleteAll();
    }

    // ===================================================================
    // §3.3.2 #1  Sync single-rule happy path
    // ===================================================================
    @Test
    @DisplayName("Sync happy path: POST /api/v1/rule-executions → 200 success=true, log row recorded as SUCCESS")
    void sync_happyPath_returnsSuccessAndPersistsLogMe() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("ORDER_VIP_DISCOUNT"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "ORDER_VIP_DISCOUNT",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        // Groovy source (JavaSourceExecutor is Groovy-backed per RFC-0020).
                        "result = price",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest(
                "ORDER_VIP_DISCOUNT", Map.of("price", 11));

        ResponseEntity<JsonNode> resp = postJson(
                "/api/v1/rule-executions", req, JsonNode.class);

        // ---- Response assertions ----
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("errorCode").isMissingNode()
                || body.path("errorCode").isNull()).isTrue();
        // JavaSourceExecutor returns ALL binding variables; input.x=1 plus rule-set result=42
        assertThat(body.path("outputContext").path("result").asInt()).isEqualTo(11);
        assertThat(body.path("outputContext").path("price").asInt()).isEqualTo(11);
        assertThat(body.path("taskId").asText()).isNotBlank();

        // ---- Log row assertions (RFC-0041 §3.2) ----
        String taskId = body.path("taskId").asText();
        var saved = logRepo.findByTaskId(taskId);
        assertThat(saved).isPresent();
        var row = saved.get();
        assertThat(row.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(row.getExecutionType().name()).isEqualTo("RULE");
        assertThat(row.getRuleCode()).isEqualTo("ORDER_VIP_DISCOUNT");
        assertThat(row.getRuleSetCode()).isNull();
        assertThat(row.getExecutorType()).isEqualTo("java-source");
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getOperatorId()).isEqualTo(OPERATOR);
        assertThat(row.getTraceId()).isEqualTo(TRACE);
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.getInputContext()).contains("\"price\":11");
        assertThat(row.getOutputContext()).contains("\"result\":11");
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getErrorMessage()).isNull();

        // ---- Cross-check: GET /api/v1/rule-executions/{taskId}/logs (RFC-0041 §5.1) ----
        ResponseEntity<JsonNode> logResp = getJson(
                "/api/v1/rule-executions/" + taskId + "/logs", JsonNode.class);
        assertThat(logResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode logBody = logResp.getBody();
        assertThat(logBody).isNotNull();
        assertThat(logBody.path("taskId").asText()).isEqualTo(taskId);
        assertThat(logBody.path("executionType").asText()).isEqualTo("RULE");
        assertThat(logBody.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(logBody.path("executorType").asText()).isEqualTo("java-source");
        assertThat(logBody.path("tenantId").asText()).isEqualTo(TENANT);
        assertThat(logBody.path("outputContext").path("result").asInt()).isEqualTo(11);
    }

    // ===================================================================
    // §3.3.2 #1  Sync single-rule happy path
    // ===================================================================
    @Test
    @DisplayName("Sync happy path: POST /api/v1/rule-executions → 200 success=true, log row recorded as SUCCESS")
    void sync_happyPath_returnsSuccessAndPersistsLog() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("ORDER_VIP_DISCOUNT"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "ORDER_VIP_DISCOUNT",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        // Groovy source (JavaSourceExecutor is Groovy-backed per RFC-0020).
                        "result = 42",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest(
                "ORDER_VIP_DISCOUNT", Map.of("x", 1));

        ResponseEntity<JsonNode> resp = postJson(
                "/api/v1/rule-executions", req, JsonNode.class);

        // ---- Response assertions ----
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("errorCode").isMissingNode()
                || body.path("errorCode").isNull()).isTrue();
        // JavaSourceExecutor returns ALL binding variables; input.x=1 plus rule-set result=42
        assertThat(body.path("outputContext").path("result").asInt()).isEqualTo(42);
        assertThat(body.path("outputContext").path("x").asInt()).isEqualTo(1);
        assertThat(body.path("taskId").asText()).isNotBlank();

        // ---- Log row assertions (RFC-0041 §3.2) ----
        String taskId = body.path("taskId").asText();
        var saved = logRepo.findByTaskId(taskId);
        assertThat(saved).isPresent();
        var row = saved.get();
        assertThat(row.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(row.getExecutionType().name()).isEqualTo("RULE");
        assertThat(row.getRuleCode()).isEqualTo("ORDER_VIP_DISCOUNT");
        assertThat(row.getRuleSetCode()).isNull();
        assertThat(row.getExecutorType()).isEqualTo("java-source");
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getOperatorId()).isEqualTo(OPERATOR);
        assertThat(row.getTraceId()).isEqualTo(TRACE);
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.getInputContext()).contains("\"x\":1");
        assertThat(row.getOutputContext()).contains("\"result\":42");
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getErrorMessage()).isNull();

        // ---- Cross-check: GET /api/v1/rule-executions/{taskId}/logs (RFC-0041 §5.1) ----
        ResponseEntity<JsonNode> logResp = getJson(
                "/api/v1/rule-executions/" + taskId + "/logs", JsonNode.class);
        assertThat(logResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode logBody = logResp.getBody();
        assertThat(logBody).isNotNull();
        assertThat(logBody.path("taskId").asText()).isEqualTo(taskId);
        assertThat(logBody.path("executionType").asText()).isEqualTo("RULE");
        assertThat(logBody.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(logBody.path("executorType").asText()).isEqualTo("java-source");
        assertThat(logBody.path("tenantId").asText()).isEqualTo(TENANT);
        assertThat(logBody.path("outputContext").path("result").asInt()).isEqualTo(42);
    }

    // ===================================================================
    // §3.3.2 #2  Sync eval-fail (Groovy compile error)
    // ===================================================================
    @Test
    @DisplayName("Sync eval-fail: success=false, errorCode=EVAL_FAILED, log row marked FAILED with EVAL_FAILED")
    void sync_evalFailure_returns200WithEvalFailedAndPersistsFailedLog() {
        when(ruleMgmt.getRuleMetadata(eq("BAD_RULE"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "BAD_RULE",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        "this is invalid groovy !!!",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest("BAD_RULE", Map.of("x", 1));

        ResponseEntity<JsonNode> resp = postJson(
                "/api/v1/rule-executions", req, JsonNode.class);

        // ---- Response assertions ----
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        // RFC-0040 §16.6: EVAL_FAILED stays at HTTP 200 with success=false.
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo("EVAL_FAILED");
        assertThat(body.path("errorMessage").asText()).contains("Failed to compile rule");
        // NOTE: GlobalExceptionHandler maps EVAL_FAILED to ErrorResponse (not
        // RuleExecutionResponse), so the body does not carry taskId. Look up
        // the persisted log row directly via the repository by ruleCode.
        String taskId = latestTaskIdForRule("BAD_RULE");
        assertThat(taskId).isNotBlank();

        // ---- Log row assertions ----
        var saved = logRepo.findByTaskId(taskId);
        assertThat(saved).isPresent();
        var row = saved.get();
        assertThat(row.getStatus().name()).isEqualTo("FAILED");
        assertThat(row.getExecutionType().name()).isEqualTo("RULE");
        assertThat(row.getRuleCode()).isEqualTo("BAD_RULE");
        assertThat(row.getExecutorType()).isEqualTo("java-source");
        assertThat(row.getErrorCode()).isEqualTo("EVAL_FAILED");
        assertThat(row.getErrorMessage()).isNotBlank();
        assertThat(row.getFinishedAt()).isNotNull();
    }

    // ===================================================================
    // §3.3.2 #3  Sync runtime error
    // ===================================================================
    @Test
    @DisplayName("Sync runtime error: success=false, errorCode=RUNTIME_ERROR, log row marked FAILED with RUNTIME_ERROR")
    void sync_runtimeError_returns200WithRuntimeErrorAndPersistsFailedLog() {
        when(ruleMgmt.getRuleMetadata(eq("BOOM_RULE"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "BOOM_RULE",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        "throw new RuntimeException(\"boom\")",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest("BOOM_RULE", Map.of("x", 1));

        ResponseEntity<JsonNode> resp = postJson(
                "/api/v1/rule-executions", req, JsonNode.class);

        // ---- Response assertions ----
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo("RUNTIME_ERROR");
        assertThat(body.path("errorMessage").asText()).contains("boom");
        // Same note as eval-failure: ErrorResponse body does not carry taskId.
        String taskId = latestTaskIdForRule("BOOM_RULE");
        assertThat(taskId).isNotBlank();

        // ---- Log row assertions ----
        var saved = logRepo.findByTaskId(taskId);
        assertThat(saved).isPresent();
        var row = saved.get();
        assertThat(row.getStatus().name()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).isEqualTo("RUNTIME_ERROR");
        assertThat(row.getErrorMessage()).contains("boom");
    }

    // ===================================================================
    // §3.3.2 #4  Sync missing tenant header
    // ===================================================================
    @Test
    @DisplayName("Sync missing tenant: HTTP 400 MISSING_TENANT_HEADER (TenantInterceptor on real Tomcat)")
    void sync_missingTenantHeader_rejectedByInterceptor() {
        RuleExecutionRequest req = new RuleExecutionRequest("X", Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // intentionally omit X-Tenant-Id / X-Operator-Id
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/rule-executions"),
                HttpMethod.POST,
                new HttpEntity<>(serialize(req), headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("MISSING_TENANT_HEADER");

        // No log row should be persisted when the interceptor short-circuits.
        assertThat(logRepo.count()).isZero();
    }

    // ===================================================================
    // §3.3.2 #5  RuleSet async happy path
    // ===================================================================
    @Test
    @DisplayName("RuleSet async: submit → poll → terminal SUCCESS with executor running for real")
    void ruleSet_async_happyPath_persistsSuccessLog() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("ORDER_PROMOTION_SUITE"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "ORDER_PROMOTION_SUITE",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        "discount = order_amount * 0.1\nprocessed = true",
                        List.of(), List.of(), Map.of()));

        // RuleSet submit body
        Map<String, Object> req = Map.of(
                "ruleSetCode", "ORDER_PROMOTION_SUITE",
                "context", Map.of("order_amount", 1000));

        ResponseEntity<JsonNode> submitResp = postJson(
                "/api/v1/rule-set-executions", req, JsonNode.class);

        assertThat(submitResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = submitResp.getBody().path("taskId").asText();
        assertThat(taskId).isNotBlank();
        assertThat(submitResp.getBody().path("status").asText()).isEqualTo("PENDING");

        // Worker thread is async; await terminal state with awaitility.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            ResponseEntity<JsonNode> queryResp = getJson(
                    "/api/v1/rule-set-executions/" + taskId, JsonNode.class);
            assertThat(queryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queryResp.getBody().path("status").asText()).isEqualTo("SUCCESS");
        });

        // ---- Detailed log via the existing /executions/{taskId}/logs endpoint ----
        ResponseEntity<JsonNode> logResp = getJson(
                "/api/v1/executions/" + taskId + "/logs", JsonNode.class);
        assertThat(logResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode logBody = logResp.getBody();
        assertThat(logBody.path("executionType").asText()).isEqualTo("RULE_SET");
        assertThat(logBody.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(logBody.path("ruleSetCode").asText()).isEqualTo("ORDER_PROMOTION_SUITE");
        assertThat(logBody.path("tenantId").asText()).isEqualTo(TENANT);
        // NOTE (RFC-0041 §8 gap): v0.7 RuleSetExecutorServiceImpl tracks
        // totalCount/successCount/failedCount locally but only persists them
        // via markPartialSuccess(...). For SUCCESS paths those columns stay
        // null in the row, so the NON_NULL @JsonInclude strips them from the
        // API response. Asserting presence is therefore deferred to RFC-0041 v0.2.
        // Output context: order_amount=1000 (preserved from input) + discount=100 + processed=true
        JsonNode outCtx = logBody.path("outputContext");
        assertThat(outCtx.path("order_amount").asInt()).isEqualTo(1000);
        assertThat(outCtx.path("discount").asInt()).isEqualTo(100);
        assertThat(outCtx.path("processed").asBoolean()).isTrue();
        assertThat(logBody.path("errorCode").isMissingNode()
                || logBody.path("errorCode").isNull()).isTrue();
    }

    // ===================================================================
    // §3.3.2 #7  Unknown taskId
    // ===================================================================
    @Test
    @DisplayName("Unknown taskId → HTTP 404 TASK_NOT_FOUND on sync log endpoint")
    void syncLog_unknownTaskId_returns404TaskNotFound() {
        ResponseEntity<JsonNode> resp = getJson(
                "/api/v1/rule-executions/no-such-task/logs", JsonNode.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // GlobalExceptionHandler wraps the body in ErrorResponse with errorCode field.
        assertThat(resp.getBody().path("errorCode").asText()).isEqualTo("TASK_NOT_FOUND");
    }

    // ===================================================================
    // §3.3.2 #6  RuleSet single rule fails → terminal FAILED
    // (v0.7 placeholder: single rule per set, hence FAILED rather than PARTIAL_SUCCESS)
    // ===================================================================
    @Test
    @DisplayName("RuleSet rule fails: terminal FAILED with errorCode populated in log")
    void ruleSet_async_ruleFails_persistsFailedLog() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("BAD_RULE_SET"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "BAD_RULE_SET",
                        "RULE_TYPE_DEMO",
                        "java-source",
                        "this is invalid groovy !!!",
                        List.of(), List.of(), Map.of()));

        Map<String, Object> req = Map.of(
                "ruleSetCode", "BAD_RULE_SET",
                "context", Map.of("x", 1));

        ResponseEntity<JsonNode> submitResp = postJson(
                "/api/v1/rule-set-executions", req, JsonNode.class);

        assertThat(submitResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = submitResp.getBody().path("taskId").asText();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            ResponseEntity<JsonNode> queryResp = getJson(
                    "/api/v1/rule-set-executions/" + taskId, JsonNode.class);
            assertThat(queryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queryResp.getBody().path("status").asText()).isEqualTo("FAILED");
        });

        ResponseEntity<JsonNode> logResp = getJson(
                "/api/v1/executions/" + taskId + "/logs", JsonNode.class);
        assertThat(logResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode logBody = logResp.getBody();
        assertThat(logBody.path("status").asText()).isEqualTo("FAILED");
        // v0.7 markFailed does not persist totalCount/successCount/failedCount;
        // see the gap noted in ruleSet_async_happyPath_persistsSuccessLog.
        // The errorMessage wraps the per-rule failure summary; the underlying
        // Groovy compile error is logged at WARN level by JavaSourceExecutor.
        assertThat(logBody.path("errorCode").asText()).isEqualTo("RULE_SET_FAILED");
        assertThat(logBody.path("errorMessage").asText()).contains("rules failed");
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private <T> ResponseEntity<T> postJson(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Tenant-Id", TENANT);
        headers.add("X-Operator-Id", OPERATOR);
        headers.add("X-Trace-Id", TRACE);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(serialize(body), headers), responseType);
    }

    private <T> ResponseEntity<T> getJson(String path, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Tenant-Id", TENANT);
        headers.add("X-Operator-Id", OPERATOR);
        headers.add("X-Trace-Id", TRACE);
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), responseType);
    }

    private String serialize(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    /**
     * Look up the most recent execution_log row matching {@code ruleCode}.
     * Used when the HTTP response body does not carry a {@code taskId}
     * (ErrorResponse path; see RFC-0041 §6 / §8).
     */
    private String latestTaskIdForRule(String ruleCode) {
        var rows = logRepo.findRecentByRuleCode(
                ruleCode, PageRequest.of(0, 1));
        assertThat(rows).as("expected at least one log row for ruleCode=" + ruleCode)
                .isNotEmpty();
        return rows.get(0).getTaskId();
    }
}
