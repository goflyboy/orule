package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.RuleExecutionServiceApplication;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.events.RuleSetCompletionPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for RFC-0042 system-test fluent DSL.
 *
 * <p>Wires the same Spring Boot context as {@code CustomerServiceSystemTest}
 * (commit {@code a1997d1}, RFC-0041 §3.3.1) but exposes a one-call surface:
 *
 * <ul>
 *   <li>{@link #mockRule(String)} — fluent Feign mock configuration</li>
 *   <li>{@link #input()} — fluent input builder, accepts POJOs via {@code also(...)}</li>
 *   <li>{@link #executeRule(String, ExecutionInputBuilder)} — one-line sync execution</li>
 *   <li>{@link #executeRuleSet(String, ExecutionInputBuilder)} — one-line async execution</li>
 * </ul>
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} keeps the frameworked test class from
 * polluting {@code CustomerServiceSystemTest}'s bean cache (and vice versa)
 * via {@code @MockBean} replacement.
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class RuleExecutionSystemTestBase {

    /** Default headers for every outbound request. Override in tests via {@link RequestOptions}. */
    protected static final String TENANT = "tenant-acme";
    protected static final String OPERATOR = "user-1001";
    protected static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private static final Logger log = LoggerFactory.getLogger(RuleExecutionSystemTestBase.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected ExecutionLogRepository logRepo;

    @MockBean
    protected RuleManagermentApiClient ruleMgmt;

    @MockBean
    protected RuleSetCompletionPublisher ruleSetPublisher;

    /** Holds the most recent result for {@link #dumpOnFailure} on assertion failure. */
    private ResultSnapshot lastSnapshot;

    /** Builders created via {@link #mockRule(String)} and not yet installed. */
    private final Map<String, MockRuleBuilder> pendingMocks = new LinkedHashMap<>();

    // ===================================================================
    // DSL entry points
    // ===================================================================

    protected MockRuleBuilder mockRule(String code) {
        MockRuleBuilder b = new MockRuleBuilder(ruleMgmt, code);
        pendingMocks.put(code, b);
        return b;
    }

    protected ExecutionInputBuilder input() {
        return new ExecutionInputBuilder(mapper);
    }

    protected RuleExecutionResult executeRule(String ruleCode, ExecutionInputBuilder input) {
        return executeRule(ruleCode, input.build(), RequestOptions.defaults());
    }

    protected RuleExecutionResult executeRule(String ruleCode,
                                              ExecutionInputBuilder input,
                                              RequestOptions options) {
        return executeRule(ruleCode, input.build(), options);
    }

    protected RuleSetExecutionResult executeRuleSet(String ruleSetCode,
                                                    ExecutionInputBuilder input) {
        return executeRuleSet(ruleSetCode, input.build(), RequestOptions.defaults());
    }

    // ===================================================================
    // Execution core
    // ===================================================================

    private RuleExecutionResult executeRule(String ruleCode,
                                            Map<String, Object> context,
                                            RequestOptions options) {
        installPendingMocks();
        Map<String, Object> body = new HashMap<>();
        body.put("ruleCode", ruleCode);
        body.put("context", context);

        ResponseEntity<String> raw = exchange(HttpMethod.POST,
                "/api/v1/rule-executions", body, options);
        lastSnapshot = new ResultSnapshot("POST /api/v1/rule-executions",
                body, raw, logRepo);
        return new RuleExecutionResult(raw, lastSnapshot, logRepo);
    }

    private RuleSetExecutionResult executeRuleSet(String ruleSetCode,
                                                  Map<String, Object> context,
                                                  RequestOptions options) {
        installPendingMocks();
        Map<String, Object> body = new HashMap<>();
        body.put("ruleSetCode", ruleSetCode);
        body.put("context", context);

        ResponseEntity<String> submitRaw = exchange(HttpMethod.POST,
                "/api/v1/rule-set-executions", body, options);
        lastSnapshot = new ResultSnapshot("POST /api/v1/rule-set-executions",
                body, submitRaw, logRepo);

        return new RuleSetExecutionResult(submitRaw, lastSnapshot, rest, port,
                "/api/v1/rule-set-executions", "/api/v1/executions", logRepo, options);
    }

    protected RequestOptions withoutTenantHeader() {
        return RequestOptions.withoutTenant();
    }

    /**
     * (Re)installs every mock registered via {@link #mockRule(String)} on
     * this test. Mockito's {@code thenReturn(...)} replaces prior stubs, so
     * calling this on every execute is safe. Builders stay registered for the
     * duration of the test method (cleared in {@code @AfterEach}).
     */
    private void installPendingMocks() {
        for (MockRuleBuilder b : pendingMocks.values()) {
            b.install();
        }
    }

    // ===================================================================
    // Low-level HTTP
    // ===================================================================

    private ResponseEntity<String> exchange(HttpMethod method,
                                            String path,
                                            Object body,
                                            RequestOptions options) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (options.sendTenant()) {
            headers.add("X-Tenant-Id", TENANT);
            headers.add("X-Operator-Id", OPERATOR);
            headers.add("X-Trace-Id", TRACE);
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
        return rest.exchange(url(path), method,
                new HttpEntity<>(payload, headers), String.class);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ===================================================================
    // Cleanup + failure dump
    // ===================================================================

    @AfterEach
    final void cleanupAndDump(TestInfo info) {
        try {
            dumpOnFailure(info);
        } finally {
            logRepo.deleteAll();
            pendingMocks.clear();
        }
    }

    private void dumpOnFailure(TestInfo info) {
        if (lastSnapshot == null || !lastSnapshot.indicatesFailure()) {
            return;
        }
        log.error("[RFC-0042 FAILURE DUMP] test={}\n  request={}\n  response={}\n  logRow={}",
                info.getDisplayName(),
                lastSnapshot.request,
                lastSnapshot.response,
                lastSnapshot.fetchLogRow());
    }

    /** Test classes may read this if they want to assert on the last result directly. */
    protected final ResultSnapshot lastSnapshot() {
        return lastSnapshot;
    }
}
