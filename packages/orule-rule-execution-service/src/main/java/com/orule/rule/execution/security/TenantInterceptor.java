package com.orule.rule.execution.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tenant + operator context interceptor (RFC-0040 §3.5 / TASK-1.3.2).
 *
 * <p>Reads headers from the request and pushes a {@link TenantContext} into
 * {@link TenantContextHolder}.  Always clears the holder in {@code afterCompletion}
 * so the worker thread can be safely returned to the servlet container pool.
 *
 * <p><b>v0.7 stub</b>: header trust assumes an upstream API gateway has already
 * authenticated and stripped the JWT (per RFC-0040 §16.1).  Full JWT signature
 * verification is TASK-3.4.1 (not in P0).
 *
 * <p>Behavior:
 * <ul>
 *   <li>If both {@code X-Tenant-Id} and {@code X-Operator-Id} are present, push
 *       a context and continue.</li>
 *   <li>If either is missing on a protected path, write 400 and short-circuit.</li>
 *   <li>Health / docs paths are exempt.</li>
 * </ul>
 */
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_OPERATOR = "X-Operator-Id";
    public static final String HEADER_TRACE = "X-Trace-Id";

    /** Path prefixes that bypass tenant enforcement. */
    private static final String[] EXEMPT_PREFIXES = {
            "/actuator", "/v3/api-docs", "/swagger-ui", "/swagger-resources",
            "/h2-console", "/webjars"
    };

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        String tenantId = request.getHeader(HEADER_TENANT);
        String operatorId = request.getHeader(HEADER_OPERATOR);
        String traceId = request.getHeader(HEADER_TRACE);

        if (isBlank(tenantId) || isBlank(operatorId)) {
            log.warn("SEC-AC-03 attempt rejected: missing tenant or operator header on {}", path);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"errorCode\":\"MISSING_TENANT_HEADER\","
                            + "\"errorMessage\":\"X-Tenant-Id and X-Operator-Id are required\"}");
            return false;
        }

        TenantContextHolder.set(new TenantContext(tenantId, operatorId, traceId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContextHolder.clear();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
