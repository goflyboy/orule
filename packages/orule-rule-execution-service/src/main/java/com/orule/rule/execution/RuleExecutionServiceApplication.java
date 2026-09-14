package com.orule.rule.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * orule-rule-execution-service entry point.
 *
 * <p>Hosts single-rule execution, batch RuleSet dispatch, Kafka completion
 * events, and exposes the RFC-0040 section-16 REST API.
 *
 * <p>This module was renamed from {@code packages/orule-runtime} (RFC-0040 v0.6
 * and earlier versions).
 *
 * @see <a href="https://github.com/goflyboy/orule/blob/main/docs/rfcs/RFC-0040-%E8%A7%84%E5%88%99%E6%89%A7%E8%A1%8C%E6%9C%8D%E5%8A%A1.md">RFC-0040 规则执行服务</a>
 */
@SpringBootApplication(scanBasePackages = "com.orule.rule.execution")
@EnableAsync
@EnableFeignClients(basePackages = "com.orule.rule.execution.client")
public class RuleExecutionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleExecutionServiceApplication.class, args);
    }
}
