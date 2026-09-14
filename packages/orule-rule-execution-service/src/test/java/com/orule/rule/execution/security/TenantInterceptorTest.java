package com.orule.rule.execution.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 §3.5 + TASK-1.3.2 + §18.SEC-AC-03 acceptance tests.
 */
@DisplayName("TenantInterceptor + TenantContextHolder")
class TenantInterceptorTest {

    private TenantInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantInterceptor();
    }

    @AfterEach
    void tearDown() {
        // Always verify the holder is clean after each test.
        assertThat(TenantContextHolder.current()).isNull();
    }

    @Test
    @DisplayName("happy path: stores tenant+operator and clears on completion")
    void storesContext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/rule-executions");
        req.addHeader(TenantInterceptor.HEADER_TENANT, "tenant-1");
        req.addHeader(TenantInterceptor.HEADER_OPERATOR, "user-1");
        req.addHeader(TenantInterceptor.HEADER_TRACE, "trace-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean continued = interceptor.preHandle(req, resp, new Object());
        assertThat(continued).isTrue();
        TenantContext ctx = TenantContextHolder.current();
        assertThat(ctx).isNotNull();
        assertThat(ctx.tenantId()).isEqualTo("tenant-1");
        assertThat(ctx.operatorId()).isEqualTo("user-1");
        assertThat(ctx.traceId()).isEqualTo("trace-1");

        interceptor.afterCompletion(req, resp, new Object(), null);
        assertThat(TenantContextHolder.current()).isNull();
    }

    @Test
    @DisplayName("SEC-AC-03: missing X-Tenant-Id returns 400 with errorCode MISSING_TENANT_HEADER")
    void missingTenantRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/rule-executions");
        req.addHeader(TenantInterceptor.HEADER_OPERATOR, "user-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean continued = interceptor.preHandle(req, resp, new Object());
        assertThat(continued).isFalse();
        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).contains("MISSING_TENANT_HEADER");
    }

    @Test
    @DisplayName("missing X-Operator-Id returns 400")
    void missingOperatorRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/rule-executions");
        req.addHeader(TenantInterceptor.HEADER_TENANT, "tenant-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean continued = interceptor.preHandle(req, resp, new Object());
        assertThat(continued).isFalse();
        assertThat(resp.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("actuator/health bypasses tenant enforcement")
    void actuatorBypass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean continued = interceptor.preHandle(req, resp, new Object());
        assertThat(continued).isTrue();
        // No headers → no context populated.
        assertThat(TenantContextHolder.current()).isNull();
    }

    @Test
    @DisplayName("swagger /v3/api-docs bypasses tenant enforcement")
    void swaggerBypass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean continued = interceptor.preHandle(req, resp, new Object());
        assertThat(continued).isTrue();
    }
}
