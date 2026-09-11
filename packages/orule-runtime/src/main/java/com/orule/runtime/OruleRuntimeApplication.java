package com.orule.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orule-runtime 执行服务入口。
 *
 * <p>负责规则实际执行、批量调用、RuleSet 调度等。
 */
@SpringBootApplication(scanBasePackages = "com.orule.runtime")
public class OruleRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OruleRuntimeApplication.class, args);
    }
}
