package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import org.hamcrest.Matcher;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Fluent result surface for an asynchronous RuleSet execution.
 *
 * <p>Returned by
 * {@link RuleExecutionSystemTestBase#executeRuleSet(String, ExecutionInputBuilder)}.
 *
 * <pre>{@code
 * RuleSetExecutionResult r = executeRuleSet("ORDER_PROMOTION_SUITE", input())
 *     .awaitTerminal(Duration.ofSeconds(10));
 * r.isOk().field("discount", is(100));
 * }</pre>
 */
public class RuleSetExecutionResult {

    private final ResponseEntity<String> submitRaw;
    private final ResultSnapshot submitSnapshot;
    private final TestRestTemplate rest;
    private final int port;
    private final String resultPath;
    private final String logPath;
    private final ExecutionLogRepository logRepo;
    private final RequestOptions options;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String taskId;
    private String terminalStatus;

    public RuleSetExecutionResult(ResponseEntity<String> submitRaw,
                                   ResultSnapshot submitSnapshot,
                                   TestRestTemplate rest,
                                   int port,
                                   String resultPath,
                                   String logPath,
                                   ExecutionLogRepository logRepo,
                                   RequestOptions options) {
        this.submitRaw = submitRaw;
        this.submitSnapshot = submitSnapshot;
        this.rest = rest;
        this.port = port;
        this.resultPath = resultPath;
        this.logPath = logPath;
        this.logRepo = logRepo;
        this.options = options;
        this.taskId = parseSubmitTaskId(submitRaw);
    }

    /**
     * Block until the persisted log row reaches a terminal status. The default
     * timeout is 10 seconds with 100 ms poll — matches RFC-0041 v0.1.
     */
    public RuleSetExecutionResult awaitTerminal(Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            ResponseEntity<String> q = fetchStatus();
            JsonNode body = parse(q);
            String status = body.path("status").asText();
            assertThat("expected terminal status, got " + status,
                    "PENDING".equals(status) || "RUNNING".equals(status),
                    is(false));
            terminalStatus = status;
        });
        return this;
    }

    public RuleSetExecutionResult isOk() {
        assertThat("expected terminal SUCCESS, got " + terminalStatus,
                terminalStatus, is("SUCCESS"));
        return this;
    }

    public RuleSetExecutionResult isFailed(String expectedErrorCode) {
        assertThat("expected terminal FAILED, got " + terminalStatus,
                terminalStatus, is("FAILED"));
        JsonNode detail = fetchLogDetail();
        assertThat(detail.path("errorCode").asText(), is(expectedErrorCode));
        return this;
    }

    /**
     * Apply assertions against the {@code /executions/{taskId}/logs} detail.
     */
    public RuleSetExecutionResult detail(DetailAssert asserter) {
        JsonNode detail = fetchLogDetail();
        asserter.check(detail);
        return this;
    }

    public RuleSetExecutionResult field(String jsonPath, Matcher<Object> matcher) {
        JsonNode detail = fetchLogDetail();
        assertThat("field " + jsonPath, detail.at(jsonPath), notNullValue());
        Object actual = coerce(detail.at(jsonPath));
        assertThat("field " + jsonPath, actual, matcher);
        return this;
    }

    public String taskId() {
        return taskId;
    }

    // -- helpers --

    private ResponseEntity<String> fetchStatus() {
        HttpHeaders headers = new HttpHeaders();
        if (options.sendTenant()) {
            headers.add("X-Tenant-Id", RuleExecutionSystemTestBase.TENANT);
            headers.add("X-Operator-Id", RuleExecutionSystemTestBase.OPERATOR);
            headers.add("X-Trace-Id", RuleExecutionSystemTestBase.TRACE);
        }
        return rest.exchange("http://localhost:" + port + resultPath + "/" + taskId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode fetchLogDetail() {
        HttpHeaders headers = new HttpHeaders();
        if (options.sendTenant()) {
            headers.add("X-Tenant-Id", RuleExecutionSystemTestBase.TENANT);
            headers.add("X-Operator-Id", RuleExecutionSystemTestBase.OPERATOR);
            headers.add("X-Trace-Id", RuleExecutionSystemTestBase.TRACE);
        }
        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + port + logPath + "/" + taskId + "/logs",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat("GET log detail status", resp.getStatusCode().is2xxSuccessful(),
                is(true));
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse log detail: " + resp.getBody(), e);
        }
    }

    private static String parseSubmitTaskId(ResponseEntity<String> raw) {
        try {
            return new ObjectMapper().readTree(raw.getBody()).path("taskId").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse submit response", e);
        }
    }

    private static JsonNode parse(ResponseEntity<String> raw) {
        try {
            return new ObjectMapper().readTree(raw.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    private static Object coerce(JsonNode node) {
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return node.asText();
        return node.toString();
    }
}
