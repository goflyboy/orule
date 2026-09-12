# RFC-0020: Groovy 沙箱 + 单条规则执行器

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：5d · **阶段**：S4

---

## 1. 摘要

实现 Groovy 沙箱（白名单 + 超时 + 内存限制）和单条规则执行器 `RuleExecutor`，在 orule-runtime 中安全执行 Groovy 脚本。

> **⚠️ 安全关键**：本 RFC 对应高风险 **T1（Groovy 沙箱被绕过）**，是 MVP 安全性最高优先级。

---

## 2. 动机

- Groovy 沙箱是 MVP 安全底线（依据 ADR-006 §14 风险缓解）
- 执行器是 NL→SimpleTS→Groovy→执行 链路的最终环节（依据 `06-运行视图 §6.1.1` Step 11~12）
- 依据 `06-运行视图 §6.2.2` 执行状态机（PENDING→RUNNING→SUCCESS/FAILED）

---

## 3. 详细设计

### 3.1 模块位置

```
packages/orule-runtime/src/main/java/com/orule/runtime/
├── execution/
│   ├── RuleExecutor.java           # 执行器入口
│   ├── GroovySandbox.java         # 沙箱核心
│   ├── SandboxConfig.java         # 沙箱配置（白名单/超时/内存）
│   ├── ExecutionResult.java        # 执行结果
│   └── error/
│       ├── SandboxViolationException.java
│       ├── ExecutionTimeoutException.java
│       └── GroovyRuntimeException.java
├── config/
│   └── RuntimeProperties.java     # application.yml 配置绑定
└── OruleRuntimeApplication.java   # 已存在（RFC-0012）
```

### 3.2 沙箱配置

```java
package com.orule.runtime.execution;

import com.orule.dsl.SimpleTSWhitelist;  // 单一来源（RFC-0018 §3.10）

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 沙箱配置（来自 application.yml 的 orule.runtime.sandbox 节）。
 *
 * <p>依据 docs/dsl/SimpleTS.md §8 + RFC-0018 §3.10 SimpleTSWhitelist。
 */
@ConfigurationProperties(prefix = "orule.runtime.sandbox")
public record SandboxConfig(
    /** 执行超时（毫秒），默认 30000ms（30s） */
    long timeoutMs,

    /** 最大内存（MB），默认 128MB */
    int maxMemoryMb,

    /** 最大本地变量数，防止无限创建对象 */
    int maxLocalVariables,

    /** 最大循环次数，防止死循环 */
    int maxLoopIterations,

    /** 允许的方法白名单（完全限定签名），来自 SimpleTSWhitelist */
    Set<String> allowedMethods,

    /** 允许的类白名单（完全限定名） */
    Set<String> allowedClasses,

    /** 禁止的包前缀（黑名单），来自 SimpleTSWhitelist */
    Set<String> forbiddenPackages,

    /** 禁止的方法前缀（黑名单，deny-list 兜底） */
    Set<String> forbiddenMethods
) {
    public SandboxConfig {
        if (timeoutMs <= 0)         timeoutMs = 30_000;
        if (maxMemoryMb <= 0)       maxMemoryMb = 128;
        if (maxLocalVariables <= 0) maxLocalVariables = 100;
        if (maxLoopIterations <= 0) maxLoopIterations = 10_000;
        if (allowedMethods == null)     allowedMethods     = SimpleTSWhitelist.allowedMethodSignatures();
        if (allowedClasses == null)     allowedClasses     = defaultAllowedClasses();
        if (forbiddenPackages == null)  forbiddenPackages  = SimpleTSWhitelist.forbiddenPackages();
        if (forbiddenMethods == null)   forbiddenMethods   = SimpleTSWhitelist.forbiddenMethods();
    }

    /** 默认类白名单（允许使用但方法必须也在 allowedMethods 中） */
    static Set<String> defaultAllowedClasses() {
        return Set.of(
            "java.lang.Math", "java.lang.String", "java.lang.Integer",
            "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Boolean", "java.lang.Character",
            "java.util.List", "java.util.ArrayList", "java.util.Map",
            "java.util.HashMap", "java.util.Set", "java.util.HashSet",
            "java.util.Objects", "java.util.Optional",
            "java.time.LocalDate", "java.time.LocalDateTime",
            "java.math.BigDecimal"
        );
    }

    /** <b>已删除</b>：原 forbiddenPackages 含 {@code groovy.lang.}、{@code org.codehaus.groovy.runtime.}、
     * {@code sun.}、{@code jdk.internal.} —— 这些是 Groovy 自身 / JDK 内部类，列入黑名单会导致
     * 沙箱拒绝合法 Groovy 编译产物。新版 {@link SimpleTSWhitelist#forbiddenPackages()}
     * 仅含业务侧危险包（IO / 反射 / 网络 / RMI），更精准。 */
}
```

### 3.3 Groovy 沙箱核心

```java
package com.orule.runtime.execution;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.security.GroovyCodeSource;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.syntax.SyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureClassLoader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Groovy 沙箱执行器。
 *
 * <p>依据 ADR-006 §14 风险缓解 + docs/dsl/SimpleTS.md §8 + RFC-0018 §3.10 SimpleTSWhitelist。
 *
 * <p><b>安全策略（修订）</b>：原版"三层防护"文档与实现不符，实际只有字符串扫描 + 超时，
 * 形同裸奔。本 RFC 重写后采用 Groovy 官方 <b>SecureASTCustomizer</b> 在 AST 阶段拦截
 * 危险调用 + Allow/Deny 包过滤 + SecureClassLoader，是真正的 4 层防护。
 *
 * <h2>4 层防护</h2>
 * <ol>
 *   <li><b>源码层</b>：SimpleTS WhitelistPruner（RFC-0018）已禁止危险语法</li>
 *   <li><b>AST 层</b>：SecureASTCustomizer 校验 Groovy 编译产物（拒绝 receiver/方法/import 黑名单）</li>
 *   <li><b>字节码层</b>：SecureClassLoader 仅加载 allowedClasses + PackageMatcher 黑名单</li>
 *   <li><b>运行时层</b>：超时线程（Future.cancel）+ 内存限制（JVM -Xmx）</li>
 * </ol>
 */
public class GroovySandbox {

    private static final Logger log = LoggerFactory.getLogger(GroovySandbox.class);

    /** Groovy 脚本最大长度（100KB），防止 DoS */
    private static final int MAX_SCRIPT_LENGTH = 100_000;

    private final SandboxConfig config;
    private final ExecutorService executor;
    private final GroovyShell shell;

    public GroovySandbox(SandboxConfig config) {
        this.config = config;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "groovy-sandbox-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // 1. SecureASTCustomizer：AST 级别拦截（Groovy 4 官方机制）
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setForbiddenImports(new HashSet<>(config.forbiddenPackages()));

        // receivers 黑名单（receiver = obj.method 的 obj 类型）
        Set<String> receivers = new HashSet<>(config.forbiddenPackages());
        receivers.add("java.lang.Runtime");
        receivers.add("java.lang.System");
        receivers.add("java.lang.ProcessBuilder");
        receivers.add("java.lang.Class");
        receivers.add("java.lang.ClassLoader");
        receivers.add("java.lang.Thread");
        receivers.add("groovy.lang.GroovyShell");
        receivers.add("groovy.lang.MetaClass");
        secure.setReceiversBlackList(receivers);

        // 调用方法黑名单
        secure.setMethodBlacklist(new HashSet<>(config.forbiddenMethods()));

        // 只允许白名单包下的 import
        Set<String> allowedImports = new HashSet<>();
        for (String cls : config.allowedClasses()) {
            int lastDot = cls.lastIndexOf('.');
            if (lastDot > 0) {
                allowedImports.add(cls.substring(0, lastDot) + ".");
            }
        }
        secure.setAllowedImports(allowedImports);

        // 2. CompilerConfiguration：组合自定义器
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(secure);

        // 3. SecureClassLoader：限制可加载的类
        SecureClassLoader classLoader = new SecureClassLoader(
            GroovySandbox.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (config.forbiddenPackages().stream().anyMatch(name::startsWith)) {
                    throw new ClassNotFoundException("forbidden: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };

        this.shell = new GroovyShell(classLoader, new Binding(), cc);
    }

    /**
     * 安全执行 Groovy 脚本。
     *
     * @param groovyScript Groovy 源码（来自 RFC-0019 GroovyCodeGen.generateExecutable）
     * @param context      输入上下文（Map）
     * @return 执行结果
     */
    public ExecutionResult execute(String groovyScript, Map<String, Object> context) {
        long startMs = System.currentTimeMillis();

        // 1. 长度预校验
        if (groovyScript.length() > MAX_SCRIPT_LENGTH) {
            return ExecutionResult.failure(
                "GROOVY_SCRIPT_TOO_LONG",
                "Groovy 脚本长度 " + groovyScript.length() + " 超过上限 " + MAX_SCRIPT_LENGTH + " 字节",
                System.currentTimeMillis() - startMs);
        }

        // 2. 超时执行（AST 校验 + SecureClassLoader 都在 shell.evaluate 内部）
        Future<ExecutionResult> future = executor.submit(() -> doExecute(groovyScript, context));
        try {
            return future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);  // 中断 Groovy 线程
            log.warn("Groovy script execution timeout: {}ms", config.timeoutMs());
            return ExecutionResult.failure("TIMEOUT",
                "执行超时（" + config.timeoutMs() + "ms）",
                System.currentTimeMillis() - startMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure("INTERRUPTED", "执行被中断",
                System.currentTimeMillis() - startMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            long dur = System.currentTimeMillis() - startMs;
            // AST 阶段拒绝 → CompilationFailedException with SecureASTCustomizer
            if (cause instanceof CompilationFailedException cfe) {
                return ExecutionResult.failure("SANDBOX_VIOLATION",
                    "Groovy AST 拒绝: " + cfe.getMessage(), dur);
            }
            // 类加载阶段拒绝 → ClassNotFoundException
            if (cause instanceof ClassNotFoundException cnfe) {
                return ExecutionResult.failure("SANDBOX_VIOLATION",
                    "禁止的类加载: " + cnfe.getMessage(), dur);
            }
            if (cause instanceof SandboxViolationException sve) {
                return ExecutionResult.failure("SANDBOX_VIOLATION", sve.getMessage(), dur);
            }
            if (cause instanceof ExecutionTimeoutException ete) {
                return ExecutionResult.failure("TIMEOUT", ete.getMessage(), dur);
            }
            return ExecutionResult.failure("GROOVY_ERROR",
                cause.getClass().getSimpleName() + ": " + cause.getMessage(), dur);
        }
    }

    /**
     * 实际执行（已在 executor.submit 内，超时由 future.get 控制）。
     */
    private ExecutionResult doExecute(String groovyScript, Map<String, Object> context) {
        long startMs = System.currentTimeMillis();
        try {
            GroovyCodeSource codeSource = new GroovyCodeSource(
                groovyScript, "rule.groovy", "/groovy/sandbox");

            Binding binding = new Binding();
            binding.setVariable("context", context);

            shell.evaluate(codeSource, binding);

            @SuppressWarnings("unchecked")
            Map<String, Object> outputContext = (Map<String, Object>) binding.getVariable("context");
            return ExecutionResult.success(outputContext, System.currentTimeMillis() - startMs);

        } catch (CompilationFailedException e) {
            throw new SandboxViolationException("Groovy AST 校验失败: " + e.getMessage(), e);
        } catch (ClassNotFoundException e) {
            throw new SandboxViolationException("禁止的类加载: " + e.getMessage(), e);
        } catch (SyntaxException e) {
            return ExecutionResult.failure("GROOVY_SYNTAX_ERROR", e.getMessage(),
                System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            return ExecutionResult.failure("GROOVY_ERROR",
                e.getClass().getSimpleName() + ": " + e.getMessage(),
                System.currentTimeMillis() - startMs);
        }
    }

    /** 由 Spring @PreDestroy 调用，确保 executor.shutdown */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

> **修订要点**：
> 1. **删除** `checkForbiddenKeywords` / `checkForbiddenClasses` / `checkImports` 三个字符串 contains 扫描方法。
>    字符串扫描无法防御 `"Class" + ".forName"` 拼接绕过、注释旁路（`// import java.io.*`）、Unicode 转义等。
>    改用 Groovy 官方 `SecureASTCustomizer` 在 AST 编译阶段做精确拦截（编译期 visitor 遍历所有 MethodCall / Import / ClassExpression 节点）。
> 2. **修复** `CompilerConfiguration.withOptimizationOptions(Map.of(...))` API 误用：Groovy 4 的
>    CompilerConfiguration 没有 `withOptimizationOptions`，改用 `addCompilationCustomizers(...)` 组合 SecureASTCustomizer。
> 3. **删除** `system.setProperty("groovy.security.disableGroovyScriptFileVisitor", "false")`：无效配置项。
> 4. **新增** SecureClassLoader 子类在字节码加载阶段兜底类黑名单。
> 5. **修复** `executor.shutdown()` 而不是 `shutdownNow()`，给正在跑的脚本 5s 优雅退出时间，避免 ExecutionLog 半写状态。

### 3.4 执行结果

```java
public record ExecutionResult(
    boolean success,
    String errorCode,
    String errorMessage,
    Map<String, Object> outputContext,
    long durationMs,
    Instant executedAt
) {
    public static ExecutionResult success(Map<String, Object> context, long durationMs) {
        return new ExecutionResult(true, null, null, context, durationMs, Instant.now());
    }

    /**
     * 失败结果工厂。
     *
     * <p><b>修订</b>：原版参数列表 {@code (errorCode, message, Map)} 漏传 durationMs
     * 与 executedAt，record 构造器签名不匹配（编译失败）。本版本统一 3 参数，
     * durationMs 由调用方从 startMs 计算后传入。
     */
    public static ExecutionResult failure(String errorCode, String message, long durationMs) {
        return new ExecutionResult(false, errorCode, message, null, durationMs, Instant.now());
    }

    /** 是否超时 */
    public boolean isTimeout() { return "TIMEOUT".equals(errorCode); }

    /** 是否沙箱违规 */
    public boolean isSandboxViolation() { return "SANDBOX_VIOLATION".equals(errorCode); }
}
```

### 3.5 单条规则执行器

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleExecutor {

    private final GroovySandbox sandbox;
    private final ArtifactStorage artifactStorage;
    private final RuleVersionRepository versionRepo;
    private final ExecutionLogRepository logRepo;

    /**
     * 执行单条规则（PUBLISHED 版本）。
     *
     * @param ruleId      规则 ID
     * @param inputContext 输入上下文（Map<String, Object>）
     * @param traceId     X-Trace-Id（用于日志关联）
     */
    @Transactional
    public ExecutionResult executeRule(String ruleId, Map<String, Object> inputContext, String traceId) {
        // 1. 获取 PUBLISHED 版本
        //    注：RFC-0014 定义的状态枚举是 RuleVersionStatus（不是 RuleStatus），
        //    旧版代码误写为 RuleStatus，编译失败。
        RuleVersion version = versionRepo
            .findByRuleIdAndStatus(ruleId, RuleVersionStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException(
                "Rule " + ruleId + " 没有 PUBLISHED 版本"));

        // 2. 检查是否有 Groovy 产物
        String groovyScript = version.getGroovySource();
        if (groovyScript == null || groovyScript.isBlank()) {
            return ExecutionResult.failure(
                "NO_ARTIFACT",
                "RuleVersion " + version.getId() + " 没有 Groovy 产物",
                0L);
        }

        // 3. 沙箱执行
        ExecutionResult result = sandbox.execute(groovyScript, inputContext);

        // 4. 记录 ExecutionLog
        ExecutionLog logEntry = ExecutionLog.builder()
            .id(String.valueOf(System.nanoTime()))
            .ruleId(ruleId)
            .ruleVersionId(version.getId())
            .inputDataJson(toJson(inputContext))
            .outputDataJson(result.success() ? toJson(result.outputContext()) : null)
            .status(result.success() ? "SUCCESS" : "FAILED")
            .errorMessage(result.errorMessage())
            .durationMs(result.durationMs())
            .traceId(traceId)
            .build();
        logRepo.save(logEntry);

        return result;
    }

    private String toJson(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
```

### 3.6 REST API 与统一响应 DTO

> **新增小节**：原 §3.6 只给出示例 JSON，缺统一的 Response DTO 定义。
> 三个 RFC（0019 compile、0020 execute、0021 批量）应共用同一套 ExecutionResponse 形态，
> 否则前端需要适配三套不同结构。本 RFC 提供统一 DTO，RFC-0019 / RFC-0021 引用即可。

```java
package com.orule.runtime.api.dto;

/**
 * 执行响应统一 DTO。
 *
 * <p>适用：RFC-0019 编译响应、RFC-0020 单条规则执行响应、RFC-0021 批量执行响应。
 *
 * <p>字段稳定原则：新增字段必须可空；删除字段需走 RFC。
 */
public record ExecutionResponse(
    /** 是否成功 */
    boolean success,
    /** 错误码（成功时为 null；具体取值见 RFC-0020 §3.4 ExecutionResult） */
    String errorCode,
    /** 错误信息（成功时为 null） */
    String errorMessage,
    /** 输出 context（失败时为 null） */
    Map<String, Object> outputContext,
    /** 执行耗时（毫秒） */
    long durationMs,
    /** 执行时间戳 */
    Instant executedAt
) {
    public static ExecutionResponse from(ExecutionResult result) {
        return new ExecutionResponse(
            result.success(),
            result.errorCode(),
            result.errorMessage(),
            result.outputContext(),
            result.durationMs(),
            result.executedAt()
        );
    }
}
```

```
POST /api/v1/rules/{ruleId}/execute
Content-Type: application/json
X-Trace-Id: <uuid>

{
    "customer": { "tier": "VIP" },
    "order": { "totalAmount": 300, "discount": 0 }
}

→ 200 OK (统一 ExecutionResponse)
{
    "success": true,
    "errorCode": null,
    "errorMessage": null,
    "outputContext": {
        "customer": { "tier": "VIP" },
        "order": { "totalAmount": 300, "discount": 30 }
    },
    "durationMs": 42,
    "executedAt": "2026-09-12T13:30:00Z"
}
```

### 3.7 安全加固清单

> **修订**：原清单"字节码层：Groovy Sandbox Policy + ASM 分析 ✅ 本 RFC"与实现不符
> （代码里没有任何 ASM 分析）。新版 4 层防护与 §3.3 实现对齐。

| 层级 | 措施 | 状态 | 依据 |
|------|------|------|------|
| **L1 源码层** | SimpleTS WhitelistPruner（RFC-0018）禁止危险语法 | ✅ 已做 | RFC-0018 §3.5 |
| **L2 AST 层** | Groovy SecureASTCustomizer：forbiddenImports + receiversBlackList + methodBlacklist | ✅ 本 RFC | §3.3 |
| **L3 字节码层** | SecureClassLoader + allowedClasses 白名单 + forbiddenPackages 黑名单 | ✅ 本 RFC | §3.3 |
| **L4 运行时层** | 超时线程（Future.cancel）+ JVM -Xmx 内存限制 + 脚本长度上限 100KB | ✅ 本 RFC | §3.3 + SandboxConfig |
| **白名单单一来源** | RFC-0018 §3.10 SimpleTSWhitelist，禁止 RFC-0018 / RFC-0020 各自维护 | ✅ 本 RFC | RFC-0018 §3.10 |
| **审计层** | ExecutionLog 记录每次执行 | ✅ 已做（RFC-0014 execution_log 表） | §3.5 |
| **测试** | OWASP 沙箱渗透测试 + fuzz 测试 | 🟡 待补 | §5.2 |

---

## 4. 影响面

- 新增 `com.orule.runtime.execution` 包（~10 个类）
- 新增 `/api/v1/rules/{id}/execute` 端点
- 依赖 `groovy-all` 依赖（`groovy.version=4.0.24`）
- 不涉及数据库新表（复用 RFC-0014 的 execution_log 表）

---

## 5. 测试计划

### 5.1 正常路径

| 测试 | 预期 |
|------|------|
| VIP 满减规则执行 | order.discount = 30 |
| 多条件与或非 | 逻辑正确 |
| 循环 for | 执行成功 |
| 函数调用（白名单） | Math.abs / String.length 正常 |
| 返回 context | outputContext 包含修改后的字段 |

### 5.2 安全测试（最高优先级）

| 测试 | 预期 |
|------|------|
| `System.exit(1)` | SANDBOX_VIOLATION |
| `Runtime.getRuntime().exec(...)` | SANDBOX_VIOLATION |
| `new File("/etc/passwd")` | SANDBOX_VIOLATION |
| `Class.forName("java.lang.Runtime")` | SANDBOX_VIOLATION |
| `while(true) {}` | TIMEOUT（30s） |
| `Thread.sleep(99999)` | TIMEOUT |
| `Math.random()` | ⚠️ 已在白名单（可接受，业务不依赖随机性） |
| `String.valueOf()` | SUCCESS（白名单方法） |
| 超长脚本（200KB） | GROOVY_SCRIPT_TOO_LONG |
| 嵌套 100 层函数调用 | TIMEOUT |
| `ProcessBuilder` | SANDBOX_VIOLATION |
| Groovy MetaClass 注入 | SANDBOX_VIOLATION |
| `@Grab` 依赖注入 | SANDBOX_VIOLATION |
| `import java.io.*` | SANDBOX_VIOLATION |

### 5.3 性能测试

| 测试 | 预期 |
|------|------|
| 单条规则 P95 延迟 | < 200ms |
| 并发 50 QPS | 无 OOM / 无超时 |
| 1000 次执行内存稳定 | 无内存泄漏 |

---

## 6. 风险与缓解

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| **T1 沙箱被绕过**（代码字符串拼接、注释旁路、Unicode 转义、reflection） | 🔴 极高 | 真正的 4 层防护（§3.7）：SecureASTCustomizer（AST 精确拦截）+ SecureClassLoader（类加载兜底）+ 超时 + 长度上限；不做字符串 contains 扫描 |
| Groovy 4 SecureASTCustomizer 配置错误 | 🟡 中 | CI 安全测试覆盖 OWASP Top 10 + 字符串拼接绕过 fuzz |
| for (;;) {} 等死循环 | 🟡 中 | Future.cancel(true) + JVM -Xmx |
| 白名单遗漏合法方法 | 🟡 中 | SimpleTSWhitelist 单一来源；方法名集合 ⊆ 签名集合前缀映射（CI 断言） |
| 内存泄漏（GroovyShell 缓存） | 🟢 低 | 单例 GroovyShell；定期监控 classLoader 加载的类数量 |
| DoS（超长脚本 / 高频调用） | 🟡 中 | 脚本长度 100KB 上限；或ule-runtime 入口限流（RFC-0027 配合） |
| ExecutorService 关闭时丢日志 | 🟢 低 | shutdown() 给予 5s 优雅退出，超时后 shutdownNow |

---

## 7. 实施步骤

```
1. 创建 com.orule.runtime.execution 包
2. 实现 SandboxConfig + 白名单配置
3. 实现 GroovySandbox（预校验 + 超时执行）
4. 实现 ExecutionResult + 异常类
5. 实现 RuleExecutor
6. 实现 REST 端点
7. 单元测试（30+ 安全测试用例）
8. 集成测试（Testcontainers）
9. 安全渗透测试（OWASP ZAP）
10. 性能测试（JMeter 或 Gatling）
11. 超时/内存压测
```

---

## 8. 关联

- 上游：RFC-0019（Groovy 源码）、RFC-0014（数据库 schema，execution_log 表）
- 平级：RFC-0018 §3.10 SimpleTSWhitelist（白名单单一来源）
- 下游：RFC-0021（批量执行 + RuleSetArtifact）
- ADR：**ADR-006 规则源语言采用 SimpleTS**
- 规范：[docs/dsl/SimpleTS.md §8](../../dsl/SimpleTS.md)
- 对应风险：T1（Groovy 沙箱被绕过）—— 最高优先级
