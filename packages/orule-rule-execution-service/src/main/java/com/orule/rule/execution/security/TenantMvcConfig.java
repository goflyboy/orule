package com.orule.rule.execution.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC wiring for {@link TenantInterceptor} (RFC-0040 §3.5 / TASK-1.3.2).
 *
 * <p>The interceptor is registered for {@code /api/**}; exempt paths
 * (actuator / swagger / h2-console) are filtered inside the interceptor
 * to keep the configuration declarative.
 */
@Configuration
public class TenantMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantInterceptor())
                .addPathPatterns("/api/**");
    }
}
