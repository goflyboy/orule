package com.orule.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RFC-0012 阶段 3 验证：Runtime 上下文必须能成功加载。
 */
@SpringBootTest
class OruleRuntimeApplicationTests {

    @Test
    void contextLoads() {
        // 触发整个 ApplicationContext 初始化
    }
}
