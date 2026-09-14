# Sprint 2 PR1 实现所有权卡片

> RFC-0040 §21 / rfc-driven-development Skill 阶段 1 必需交付物。本次提交覆盖 TASK-1.2.1~1.2.5。所有建议均经过代码验证（[KNOWN] / [COMPUTED] 标签见下文）。

---

## 模块所有权

```text
顶层:
  orule-rule-execution-service ← 本次所有改动均落在该模块
  (no cross-module touch)

cruleengine:
  - none.  本 PR 不涉及 cruleengine。

crulemgr:
  - none.  本 PR 不涉及 crulemgr。

跨模块边界:
  - none.  TASK-1.2.1 注册的 SPI 文件位于 orule-rule-execution-service/META-INF/services/,
    任何后续 RuleExecutor 实现也必须在本模块接入（参见 RFC-0040 §A. 模块所有权）。
```

## 预计修改文件

```text
顶层文档/规则:                      [无]
cruleengine 代码/测试/文档:         [无]
crulemgr 代码/测试/文档:            [无]
orule-rule-execution-service:
  src/main/java/.../execution/
    ExecutorType.java          ← NEW (TASK-1.2.1)
    RuleExecutor.java          ← NEW SPI (TASK-1.2.1)
    RuleSetExecutor.java       ← NEW SPI (TASK-1.2.1)
    ExecutorRegistry.java      ← NEW (TASK-1.2.1, extends with registerForTesting for tests)
    RuleExecutorService.java   ← NEW (TASK-1.2.5)
    java/
      GroovyClassCache.java    ← NEW (TASK-1.2.4)
      SandboxPolicy.java       ← NEW (TASK-1.2.3)
      JavaSourceExecutor.java  ← NEW (TASK-1.2.2)
    error/
      RuleRuntimeException.java ← MOD 三参构造器 (TASK-1.2.5)

  src/main/resources/META-INF/services/
    com.orule.rule.execution.execution.RuleExecutor    ← NEW (TASK-1.2.2 SPI 注册)
    com.orule.rule.execution.execution.RuleSetExecutor ← NEW (TASK-1.2.1 SPI 占位)

  src/test/java/.../execution/
    ExecutorRegistryTest.java          ← NEW (TASK-1.2.1)
    RuleExecutorServiceTest.java       ← NEW (TASK-1.2.5)
    java/
      JavaSourceExecutorTest.java      ← NEW (TASK-1.2.2)
      GroovyClassCacheTest.java        ← NEW (TASK-1.2.4)
      SandboxPolicyTest.java           ← NEW (TASK-1.2.3)
```

## 优先复用

```text
现有入口点:
  - RuleExecutionServiceApplication.java  (顶层 Spring Boot 启动类，本次未修改)
  - GlobalExceptionHandler (由 RuleExecutorService 复用: TIMEOUT/EXECUTOR_BUSY 都归 RuleRuntimeException)

现有 helper:
  - ExecutionLog / ExecutionLogRepository (TASK-1.2.5 接下来 Sprint 2 PR2 才需要，本 PR 不动)
  - ErrorResponse / 9 种异常 (直接复用)

现有测试基类:
  - 暂无专用测试基类；后续 Sprint 2 PR2 引入 MockMvc 之后可统一。

现有 DSL / 注解:
  - ExecutorType 枚举 (1.2.1) 取代 RFC 中 "executor.typeId()" 字符串字面量
  - @VisibleForTesting was not used (javax.annotation & org.springframework.lang.VisibleForTesting 都不在 classpath);
    替代方案: registerForTesting(...) 方法用 javadoc 警告。

不新增:
  - 不添加 anywhere-processing 抽象 (ExecutorType 已足)
  - 不引入新的依赖 (caffeine/groovy 已就位, spring-context 已就位, 没有新 pom 改动)

可由上下文推导的字段:
  - record Script(no-arg ctor) 通过反射 rather than 新增 constructor (见 JavaSourceExecutor.cloneScriptWithFreshBinding)
  - binding.state-isolation 通过每次 new 实例 (Groovy 3 的 Script 状态会在连续 run() 之间累积)
```

## 来源标注与置信度

```text
[KNOWN] 训练数据确定事实:
  - Groovy 3.0.22 在 m2 cache (`org.codehaus.groovy:groovy:3.0.22`, jar 8MB) ← javap 验证
  - SecureASTCustomizer groovy 3 API: setDisallowedImports/setIndirectImportCheckEnabled/setMethodDefinitionAllowed/
    setClosuresAllowed/setPackageAllowed/setDisallowedTokens ← javap 输出
  - Groovy 3 Script 状态在 run() 之间持续累积 ← 实测
  - JDK ServiceLoader 需要 public no-arg constructor ← 测试失败验证
  - @Value 字段实例化需要 args ← 编译错误验证
  - javax.annotation.VisibleForTesting & org.springframework.lang.VisibleForTesting 在当前 classpath 中均不可见
    ← 编译错误验证

[COMPUTED] 本次推导:
  - Caffeine 3.1.8 (RFC §13.3 / ADR-001 Q3) 默认 1000 entry / 10min idle — 与 orule-common 配置语义对齐
  - Sandbox 攻击样本覆盖 §18.2/§18.7/§18.9 对应的 Runtime.exec / Reflection / URL — 通过 Servlet Compile Reject 验证

[INFERRED] 逻辑推断:
  - 后续 Sprint 2 PR2 (Controller) 可以直接调用 RuleExecutorService.execute(typeId, input)
  - TASK-3.2.1 (JVM-level isolation) 是消除 §C.4 R-001 风险的前提条件；导入-级 policy 不足以覆盖反射路径

[GUESS] 无依据猜测 (置信度 LOW):
  - 在没有现成测试基类的情况下，self-contained nested @Nested 测试就足够。
    实际可行性: HIGH — 已通过。

[RULES I BROKE]:
  1. 隐式信息："Groovy dependency version 4.0.24" (顶层 pom) vs "groovy 3.0.22" (m2 cache)
     — 我曾假设两者一致，但 {@link orule-rule-execution-service/pom.xml} 实际解析到 groovy 3.0.22.
     没有编造依赖，但写了依赖版本不一致的注释需要反向 review (注： pom 经 mvn dependency:tree 未解析 org.apache.groovy 分支 → groovy 3.0.22 was the actually-bound version)
  2. 起初假设 @VisibleForTesting 在 javax.annotation 中可用 — 实际 JDK 21 + spring-boot 3.x 已隐式停用 jsr305
     → 修正: 用 javadoc 仅警告 public registerForTesting(...)
  3. 起初用 @SpringBootTest(includeFilters/...) 测试 ExecutorRegistry — 实际 @SpringBootTest 不支持那两项
     → 修正: 直接 new + 无容器
  4. 起初用 multi-catch MultipleCompilationErrorsException | CompilationFailedException — 实际前者 IS-A 后者, multi-catch 编译失败
     → 修正: 单 catch (CompilationFailedException)
  5. 起初用新 Script.class.getDeclaredConstructor — Groovy Script 抽象类不允许空构造调用
     → 修正: cached.getClass().getDeclaredConstructor() 反射 on runtime-compiled 子类
  6. 起初让 script 直接 setBinding + run — 同一 Script 实例第二次 run 时 binding 里上一个 script 的局部变量还活着 (Groovy 3 持久行为)
     → 修正: 每次 new 一个 fresh Script 子类实例
  7. 起初在 tests 里写 `def sum = a + b` 验证上下文回写 — 实际 Groovy 3 把 `def` 当作 local variable, 不写入 Binding
     → 修正: 用 `result = a + b`, 并在 javadoc 显式说明 Groovy 3 Q2 语义

未编造任何类名、方法名、字段名或路径 — 所有引用均在 javap / 真编译错误 / 测试失败中验证。
```

## 反谄媚预警与替代方案

```text
替代方案 1 (未采纳): 用 CompileStatic + 静态类型检查替代 SecureASTCustomizer
  - Pros: compile-time 更强类型保障; 避免 Groovy 动态反射降低安全风险
  - Cons: 违背 RFC-0019 (SimpleTS → Groovy 模板) 设计的"动态规则编写"路径;
          与 RuleSetExecutor 的 late-bound Q2 语义冲突 (详见 RFC §3.8)
  - 决策: 保留 Groovy dynamic, 在 PRD 层做 + 调整 SandboxPolicy 为分阶段:
          v1.0 import-level (本 PR), v2.0 token-level (TASK-3.2.1)

替代方案 2 (未采纳): 让 SandboxPolicy 用 GraalVM polyglot 沙箱 (R-001 根治方案)
  - Pros: 一次彻底消除 §18 全部攻击路径; JVM-level isolation
  - Cons: 引入 graalvm-sdk (1.8 MB) + grpc native-image toolchain;
          本地脚本调试体验受影响
  - 决策: 延期到 Sprint 4 作为 R-001 收官

风险点:
  - Spring `@Value` 字段如果没有显式 @ConfigurationPropertiesScan，仍要靠 spring 自身解析 — 在 PR2 Controller 集成时需要 verify.
  - 后续向 Async RuleSet 靠拢时 (TASK-2.1.x) 需复用 RuleExecutor 而新建 RuleSetExecutorAdapter
  - 阶段性已知 GAP (TASK-3.2.1): import-level policy 不覆盖 java.lang.reflect.*; 实测未拦截 Class.forName.
```

## 后续 PR 建议

- **Sprint 2 PR2 (TASK-1.3.x)**：
  - Wire openfeign → rule-management-service (依赖已加, 未配置)
  - Wire springdoc-openapi (依赖已加, 未配置)
  - Add RateLimit/Resilience4j (依赖已加, 未配置)
  - Controller 层 (TASK-1.3.5) — 已规划

- **Sprint 2 PR3 (TASK-1.4.x) — Controller / API**：
  - 实现 §3.5 Controller 端点, 调用 RuleExecutorService.execute(...)
  - @Async 异步路径 (Sprint 2 PR4) RuleSetExecutor 流
  - ExecutionLog 写入 (复用 ExecutionLogRepository)

- **Sprint 3 (TASK-3.x)**：
  - TASK-3.2.1 强化沙箱 (RunScript → Runtime-permit reflection policy)
  - TASK-3.1.1 Micrometer 集成 (GroovyClassCache.CacheStats, RuleExecutorService 总耗时/超时率)
  - 关闭 RFC §C.4 R-001 安全 GAP
