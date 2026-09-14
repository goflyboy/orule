# Sprint 2 PR2 — RFC-0040 P0 全集交付所有权卡

> 涵盖 RFC-0040 §C.4 P0 表中的 8 个 TASK（上一回合 1.2.1~1.2.5 + 本回合 1.3.x / 2.1.x / 2.3.1 / 3.2.2）。
> 总计 71 个测试全绿，BUILD SUCCESS。

---

## 模块所有权

```text
顶层:        orule-rule-execution-service（本回合所有改动均落在该模块）
cruleengine:  无
crulemgr:     无
跨模块边界:
  - 通过 Feign 调用 rule-management-service（无强依赖）
  - 通过 Kafka 发送 rule-set-execution-completed 事件（无强依赖）
  - 通过 ExecutorRegistry SPI 加载 JavaSourceExecutor（同模块）
```

## 预计修改文件

```text
顶层: 无

orule-rule-execution-service 新增 (本回合, 共 ~14 文件):
  src/main/java/.../security/
    TenantContext.java                       ← NEW (TASK-1.3.2)
    TenantContextHolder.java                 ← NEW (TASK-1.3.2)
    TenantInterceptor.java                   ← NEW (TASK-1.3.2, §18 SEC-AC-03)
    TenantMvcConfig.java                     ← NEW (TASK-1.3.2)
  src/main/java/.../client/
    RuleMetadataResponse.java                ← NEW (TASK-1.3.3)
    RuleManagermentApiClient.java            ← NEW (TASK-1.3.3, @FeignClient)
    FeignTenantInterceptorConfig.java        ← NEW (TASK-1.3.3)
  src/main/java/.../api/
    RuleExecutionController.java             ← NEW (TASK-1.3.1)
  src/main/java/.../service/
    RuleExecutionApplicationService.java     ← NEW (TASK-1.3.1)
  src/main/java/.../events/
    RuleSetExecutionCompletedEvent.java      ← NEW (TASK-2.3.1)
    KafkaEventPublisher.java                 ← NEW (TASK-2.3.1)
  src/main/java/.../execution/async/
    AsyncThreadPoolConfig.java               ← NEW (TASK-2.1.1)
    RuleSetExecutorService.java              ← NEW (TASK-2.1.1 interface)
    RuleSetExecutorServiceImpl.java          ← NEW (TASK-2.1.2 impl)

  src/main/resources/application.yml         ← MOD (TASK-1.3.3 + 2.3.1 + 3.2.2)
  src/main/java/.../RuleExecutionServiceApplication.java ← MOD (@EnableFeignClients)

测试新增 (本回合, 共 9 个测试类):
  src/test/java/.../security/TenantInterceptorTest.java                ← NEW
  src/test/java/.../client/FeignTenantInterceptorConfigTest.java       ← NEW
  src/test/java/.../api/RuleExecutionControllerTest.java                ← NEW
  src/test/java/.../events/KafkaEventPublisherTest.java                 ← NEW
  src/test/java/.../service/RateLimiterIntegrationTest.java             ← NEW
  src/test/java/.../service/Resilience4jConfigBindingTest.java          ← NEW
  src/test/java/.../execution/async/RuleSetExecutorServiceTest.java     ← NEW
```

## 优先复用

```text
现有入口点:
  - RuleExecutionServiceApplication: 加 @EnableFeignClients(basePackages = "...client")
  - ExecutionLog / ExecutionLogRepository / ExecutionLogStatus: RuleSetExecutorServiceImpl 复用 markSuccess/markFailed/markPartialSuccess
  - GlobalExceptionHandler: 已覆盖 EVAL_FAILED/RUNTIME_ERROR/RULE_NOT_FOUND/RATE_LIMIT_EXCEEDED 等
  - ExecutionMetadata / ExecutionInput / ExecutionOutput DTOs: 不需新增

现有 helper:
  - TenantContextHolder: 单例 ThreadLocal, 避免在 @Async worker thread 上丢失
  - ExecutorRegistry.registerForTesting(...): 单元测试替换 SPI 路由

现有测试基类:
  - 无专用测试基类；本 PR 新增测试自包含 MockMvc + @MockBean

现有 DSL / 注解:
  - @RateLimiter(name = "ruleExecute"): 通过 resilience4j-spring-boot3 AOP 自动织入
  - @EnableFeignClients(basePackages = "..."): 只扫描 client 包, 避免污染

不新增的依赖 (pom.xml 已就绪):
  - spring-cloud-starter-openfeign (2023.0.3)
  - springdoc-openapi-starter-webmvc-ui (2.6.0)
  - resilience4j-spring-boot3 (2.2.0)
  - spring-kafka (BOM 管理)
  - feign-core (13.3, 自动传递)

可由上下文推导的字段:
  - JavaTimeModule: 通过 Class.forName 反射注册, 不硬编码 import, 单元测试用 bare ObjectMapper 不需要预先注册
  - TenantContext 字段: 严格按 §3.5 (tenantId/operatorId/traceId)
  - Kafka key: taskId (保证 partition stability across retries)
```

## 来源标注与置信度

```text
[KNOWN] 训练数据确定事实:
  - Spring Boot 3.x YAML 多文档处理：profile-specific doc 不会覆盖 default doc
    ← 调试 Resilience4jConfigBindingTest 失败时通过 dump property sources 验证
  - resilience4j-spring-boot3 实例继承 base-config 不绑定到 flat key
    ← 直接 env.getProperty("resilience4j.ratelimiter.instances.ruleExecute.limit-for-period") 返回 null 验证
  - Jackson 2.17 findModules() 不会自动通过 ServiceLoader 加载
    ← KafkaEventPublisherTest happyPath 序列化 Instant 失败 → ClassNotFoundException 路径
  - Groovy 3 类反射: cached.getClass().getDeclaredConstructor() 必须 public
  - MockMvc + @MockBean Feign + SPI 替换 fake 是验证 Controller 端到端的可靠模式
  - @Async 跨线程: TenantContextHolder 必须在线程切换前捕获, worker 内重新 set
  - JavaTimeModule 注册必须 idempotent: registerModule 重复注册会抛 IllegalArgumentException

[COMPUTED] 本次推导:
  - Spring Boot 配置文件 "default profile" 文档必须放在所有 on-profile 文档之前或用 `---` 显式分割
  - KafkaTemplate<String,String> 需 StringSerializer + Jackson 序列化 payload 二选一; 选择 StringSerializer + Jackson 手动
    序列化便于测试时 verify payload 内容 (而不是 raw byte[])
  - @FeignClient 必须配 @EnableFeignClients, 否则 FeignContext 不创建 → RuleManagermentApiClient 无法注入

[INFERRED] 逻辑推断:
  - TASK-1.3.5 Controller 路由 (POST /api/v1/rule-executions + POST /api/v1/rule-set-executions) 应在 Sprint 3 提交
  - RuleSet 真实 artifact 查找 (RFC-0021) 在 TASK-2.2.1; 当前 placeholder 用 ruleSetCode 视为单规则
  - 跨线程 TenantContextHolder 重新设置存在 race; 真实生产应使用 request-scoped bean + Spring's RequestContextHolder

[GUESS] 无依据猜测 (置信度 LOW):
  - 100 req/s 的限流配置在 prod 流量下合理; 真实阈值需 TASK-3.2.2 perf test 验证
  - Kafka idempotent producer 配置 (`enable.idempotence: true`) 足以保证 at-least-once + 去重;
    跨 partition 事务需要 transactional id 配置 (TASK-2.3.2)

[RULES I BROKE]:
  1. 起初假设 spring.kafka 可以写成 `spring.kafka:` 扁平键 — 实际 YAML 顶层 key 不能用 `.` 分隔
     → 修正: 嵌套在 `spring:` 顶层 key 下
  2. 起初把 resilience4j 配置放在顶层 spring 块之后, 但被误判为 prod profile 文档 (因 `---` 之前的内容)
     → 修正: 重构 yml 为显式 default profile 文档 (用 `---` 分割) + dev/prod profile 文档
  3. 起初用 `findModules().stream().noneMatch(...)` 检查 JavaTimeModule 是否注册 — 实际 `findModules()`
     不会自动 ServiceLoader 发现; 该谓词对 bare `new ObjectMapper()` 永远返回 true, 但后续 registerModule 失败
     → 修正: 用 `getRegisteredModuleIds()` 检查
  4. 起初用 multi-catch `ReflectiveOperationException | ClassNotFoundException`
     — 实际 ClassNotFoundException extends ReflectiveOperationException (Java 7+)
     → 修正: 单 catch
  5. 起初 RuleSetExecutorServiceImpl 用 new ExecutionLog() + setX() 直接赋值
     — 实际 ExecutionLog 已有 pending() 工厂方法和 markSuccess/markFailed/markPartialSuccess 状态机
     → 修正: 复用既有状态机, 不绕过
  6. 起初 RuleSetExecutorServiceImpl.doRun 缺少 tenant 参数 — 编译失败
     → 修正: 显式传递 TenantContext
  7. 起初 RuleSetExecutorServiceImpl 的 `setX()` 调用序列与 ExecutionLog 的 markX() 状态机冲突
     — pending() 设置 status=PENDING 但 createdAt=now, 然后 markSuccess() 会检查 startedAt==null 才计算 durationMs
     → 修正: 使用 pending() + markX() 组合, 让 status machine 自行驱动
```

## 反谄媚预警与替代方案

```text
替代方案 1 (未采纳): 直接用 Spring Security 替代自实现 TenantInterceptor
  - Pros: 标准化 JWT 验证、CSRF 保护、统一安全策略
  - Cons: 引入 spring-boot-starter-security 体积 +12 MB; JWT 验证需要 JWK 端点或对称密钥
          (RFC §16.1 假设 API gateway 已完成 JWT 验证, 此模块只透传)
  - 决策: 保留自实现, 但加 TODO 标记 TASK-3.4.1 升级到 Spring Security ResourceServer

替代方案 2 (未采纳): 让 RuleSetExecutorServiceImpl 不直接持有 Feign client, 而是先发 Kafka 事件给 rule-management-service 解析
  - Pros: 解耦, 异步
  - Cons: 引入新的循环依赖 (rule-mgmt 需要消费 rule-execution 事件); latency 增加 10x
  - 决策: 保留同步 Feign 调用; TASK-3.5.x 评估异步元数据获取

替代方案 3 (未采纳): Kafka producer 用 Avro + Schema Registry
  - Pros: 强类型契约; schema 演进自动兼容
  - Cons: 需要 Confluent Schema Registry 部署; 当前仅 1 个 event type
  - 决策: 保持 JSON + Jackson; TASK-3.6.1 评估升级到 Avro

风险点 (须在 RFC §5 / §C.4 显式保留):
  - Resilience4j @RateLimiter AOP 不会拦截 self-invocation (同一 bean 内部调用绕过 proxy)
    → 影响: RuleExecutionApplicationService.execute() 调用自身方法不会限流
    → 缓解: 限流放在 Controller 层 (@RateLimiter 移到 Controller) — TASK-3.2.2 调整
  - TenantContextHolder 跨 @Async 线程: 当前手动 capture+restore, 但如果 worker pool 复用
    同一线程处理不同请求, 顺序错误会导致 A 请求的 tenantId 被 B 请求看到
    → 缓解: 改用 TransmittableThreadLocal (Alibaba) 或重新设计为 Spring Scope
  - RuleSetExecutorServiceImpl 当前是 "单规则集合" 占位; 真实 RuleSet 需要 RuleSetArtifact 仓储
    → 阻塞: TASK-2.2.1 (依赖 RFC-0021) — Sprint 3 必须先解 RFC-0021

测试覆盖:
  - 71 测试 / 0 失败 / 0 错误
  - 包括: Application 启动 + H2 + Flyway + JPA + Springdoc + MockMvc + Feign + Kafka + RateLimiter + 异步 worker
```

## 后续 PR 建议

- **Sprint 3 PR1**: TASK-2.2.1 RuleSetArtifact 仓储 + RFC-0021 落地
- **Sprint 3 PR2**: TASK-2.1.3 fail-fast 标志 + TaskNotFoundException 流
- **Sprint 3 PR3**: TASK-3.2.1 OWASP Top 10 自动化测试套件 (28 用例, R-001 闭环)
- **Sprint 3 PR4**: TASK-3.4.1 升级到 Spring Security ResourceServer (JWT 签名验证)
- **Sprint 3 PR5**: TASK-3.1.x Micrometer 集成 (GroovyClassCache 命中率 / RateLimiter 拒绝率)
- **Sprint 4**: TASK-2.3.2 Kafka transactional id (跨 partition 事务)

---

[RULES I BROKE]: 显式记录于上方 RULES I BROKE 段; 不在响应主体遮蔽。
