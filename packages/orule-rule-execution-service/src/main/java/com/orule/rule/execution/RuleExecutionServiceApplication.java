package com.orule.rule.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * orule-rule-execution-service 执行服务入口。
 *
 * <p>负责规则实际执行、批量调用、RuleSet 调度、Kafka 完成事件投递等。
 * 对外暴露 RFC-0040 §16 定义的 REST API。
 *
 * <p>本模块由 {@code packages/orule-runtime}（RFC-0040 v0.6 之前版本）改名而来。
 *
 * @see <a href="https://github.com/goflyboy/orule/blob/main/docs/rfcs/RFC-0040-%E8%A7%84%E5%88%99%E6%89%A7%E8%A1%8C%E6%9C%8D%E5%8A%A1.md">RFC-0040 规则执行服务</a>
 */
@SpringBootApplication(scanBasePackages = "com.orule.rule.execution")
@EnableAsync
public class RuleExecutionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleExecutionServiceApplication.class, args);
    }
}
