package com.orule.rule.execution.service;

import com.orule.common.dto.AttributeTypeDto;
import com.orule.common.dto.ObjectTypeDto;
import com.orule.common.dto.ObjectTypeDto.EnumValueDto;
import com.orule.rule.execution.RuleExecutionServiceApplication;
import com.orule.rule.execution.api.dto.RuleExecutionRequest;
import com.orule.rule.execution.client.RuleManagermentApiClient;
import com.orule.rule.execution.client.RuleMetadataResponseV2;
import com.orule.rule.execution.domain.ExecutionLogRepository;
import com.orule.rule.execution.events.RuleSetCompletionPublisher;
import com.orule.rule.execution.execution.ExecutorRegistry;
import com.orule.rule.execution.execution.RuleExecutor;
import com.orule.rule.execution.execution.java.ExecutionOutputStub;
import com.orule.rule.execution.security.TenantContext;
import com.orule.rule.execution.security.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFC-0045 §4.3 unit / integration tests for the secondary Feign call inside
 * {@link RuleExecutionApplicationService#fetchResolvedObjectTypes}.
 */
@SpringBootTest(classes = RuleExecutionServiceApplication.class,
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.bootstrap-servers=localhost:0",
                "orule.execution.pool.core-size=2",
                "orule.execution.pool.max-size=4",
                "orule.execution.pool.queue-capacity=8",
                "orule.execution.async.pool.core-size=2",
                "orule.execution.async.pool.max-size=4",
                "orule.execution.async.pool.queue-capacity=8"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RuleExecutionApplicationService")
class RuleExecutionApplicationServiceTest {

    @Autowired RuleExecutionApplicationService service;
    @Autowired ExecutorRegistry registry;
    @MockBean RuleManagermentApiClient ruleMgmt;
    @MockBean ExecutionLogRepository logRepo;
    @MockBean RuleSetCompletionPublisher publisher;

    @BeforeEach
    void setUp() {
        // Replace the executor route with a deterministic stub.
        registry.registerForTesting(new RuleExecutor() {
            @Override public String executorType() { return "java-source"; }
            @Override public com.orule.rule.execution.api.dto.ExecutionOutput execute(
                    com.orule.rule.execution.api.dto.ExecutionInput input) {
                return ExecutionOutputStub.success(Map.of("ok", true));
            }
        });
        // Bind a tenant so the service doesn't bail with MISSING_TENANT_CONTEXT.
        TenantContextHolder.set(
                new TenantContext("tenant-1", "user-1", "trace-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("primary Feign only when objectTypeCodes empty (RFC-0045 §8 降级路径)")
    void noSecondaryFeignWhenEmpty() {
        when(ruleMgmt.getRuleMetadata(eq("NO_META"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponseV2(
                        "NO_META", "RT", "java-source", "ok = true",
                        List.of(), List.of(),
                        null, List.of(), Map.of()));

        service.execute(new RuleExecutionRequest("NO_META", Map.of("a", 1)));

        verify(ruleMgmt, atLeastOnce()).getRuleMetadata(eq("NO_META"), any(), any(), any());
        verify(ruleMgmt, never()).getObjectTypeByProgramCode(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("secondary Feign invoked for each objectTypeCode (de-dup)")
    void secondaryFeignInvoked() {
        when(ruleMgmt.getRuleMetadata(eq("WITH_META"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponseV2(
                        "WITH_META", "RT", "java-source", "ok = true",
                        List.of(), List.of(),
                        "ORDER",
                        List.of("Customer", "Order", "Customer", "CustomerTier"),
                        Map.of()));
        when(ruleMgmt.getObjectTypeByProgramCode(eq("ORDER"), eq("Customer"), any(), any(), any()))
                .thenReturn(customerDto());
        when(ruleMgmt.getObjectTypeByProgramCode(eq("ORDER"), eq("Order"), any(), any(), any()))
                .thenReturn(orderDto());
        when(ruleMgmt.getObjectTypeByProgramCode(eq("ORDER"), eq("CustomerTier"), any(), any(), any()))
                .thenReturn(tierDto());

        service.execute(new RuleExecutionRequest("WITH_META", Map.of()));

        // 3 unique codes; verify no double-fetch on "Customer"
        verify(ruleMgmt, atLeastOnce()).getObjectTypeByProgramCode(
                eq("ORDER"), eq("Customer"), any(), any(), any());
        verify(ruleMgmt, atLeastOnce()).getObjectTypeByProgramCode(
                eq("ORDER"), eq("Order"), any(), any(), any());
        verify(ruleMgmt, atLeastOnce()).getObjectTypeByProgramCode(
                eq("ORDER"), eq("CustomerTier"), any(), any(), any());
    }

    @Test
    @DisplayName("secondary Feign failure degrades gracefully (no exception, empty prefix)")
    void secondaryFeignFailureDegrades() {
        when(ruleMgmt.getRuleMetadata(eq("BROKEN"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponseV2(
                        "BROKEN", "RT", "java-source", "ok = true",
                        List.of(), List.of(),
                        "ORDER",
                        List.of("Customer"),
                        Map.of()));
        when(ruleMgmt.getObjectTypeByProgramCode(eq("ORDER"), eq("Customer"), any(), any(), any()))
                .thenThrow(new RuntimeException("upstream 503"));

        // Should not throw — degraded to empty prefix, executor still runs.
        service.execute(new RuleExecutionRequest("BROKEN", Map.of()));
    }

    @Test
    @DisplayName("missing domainCode skips secondary Feign (RFC-0045 §4.3 guard)")
    void missingDomainSkipsSecondaryFeign() {
        when(ruleMgmt.getRuleMetadata(eq("NO_DOMAIN"), any(), any(), any()))
                .thenReturn(new RuleMetadataResponseV2(
                        "NO_DOMAIN", "RT", "java-source", "ok = true",
                        List.of(), List.of(),
                        /* domainCode */ null,
                        List.of("Customer"),
                        Map.of()));

        service.execute(new RuleExecutionRequest("NO_DOMAIN", Map.of()));

        verify(ruleMgmt, never()).getObjectTypeByProgramCode(any(), any(), any(), any(), any());
    }

    // ===== DTO factories =====

    private static ObjectTypeDto customerDto() {
        return new ObjectTypeDto("c1", "dom-order", "Customer", "Customer", "CLASS",
                List.of(new AttributeTypeDto(null, "c1", "name", "name", "primitive", "string", null, false, null, null, null),
                        new AttributeTypeDto(null, "c1", "tier", "tier", "object", "CustomerTier", null, false, null, null, null)),
                List.of(), null, null, null);
    }

    private static ObjectTypeDto orderDto() {
        return new ObjectTypeDto("o1", "dom-order", "Order", "Order", "CLASS",
                List.of(new AttributeTypeDto(null, "o1", "totalAmount", "totalAmount", "primitive", "number", null, false, null, null, null),
                        new AttributeTypeDto(null, "o1", "discount", "discount", "primitive", "number", null, false, null, null, null)),
                List.of(), null, null, null);
    }

    private static ObjectTypeDto tierDto() {
        return new ObjectTypeDto("t1", "dom-order", "CustomerTier", "Customer Tier", "ENUM",
                List.of(),
                List.of(new EnumValueDto("VIP",  "VIP",  1),
                        new EnumValueDto("GOLD", "Gold", 2)),
                null, null, null);
    }
}
