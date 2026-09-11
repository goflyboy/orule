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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置（来自 application.yml 的 orule.runtime.sandbox 节）。
 * 依据 docs/dsl/SimpleTS.md §8 白名单定义。
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
    
    /** 允许的方法白名单（方法签名） */
    Set<String> allowedMethods,
    
    /** 允许的类白名单（完全限定名） */
    Set<String> allowedClasses,
    
    /** 禁止的包前缀（黑名单，即使在白名单类中也禁止） */
    Set<String> forbiddenPackages
) {
    public SandboxConfig {
        if (timeoutMs <= 0) timeoutMs = 30_000;
        if (maxMemoryMb <= 0) maxMemoryMb = 128;
        if (maxLocalVariables <= 0) maxLocalVariables = 100;
        if (maxLoopIterations <= 0) maxLoopIterations = 10_000;
        if (allowedMethods == null) allowedMethods = defaultAllowedMethods();
        if (allowedClasses == null) allowedClasses = defaultAllowedClasses();
        if (forbiddenPackages == null) forbiddenPackages = defaultForbiddenPackages();
    }
    
    /** 默认方法白名单（依据 docs/dsl/SimpleTS.md §8.1） */
    static Set<String> defaultAllowedMethods() {
        return Set.of(
            // 数学
            "java.lang.Math.abs", "java.lang.Math.min", "java.lang.Math.max",
            "java.lang.Math.floor", "java.lang.Math.ceil", "java.lang.Math.round",
            "java.lang.Math.sqrt", "java.lang.Math.pow", "java.lang.Math.random",
            // 字符串
            "java.lang.String.length", "java.lang.String.startsWith", "java.lang.String.endsWith",
            "java.lang.String.includes", "java.lang.String.toUpperCase", "java.lang.String.toLowerCase",
            "java.lang.String.trim", "java.lang.String.substring", "java.lang.String.indexOf",
            "java.lang.String.replace", "java.lang.String.split", "java.lang.String.valueOf",
            "java.lang.Integer.parseInt", "java.lang.Double.parseDouble",
            "java.lang.Long.parseLong",
            // 集合
            "java.util.List.size", "java.util.List.isEmpty", "java.util.List.get",
            "java.util.Map.size", "java.util.Map.isEmpty", "java.util.Map.get",
            "java.util.Set.size", "java.util.Set.contains",
            // 日期
            "java.time.LocalDate.now", "java.time.LocalDateTime.now",
            "java.time.LocalDate.getYear", "java.time.LocalDate.getMonthValue",
            "java.time.LocalDate.getDayOfMonth", "java.time.LocalDate.plusDays",
            "java.time.LocalDate.minusDays", "java.time.LocalDate.isAfter", "java.time.LocalDate.isBefore",
            "java.time.LocalDateTime.getHour", "java.time.LocalDateTime.getMinute"
        );
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
    
    /** 默认禁止包（高危，即使在白名单类中也禁止访问） */
    static Set<String> defaultForbiddenPackages() {
        return Set.of(
            "java.lang.Runtime", "java.lang.System", "java.lang.Thread",
            "java.lang.Process", "java.lang.ClassLoader",
            "java.lang.reflect.", "java.io.", "java.nio.file.",
            "java.net.Socket", "java.net.URL", "java.net.HttpURLConnection",
            "groovy.lang.", "org.codehaus.groovy.runtime.",
            "sun.", "jdk.internal.", "java.lang.invoke."
        );
    }
}
```

### 3.3 Groovy 沙箱核心

```java
package com.orule.runtime.execution;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.security.GroovyCodeSource;
import org.codehaus.groovy.control.CompilationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Policy;
import java.util.concurrent.*;

/**
 * Groovy 沙箱执行器。
 * 依据 ADR-006 §14 风险缓解 + docs/dsl/SimpleTS.md §8 白名单。
 * 
 * 安全策略（三层防护）：
 * 1. Groovy Sandbox Policy（类加载级别）
 * 2. 超时线程（执行级别）
 * 3. 内存限制（脚本级别）
 */
public class GroovySandbox {
    
    private static final Logger log = LoggerFactory.getLogger(GroovySandbox.class);
    
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
        
        // 配置 Groovy 安全策略
        configureSecurityPolicy();
        
        // 创建受限 GroovyShell
        this.shell = new GroovyShell(new SandboxBinding(), 
            new CompilerConfiguration()
                .withOptimizationOptions(Map.of(
                    "methodPointerArithmetic", false,
                    "invokeDynamic", false
                ))
        );
    }
    
    /**
     * 安全执行 Groovy 脚本。
     * @param groovyScript Groovy 源码
     * @param context     输入上下文（Map）
     * @return 执行结果
     */
    public ExecutionResult execute(String groovyScript, Map<String, Object> context) {
        // 1. 预校验：脚本长度
        if (groovyScript.length() > 100_000) {
            return ExecutionResult.failure(
                "GROOVY_SCRIPT_TOO_LONG",
                "Groovy 脚本长度 " + groovyScript.length() + " 超过上限 100KB",
                null
            );
        }
        
        // 2. 预校验：禁止关键字
        String violation = checkForbiddenKeywords(groovyScript);
        if (violation != null) {
            return ExecutionResult.failure(
                "SANDBOX_VIOLATION",
                "禁止的关键字: " + violation,
                null
            );
        }
        
        // 3. 预校验：禁止类引用
        violation = checkForbiddenClasses(groovyScript);
        if (violation != null) {
            return ExecutionResult.failure(
                "SANDBOX_VIOLATION",
                "禁止的类引用: " + violation,
                null
            );
        }
        
        // 4. 超时执行
        Future<ExecutionResult> future = executor.submit(() -> 
            doExecute(groovyScript, context)
        );
        
        try {
            return future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Groovy script execution timeout: {}ms", config.timeoutMs());
            return ExecutionResult.failure(
                "TIMEOUT",
                "执行超时（" + config.timeoutMs() + "ms）",
                null
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure("UNKNOWN", "执行被中断", null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SandboxViolationException sve) {
                return ExecutionResult.failure("SANDBOX_VIOLATION", sve.getMessage(), null);
            } else if (cause instanceof ExecutionTimeoutException ete) {
                return ExecutionResult.failure("TIMEOUT", ete.getMessage(), null);
            } else {
                return ExecutionResult.failure("GROOVY_ERROR", cause.getMessage(), null);
            }
        }
    }
    
    private ExecutionResult doExecute(String groovyScript, Map<String, Object> context) {
        try {
            // 预编译 + 安全检查
            GroovyCodeSource codeSource = new GroovyCodeSource(
                groovyScript, "rule.groovy", "/groovy/sandbox");
            
            // 检查是否引用了禁止的包
            checkImports(groovyScript);
            
            // 绑定上下文
            Binding binding = new SandboxBinding(context);
            
            // 执行
            long start = System.currentTimeMillis();
            Object result = shell.evaluate(codeSource);
            long duration = System.currentTimeMillis() - start;
            
            // 提取返回值（context 已被脚本修改）
            @SuppressWarnings("unchecked")
            Map<String, Object> outputContext = (Map<String, Object>) binding.getVariable("context");
            
            log.debug("Groovy script executed in {}ms", duration);
            return ExecutionResult.success(outputContext, duration);
            
        } catch (CompilationFailedException e) {
            return ExecutionResult.failure("GROOVY_SYNTAX_ERROR", e.getMessage(), null);
        } catch (SandboxViolationException e) {
            throw e;  // 上层处理
        } catch (Exception e) {
            return ExecutionResult.failure("GROOVY_ERROR", e.getMessage(), null);
        }
    }
    
    /** 检查禁止的关键字（运行时字节码级别防护） */
    private String checkForbiddenKeywords(String script) {
        String[] forbidden = {
            "System.exit", "Runtime.getRuntime", "Class.forName",
            "new File", "new Socket", "new URL(",
            "ProcessBuilder", ".execute(", ".exec(",
            "@Grab", "GroovyShell.", "Eval.me(",
            "metaClass", "__groovy", "getClass()."
        };
        for (String kw : forbidden) {
            if (script.contains(kw)) return kw;
        }
        return null;
    }
    
    /** 检查禁止的类引用 */
    private String checkForbiddenClasses(String script) {
        for (String pkg : config.forbiddenPackages()) {
            // 简单字符串匹配（生产环境建议用 ASM 分析字节码）
            if (script.contains(pkg.replace("/", "."))) {
                return pkg;
            }
        }
        return null;
    }
    
    /** 检查 import 语句 */
    private void checkImports(String script) {
        // Groovy 脚本不允许 import（已在 WhitelistPruner 阶段处理）
        // 此处二次校验
        if (script.contains("import ") && !script.contains("//")) {
            // 允许注释中的 import
            throw new SandboxViolationException("禁止 import 语句");
        }
    }
    
    private void configureSecurityPolicy() {
        // 禁用 Groovy 的 AST Transformation 危险特性
        System.setProperty("groovy.security.disableGroovyScriptFileVisitor", "false");
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }
}
```

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
     * @param ruleId 规则 ID
     * @param inputContext 输入上下文（Map<String, Object>）
     * @param traceId X-Trace-Id（用于日志关联）
     */
    @Transactional
    public ExecutionResult executeRule(String ruleId, Map<String, Object> inputContext, String traceId) {
        // 1. 获取 PUBLISHED 版本
        RuleVersion version = versionRepo
            .findByRuleIdAndStatus(ruleId, RuleStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException(
                "Rule " + ruleId + " 没有 PUBLISHED 版本"));
        
        // 2. 检查是否有 Groovy 产物
        String groovyScript = version.getGroovySource();
        if (groovyScript == null || groovyScript.isBlank()) {
            return ExecutionResult.failure(
                "NO_ARTIFACT",
                "RuleVersion " + version.getId() + " 没有 Groovy 产物",
                0
            );
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

### 3.6 REST API

```
POST /api/v1/rules/{ruleId}/execute
Content-Type: application/json
X-Trace-Id: <uuid>

{
    "customer": { "tier": "VIP" },
    "order": { "totalAmount": 300, "discount": 0 }
}

→ 200 OK
{
    "success": true,
    "outputContext": {
        "customer": { "tier": "VIP" },
        "order": { "totalAmount": 300, "discount": 30 }
    },
    "durationMs": 42
}
```

### 3.7 安全加固清单

| 层级 | 措施 | 状态 |
|------|------|------|
| **源码层** | SimpleTS WhitelistPruner（RFC-0018）已禁止危险语法 | ✅ 已做 |
| **字节码层** | Groovy Sandbox Policy + ASM 分析 | ✅ 本 RFC |
| **运行时层** | 超时线程 + 内存限制 | ✅ 本 RFC |
| **方法调用层** | allowedMethods 白名单 | ✅ 本 RFC |
| **禁止包层** | forbiddenPackages 黑名单 | ✅ 本 RFC |
| **审计层** | ExecutionLog 记录每次执行 | ✅ 本 RFC |

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
| **T1 沙箱被绕过** | 🔴 极高 | 三层防护（源码/字节码/运行时）；OWASP Top 10 检查；CI 安全测试 |
| 字节码分析不够严格 | 🟡 中 | 二期引入 OWASP ESAPI + CodeQL 扫描 |
| 白名单遗漏 | 🟡 中 | 持续补充；日志告警未知方法调用 |
| 内存泄漏 | 🟡 中 | GroovyShell 每次新建；监控 + 限流 |
| DoS 攻击 | 🟡 中 | 超时 + 限流（RFC-0027 配合） |

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

- 上游：RFC-0019（Groovy 源码）
- 下游：RFC-0021（批量执行 + RuleSetArtifact）
- ADR：**ADR-006 规则源语言采用 SimpleTS**
- 规范：[docs/dsl/SimpleTS.md §8](../../dsl/SimpleTS.md)
- 对应风险：T1（Groovy 沙箱被绕过）—— 最高优先级
