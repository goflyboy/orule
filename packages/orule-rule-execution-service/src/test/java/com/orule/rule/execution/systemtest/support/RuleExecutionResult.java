package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.domain.ExecutionLog;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Fluent assertion surface for a synchronous single-rule execution.
 *
 * <p>Returned by
 * {@link RuleExecutionSystemTestBase#executeRule(String, ExecutionInputBuilder)}.
 *
 * <pre>{@code
 * RuleExecutionResult r = executeRule("X", input().set("price", 100));
 * r.isOk()
 *  .field("result", is(100))
 *  .field("status", is("SUCCESS"));
 * }</pre>
 *
 * <p>Every {@code field}/{@code isXxx} call returns {@code this} so calls chain
 * without temp variables.
 */
public class RuleExecutionResult {

    private final ResponseEntity<String> raw;
    private final ResultSnapshot snapshot;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode body;
    private final String taskId;

    public RuleExecutionResult(ResponseEntity<String> raw,
                               ResultSnapshot snapshot,
                               ExecutionLogRepository logRepo) {
        this.raw = raw;
        this.snapshot = snapshot;
        this.body = parse(raw);
        this.taskId = body.path("taskId").asText(null);
    }

    // --- HTTP / status ---

    public RuleExecutionResult isHttpStatus(HttpStatus expected) {
        assertThat(raw.getStatusCode(), is(expected));
        return this;
    }

    public RuleExecutionResult isOk() {
        // Sync success may surface as HTTP 200 with success=true OR as HTTP 200
        // with success=false + errorCode in {EVAL_FAILED, RUNTIME_ERROR}; the
        // "happy path" notion is success=true.
        assertThat("expected business success", body.path("success").asBoolean(), is(true));
        assertThat("expected no errorCode on success",
                body.path("errorCode").isMissingNode() || body.path("errorCode").isNull(),
                is(true));
        return this;
    }

    public RuleExecutionResult isFailed(String expectedErrorCode) {
        assertThat("expected business failure", body.path("success").asBoolean(), is(false));
        assertThat(body.path("errorCode").asText(), is(expectedErrorCode));
        return this;
    }

    // --- Field-level assertions ---

    public RuleExecutionResult field(String jsonPath, Matcher<Object> matcher) {
        assertThat("field " + jsonPath, body.at(jsonPath), notNullValue());
        Object actual = coerce(body.at(jsonPath));
        assertThat("field " + jsonPath, actual, matcher);
        return this;
    }

    public RuleExecutionResult fieldString(String jsonPath, Matcher<String> matcher) {
        assertThat("field " + jsonPath, body.at(jsonPath), notNullValue());
        assertThat("field " + jsonPath, body.at(jsonPath).asText(), matcher);
        return this;
    }

    public RuleExecutionResult fieldMessage(Matcher<String> matcher) {
        return fieldString("/errorMessage", matcher);
    }

    // --- Log row surface ---

    /**
     * Apply the matcher against the persisted {@link ExecutionLog} row for
     * this task. Skips silently when no row exists (e.g. when the
     * TenantInterceptor short-circuited the call).
     */
    public RuleExecutionResult log(LogAssert asserter) {
        Optional<ExecutionLog> row = snapshot.logRepo.findByTaskId(taskId);
        asserter.check(row.orElse(null), taskId);
        return this;
    }

    // --- Escape hatch ---

    /** Underlying Jackson body for advanced assertions; tests should rarely need this. */
    public JsonNode rawBody() {
        return body;
    }

    public String taskId() {
        return taskId;
    }

    private static JsonNode parse(ResponseEntity<String> raw) {
        try {
            return new ObjectMapper().readTree(raw.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body: " + raw.getBody(), e);
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

    // --- Static helpers re-exporting Hamcrest matchers for terse imports ---

    public static Matcher<Object> is(Object value) {
        return Matchers.is(value);
    }

    public static Matcher<Object> equalTo(Object value) {
        return Matchers.equalTo(value);
    }

    public static org.hamcrest.Matcher<String> containsString(String fragment) {
        return Matchers.containsString(fragment);
    }
}
