package com.orule.rule.execution.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.api.dto.RuleExecutionResponse;
import com.orule.rule.execution.api.error.GlobalExceptionHandler;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponse;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.ExecutorRegistry;
import com.orule.rule.execution.execution.RuleExecutorService;
import com.orule.rule.execution.service.RuleExecutionApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC-0040 §3.5 + §16.1 + TASK-1.3.1 acceptance tests for {@link RuleExecutionController}.
 *
 * <p>Uses MockMvc + Spring context (with Feign client mocked) so the full MVC pipeline
 * — interceptor → controller → service → mocked Feign → mocked executor — is exercised.
 *
 * <p>Three scenarios match RFC §16.1:
 * <ul>
 *   <li>happy path (success=true)</li>
 *   <li>evaluation failure (EVAL_FAILED via success=false)</li>
 *   <li>missing tenant header (400 MISSING_TENANT_HEADER)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("POST /api/v1/rule-executions")
class RuleExecutionControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @MockBean
    RuleManagermentApiClient ruleMgmt;

    // We inject a fake ExecutorRegistry to keep RuleExecutorService deterministic.
    @Autowired
    ExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        // Replace the SPI route so the controller doesn't need a real network.
        // For evalFailed tests the fake throws; otherwise it returns success.
        registry.registerForTesting(new com.orule.rule.execution.execution.RuleExecutor() {
            @Override public String executorType() { return "java-source"; }
            @Override public ExecutionOutput execute(com.orule.rule.execution.api.dto.ExecutionInput input) {
                if (input.sourceCode() != null && input.sourceCode().contains("invalid groovy")) {
                    throw new com.orule.rule.execution.error.RuleEvalException(
                            "Failed to compile rule: invalid groovy !!!");
                }
                return new ExecutionOutput(Map.of("result", 42), true, null, null);
            }
        });
    }

    @Test
    @DisplayName("happy path: HTTP 200, success=true, outputContext={result:42}")
    void happyPath() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("ORDER_VIP_DISCOUNT"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "ORDER_VIP_DISCOUNT", "RULE_TYPE_DEMO",
                        "java-source",
                        "result = 42",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest("ORDER_VIP_DISCOUNT", Map.of("x", 1));
        mvc.perform(post("/api/v1/rule-executions")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1")
                        .header("X-Trace-Id", "trace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.outputContext.result").value(42))
                .andExpect(jsonPath("$.taskId").exists());
    }

    @Test
    @DisplayName("evaluation failure: HTTP 200, success=false, errorCode=EVAL_FAILED")
    void evalFailed() throws Exception {
        when(ruleMgmt.getRuleMetadata(eq("BAD_RULE"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponse(
                        "BAD_RULE", "RULE_TYPE_DEMO",
                        "java-source",
                        "this is invalid groovy !!!",
                        List.of(), List.of(), Map.of()));

        RuleExecutionRequest req = new RuleExecutionRequest("BAD_RULE", Map.of());
        mvc.perform(post("/api/v1/rule-executions")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Operator-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("EVAL_FAILED"));
    }

    @Test
    @DisplayName("missing tenant header: HTTP 400 MISSING_TENANT_HEADER")
    void missingTenantHeader() throws Exception {
        RuleExecutionRequest req = new RuleExecutionRequest("X", Map.of());
        mvc.perform(post("/api/v1/rule-executions")
                        .header("X-Operator-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MISSING_TENANT_HEADER")));
    }

    @Test
    @DisplayName("openapi contract: GET /v3/api-docs returns the rule-executions endpoint")
    void openApiDocumented() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/v1/rule-executions").exists())
                .andExpect(jsonPath("$.paths./api/v1/rule-executions.post").exists())
                .andExpect(jsonPath("$.components.schemas.RuleExecutionRequest").exists())
                .andExpect(jsonPath("$.components.schemas.RuleExecutionResponse").exists());
    }
}
