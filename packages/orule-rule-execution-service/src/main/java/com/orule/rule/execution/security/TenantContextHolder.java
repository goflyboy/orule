package com.orule.rule.execution.security;

/**
 * Thread-local carrier for {@link TenantContext} (RFC-0040 §3.5 / TASK-1.3.2).
 *
 * <p>The Spring MVC dispatcher uses one thread per request, so a plain
 * {@link ThreadLocal} is sufficient. Production deployments using reactive
 * I/O or coroutines MUST switch to a {@code reactor.util.context.Context}
 * carrier; that work belongs to a separate RFC.
 *
 * <p><strong>Always</strong> pair {@link #set(TenantContext)} with {@link #clear()}
 * inside a {@code try/finally} to avoid leaking tenant identity between threads
 * (see TASK-1.3.2 tests).
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {}

    /** Set the current request's tenant context. Replaces any prior value. */
    public static void set(TenantContext ctx) {
        CURRENT.set(ctx);
    }

    /**
     * Returns the current tenant context or {@code null} if none has been set
     * (e.g. background task, smoke test).
     */
    public static TenantContext current() {
        return CURRENT.get();
    }

    /** Required to prevent thread-pool leak after request completion. */
    public static void clear() {
        CURRENT.remove();
    }
}
