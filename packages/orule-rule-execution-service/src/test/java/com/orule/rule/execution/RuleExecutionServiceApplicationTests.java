package com.orule.rule.execution;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RFC-0012 阶段 3 验证：Rule Execution Service 上下文必须能成功加载。
 *
 * <p>本模块由 {@code orule-runtime}（RFC-0040 v0.6 之前版本）改名而来，
 * 测试类名同步更新以匹配 {@link RuleExecutionServiceApplication}。
 */
@SpringBootTest
class RuleExecutionServiceApplicationTests {

    @Test
    void contextLoads() {
        // 触发整个 ApplicationContext 初始化
    }
}
