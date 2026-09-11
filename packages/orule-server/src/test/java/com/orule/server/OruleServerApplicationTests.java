package com.orule.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * RFC-0012 阶段 3 验证：Spring 上下文必须能成功加载。
 *
 * <p>此测试同时校验 Flyway（V1~V5 在 RFC-0014 引入）、JPA、Actuator 配置正确。
 * 数据库使用 H2 内存模式 + MySQL 方言兼容。
 */
@SpringBootTest
@ActiveProfiles("test")
class OruleServerApplicationTests {

    @Test
    void contextLoads() {
        // 触发整个 ApplicationContext 初始化
    }
}
