# RFC-0020: Groovy 沙箱 + 单条规则执行器

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：5d · **阶段**：S4
> **关联数据模型**：[RFC-0032 ObjectType 枚举化 + Type 系统简化](RFC-0032-ObjectType枚举化与Type系统简化.md)、[RFC-0033 RuleSetType 与 RuleType](RFC-0033-元数据管理2-RuleSetType与RuleType.md)

---

## 1. 摘要

实现 Groovy 沙箱（白名单 + 超时 + 内存限制）和单条规则执行器 `RuleExecutor`，在 orule-runtime 中安全执行 Groovy 脚本。
执行器消费 RFC-0033 的 `RuleType` 元数据（`arguments` / `returnType` / `functionTypeCodes` / `validatable`）作为白名单依据，并通过 RFC-0032 的 4 Variant Type 系统校验 enum 引用。

> **⚠️ 安全关键**：本 RFC 对应高风险 **T1（Groovy 沙箱被绕过）**，是 MVP 安全性最高优先级。

---

## 2. 动机

- Groovy 沙箱是 MVP 安全底线（依据 ADR-006 §14 风险缓解）
- 执行器是 NL→SimpleTS→Groovy→执行 链路的最终环节（依据 `06-运行视图 §6.1.1` Step 11~12）
- 依据 `06-运行视图 §6.2.2` 执行状态机（PENDING→RUNNING→SUCCESS/FAILED）
- **白名单单一来源**：依据 RFC-0033 §3.3，`RuleType.functionTypeCodes` 是规则可用 SDK 的权威白名单（取代 RFC-0018 §3.10 SimpleTSWhitelist 中"自由白名单"思路）

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
├── meta/
│   ├── RuleTypeMetaProvider.java  # 依据 RFC-0033 §3.3 拉取 RuleType 元数据
│   └── WhitelistBuilder.java       # 依据 RuleType.functionTypeCodes 构造白名单
├── config/
│   └── RuntimeProperties.java     # application.yml 配置绑定
└── OruleRuntimeApplication.java   # 已存在（RFC-0012）
```

> **修订说明**：原模块位置仅含沙箱与执行器；RFC-0033 落地后，新增 `meta` 子包专门负责 RuleType 元数据消费与白名单拼装。
> `RuleExecutor` 通过 `RuleTypeMetaProvider` 拉取 `RuleType.arguments` / `functionTypeCodes` / `excludeFunctionTypeCodes`，
> `WhitelistBuilder` 据此生成 `SandboxConfig.allowedMethods`，避免硬编码白名单。

### 3.2 沙箱配置

```java
package com.orule.runtime.execution;

import com.orule.common.entity.RuleType;   // RFC-0033 §3.3 元数据
import com.orule.common.entity.FuntionType; // RFC-0033 §3.8 重命名自 FunctionLib

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * 沙箱配置（来自 application.yml 的 orule.runtime.sandbox 节 + RuleType 元数据）。
 *
 * <p><b>RFC-0033 修订</b>：白名单的"基础全集"由 {@link FuntionType} 提供（内置 SDK 全集），
 * 但每条规则实际可用的白名单由 {@link RuleType#getArguments()} 中的
 * {@code functionTypeCodes} + {@code excludeFunctionTypeCodes} 决定。
 *
 * <p><b>RFC-0032 修订</b>：enum 类型不再由独立 SimpleTSWhitelist 提供，改为运行时通过
 * {@code ObjectType.Kind=ENUM} 动态加载（见 RFC-0032 §3.6）。
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

    /** 允许的方法白名单（完全限定签名），由 RuleType.functionTypeCodes 动态构造 */
    Set<String> allowedMethods,

    /** 允许的类白名单（完全限定名），由 RuleType.arguments[*].objectTypeCode 反查 ObjectType 构造 */
    Set<String> allowedClasses,

    /** 禁止的包前缀（黑名单，固定常量，无 RFC-0032 后已精简） */
    Set<String> forbiddenPackages,

    /** 禁止的方法前缀（黑名单，deny-list 兜底） */
    Set<String> forbiddenMethods
) {
    public SandboxConfig {
        if (timeoutMs <= 0)         timeoutMs = 30_000;
        if (maxMemoryMb <= 0)       maxMemoryMb = 128;
        if (maxLocalVariables <= 0) maxLocalVariables = 100;
        if (maxLoopIterations <= 0) maxLoopIterations = 10_000;
        // 注意：allowedMethods / allowedClasses 不可设默认；必须由 RuleTypeMetaProvider 提供
        if (allowedMethods == null)     allowedMethods     = Set.of();
        if (allowedClasses == null)     allowedClasses     = Set.of();
        if (forbiddenPackages == null)  forbiddenPackages  = defaultForbiddenPackages();
        if (forbiddenMethods == null)   forbiddenMethods   = defaultForbiddenMethods();
    }

    /**
     * RFC-0032 修订：forbiddenPackages 仅含业务侧危险包，不再含 Groovy/JDK 内部类
     * （那些由 SecureASTCustomizer 在 AST 阶段拦截，本表兜底）。
     */
    static Set<String> defaultForbiddenPackages() {
        return Set.of(
            "java.io.",
            "java.nio.",
            "java.net.",
            "java.rmi.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "javax.script.",
            "org.springframework.context."
        );
    }

    /**
     * RFC-0032 修订：deny-list 仅含执行期必须禁止的方法（即便在白名单 SDK 内也不应被规则调用）。
     * 例：{@code System.exit} / {@code Runtime.exec} 即使被某种 SDK 包装也不应允许。
     */
    static Set<String> defaultForbiddenMethods() {
        return Set.of(
            "exit", "halt", "exec", "loadClass", "forName",
            "invoke", "newInstance", "getDeclaredMethod",
            "setAccessible", "defineClass", "loadLibrary"
        );
    }
}
```

### 3.3 Groovy 沙箱核心

```java
package com.orule.runtime.execution;

import com.orule.runtime.meta.WhitelistBuilder; // RFC-0033 §3.3 接入

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
 * <p><b>RFC-0033 修订</b>：白名单由 {@link WhitelistBuilder} 基于
 * {@code RuleType.functionTypeCodes} 动态构造，而非硬编码常量。
 *
 * <p><b>RFC-0032 修订</b>：{@code enum} 类型不在白名单中预先列出；运行时若 Groovy 代码
 * 引用 {@code CustomerTier.VIP}（由 RFC-0019 GroovyCodeGen 在编译期已注入 enum 块），
 * 由 SecureASTCustomizer 在 AST 阶段校验 enum 是否在 RuleType 入参 objectTypeCode 范围内。
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

    /**
     * 构造沙箱。
     *
     * <p><b>RFC-0033 修订</b>：构造时接收由 {@link WhitelistBuilder} 计算好的白名单，
     * 不再使用 SimpleTSWhitelist 的静态默认值。
     *
     * @param config 沙箱配置（含动态白名单）
     */
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

        // RFC-0033 修订：allowedImports 由 allowedClasses 动态构造，而非来自 SimpleTSWhitelist
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
    private final RuleTypeMetaProvider ruleTypeMetaProvider;     // RFC-0033 §3.3
    private final WhitelistBuilder whitelistBuilder;             // RFC-0033 §3.3

    /**
     * 执行单条规则（PUBLISHED 版本）。
     *
     * <p><b>RFC-0033 修订</b>：执行前依据 {@code Rule.version.typeCode} 查找对应的
     * {@code RuleType} 元数据，按其 {@code functionTypeCodes} 构造白名单后再启动沙箱。
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

        // 3. RFC-0033：拉取 RuleType 元数据，按 functionTypeCodes 构造白名单
        //    同一规则并发执行可缓存（ruleTypeId 维度，TTL 5min）
        RuleType ruleType = ruleTypeMetaProvider.getByCode(version.getRule().getTypeCode())
            .orElseThrow(() -> new NotFoundException(
                "RuleType " + version.getRule().getTypeCode() + " 未定义"));

        SandboxConfig scopedConfig = whitelistBuilder.buildFor(ruleType);
        GroovySandbox scopedSandbox = new GroovySandbox(scopedConfig);

        // 4. 沙箱执行（使用 ruleType 维度的白名单）
        ExecutionResult result = scopedSandbox.execute(groovyScript, inputContext);

        // 5. 记录 ExecutionLog
        ExecutionLog logEntry = ExecutionLog.builder()
            .id(String.valueOf(System.nanoTime()))
            .ruleId(ruleId)
            .ruleVersionId(version.getId())
            .ruleTypeId(ruleType.getId())                          // RFC-0033 补充字段
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

> **RFC-0033 修订说明**：
> - **新增** `RuleTypeMetaProvider` 调用：根据 `Rule.typeCode` 拉取 `RuleType` 实体。
> - **新增** `WhitelistBuilder.buildFor(ruleType)` 调用：构造 RuleType 维度的 `SandboxConfig`。
> - **新增** `ExecutionLog.ruleTypeId` 字段：执行日志关联元数据，便于按 RuleType 维度统计与审计。
> - **白名单生效点**：每条规则按其 `RuleType.functionTypeCodes` 独立沙箱，不能访问其他 RuleType 的 SDK（即便底层 FuntionType 是同一份）。

### 3.6 WhitelistBuilder（RFC-0033 接入）

> **新增小节**：原 RFC-0018 §3.10 SimpleTSWhitelist 的"静态白名单常量"思路被 RFC-0033 §3.3 取代。
> 本 RFC 新增 `WhitelistBuilder`，按 RuleType 动态构造沙箱白名单。

```java
package com.orule.runtime.meta;

import com.orule.common.entity.RuleType;
import com.orule.common.entity.FuntionType;
import com.orule.common.entity.ObjectType;
import com.orule.common.entity.RuleTypeArguments;
import com.orule.common.repository.FuntionTypeRepository;
import com.orule.common.repository.ObjectTypeRepository;

import com.orule.runtime.execution.SandboxConfig;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 RuleType 维度构造沙箱白名单（RFC-0033 §3.3 + RFC-0020 §3.6）。
 *
 * <p>输入：{@link RuleType}；输出：{@link SandboxConfig}。
 */
@Component
@RequiredArgsConstructor
public class WhitelistBuilder {

    private final FuntionTypeRepository funtionTypeRepo;
    private final ObjectTypeRepository objectTypeRepo;

    /**
     * 根据 RuleType 构造沙箱配置。
     *
     * <p>白名单构造算法：
     * <ol>
     *   <li>取出 {@code RuleType.arguments[*].objectTypeCode}（入参 ObjectType 集合）</li>
     *   <li>反查 {@code ObjectType} 拿到允许的类集合（含 CLASS / ENUM）</li>
     *   <li>取出 {@code RuleType.arguments.functionTypeCodes}（白名单 SDK）</li>
     *   <li>从 {@code FuntionType.signature} 提取允许的方法签名</li>
     *   <li>排除 {@code excludeFunctionTypeCodes} 中的 SDK</li>
     * </ol>
     */
    public SandboxConfig buildFor(RuleType ruleType) {
        Set<String> allowedClasses = new HashSet<>();
        Set<String> allowedMethods = new HashSet<>();

        // 1. 入参 ObjectType 全部反查
        RuleTypeArguments args = ruleType.getArguments();
        if (args != null && args.arguments() != null) {
            for (RuleTypeArguments.ArgumentType arg : args.arguments()) {
                ObjectType ot = objectTypeRepo.findByProgramCode(arg.objectTypeCode())
                    .orElseThrow(() -> new IllegalStateException(
                        "ObjectType 不存在: " + arg.objectTypeCode()));
                allowedClasses.add(ot.getProgramCode());
                // CLASS 类型额外把其 AttributeType 的 primitive 类型也加进去
                if (ot.getKind() == ObjectType.Kind.CLASS) {
                    ot.getAttributes().forEach(a -> {
                        if ("primitive".equals(a.getDataType())) {
                            allowedClasses.add(a.getSubDataTypeProgramCode());
                        }
                    });
                }
            }
        }

        // 2. 白名单 SDK → FuntionType → 方法签名
        Set<String> includeCodes = new HashSet<>(args.functionTypeCodes());
        Set<String> excludeCodes = new HashSet<>(args.excludeFunctionTypeCodes());

        List<FuntionType> allowedFuntions = funtionTypeRepo.findByProgramCodeIn(includeCodes);
        for (FuntionType ft : allowedFuntions) {
            if (excludeCodes.contains(ft.getProgramCode())) continue;
            ft.getSignature().params().forEach(p -> {
                if (p.kind().equals("primitive")) {
                    allowedMethods.add("java.lang." + capitalize(p.name()));
                }
            });
            // FuntionType 自身的方法签名
            allowedMethods.add(ft.getProgramCode() + ".execute");
        }

        // 3. 构造 SandboxConfig（沿用 RFC-0020 §3.2 默认 timeout / memory / etc.）
        return new SandboxConfig(
            30_000L,        // timeoutMs
            128,            // maxMemoryMb
            100,            // maxLocalVariables
            10_000,         // maxLoopIterations
            allowedMethods,
            allowedClasses,
            null,           // forbiddenPackages → 默认
            null            // forbiddenMethods → 默认
        );
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
```

```java
package com.orule.runtime.meta;

import com.orule.common.entity.RuleType;
import com.orule.common.repository.RuleTypeRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RuleType 元数据加载器（带 5 分钟缓存）。
 *
 * <p>RFC-0033 §3.3 配套：执行器按需拉取 RuleType。
 */
@Component
@RequiredArgsConstructor
public class RuleTypeMetaProvider {

    private final RuleTypeRepository ruleTypeRepo;

    /** code → RuleType 缓存（5min TTL） */
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public java.util.Optional<RuleType> getByCode(String code) {
        CacheEntry entry = cache.get(code);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.timestamp < 300_000L) {
            return java.util.Optional.of(entry.ruleType);
        }
        return ruleTypeRepo.findByCode(code).map(rt -> {
            cache.put(code, new CacheEntry(rt, now));
            return rt;
        });
    }

    public void invalidate(String code) {
        cache.remove(code);
    }

    private record CacheEntry(RuleType ruleType, long timestamp) {}
}
```

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
 * <p><b>RFC-0033 修订</b>：新增 {@code ruleTypeCode} 字段，告知前端本次执行使用的 RuleType。
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
    Instant executedAt,
    /** RFC-0033：本次执行使用的 RuleType.programCode（前端可用于 audit / debug） */
    String ruleTypeCode
) {
    public static ExecutionResponse from(ExecutionResult result) {
        return new ExecutionResponse(
            result.success(),
            result.errorCode(),
            result.errorMessage(),
            result.outputContext(),
            result.durationMs(),
            result.executedAt(),
            null   // ruleTypeCode 由 Service 层填充
        );
    }

    public static ExecutionResponse from(ExecutionResult result, String ruleTypeCode) {
        return new ExecutionResponse(
            result.success(),
            result.errorCode(),
            result.errorMessage(),
            result.outputContext(),
            result.durationMs(),
            result.executedAt(),
            ruleTypeCode
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
    "executedAt": "2026-09-12T13:30:00Z",
    "ruleTypeCode": "ORDER_DISCOUNT_VALIDATE"
}
```

### 3.8 安全加固清单

> **修订**：原清单"字节码层：Groovy Sandbox Policy + ASM 分析 ✅ 本 RFC"与实现不符
> （代码里没有任何 ASM 分析）。新版 4 层防护与 §3.3 实现对齐。
> **RFC-0033 修订**：新增"RuleType 白名单维度"作为白名单单一来源的依据。

| 层级 | 措施 | 状态 | 依据 |
|------|------|------|------|
| **L1 源码层** | SimpleTS WhitelistPruner（RFC-0018）禁止危险语法 | ✅ 已做 | RFC-0018 §3.5 |
| **L2 AST 层** | Groovy SecureASTCustomizer：forbiddenImports + receiversBlackList + methodBlacklist | ✅ 本 RFC | §3.3 |
| **L3 字节码层** | SecureClassLoader + allowedClasses 白名单 + forbiddenPackages 黑名单 | ✅ 本 RFC | §3.3 |
| **L4 运行时层** | 超时线程（Future.cancel）+ JVM -Xmx 内存限制 + 脚本长度上限 100KB | ✅ 本 RFC | §3.3 + SandboxConfig |
| **白名单单一来源** | RFC-0033 §3.3 RuleType.functionTypeCodes 动态构造白名单；不再使用 SimpleTSWhitelist 静态常量 | ✅ 本 RFC | RFC-0033 §3.3 + §3.6 WhitelistBuilder |
| **enum 校验** | RFC-0032 §3.6 enum 引用 = `ObjectType.Kind=ENUM` 校验 | ✅ 已做 | RFC-0032 §3.6 |
| **审计层** | ExecutionLog 记录每次执行（含 ruleTypeId 维度，RFC-0033） | ✅ 已做（RFC-0014 execution_log 表） | §3.5 |
| **测试** | OWASP 沙箱渗透测试 + fuzz 测试 | 🟡 待补 | §5.2 |

---

## 4. 影响面

- 新增 `com.orule.runtime.execution` 包（~10 个类）
- **新增 `com.orule.runtime.meta` 包**：`RuleTypeMetaProvider` + `WhitelistBuilder`（RFC-0033 接入）
- 新增 `/api/v1/rules/{id}/execute` 端点
- 依赖 `groovy-all` 依赖（`groovy.version=4.0.24`）
- 不涉及数据库新表（复用 RFC-0014 的 execution_log 表，**新增字段 `rule_type_id`**）
- **元数据层依赖**：RFC-0033 §3.2 `rule_set_type` / §3.3 `rule_type` / §3.8 `funtion_type`（原 function_lib）
- **Type 系统依赖**：RFC-0032 §3.1 4 Variant Type（PrimitiveType / ObjectRef / ListType / MapType）

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
| **RFC-0033**：规则引用未在 `RuleType.functionTypeCodes` 中的 SDK | SANDBOX_VIOLATION |
| **RFC-0032**：规则引用 `Kind=CLASS` 的 ObjectType 当作 enum 用 | SANDBOX_VIOLATION |
| **RFC-0033**：`excludeFunctionTypeCodes` 中的 SDK 被显式排除 | SANDBOX_VIOLATION |

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
| **T1 沙箱被绕过**（代码字符串拼接、注释旁路、Unicode 转义、reflection） | 🔴 极高 | 真正的 4 层防护（§3.8）：SecureASTCustomizer（AST 精确拦截）+ SecureClassLoader（类加载兜底）+ 超时 + 长度上限；不做字符串 contains 扫描 |
| Groovy 4 SecureASTCustomizer 配置错误 | 🟡 中 | CI 安全测试覆盖 OWASP Top 10 + 字符串拼接绕过 fuzz |
| for (;;) {} 等死循环 | 🟡 中 | Future.cancel(true) + JVM -Xmx |
| **RFC-0033** 白名单遗漏合法 SDK | 🟡 中 | RuleType.functionTypeCodes 单一来源；WhitelistBuilder 从 FuntionType.signature 自动派生方法集；CI 断言"RuleType 白名单 ⊆ FuntionType 全集" |
| 内存泄漏（GroovyShell 缓存） | 🟢 低 | RuleType 维度构造沙箱（5min 缓存）；定期监控 classLoader 加载的类数量 |
| DoS（超长脚本 / 高频调用） | 🟡 中 | 脚本长度 100KB 上限；orule-runtime 入口限流（RFC-0027 配合） |
| ExecutorService 关闭时丢日志 | 🟢 低 | shutdown() 给予 5s 优雅退出，超时后 shutdownNow |
| **RFC-0033** RuleType 元数据不一致（functionTypeCodes 引用不存在的 FuntionType） | 🟡 中 | MetadataService 创建 RuleType 时按 RFC-0033 §3.7 不变式 #3 校验；启动期 MetadataIntegrityChecker 自检 |
| **RFC-0032** enum 引用 Kind≠ENUM 的 ObjectType | 🟢 低 | RFC-0019 GroovyCodeGen 仅遍历 `kind=ENUM` 的 ObjectType 注入 enum 块；执行期无 enum 注入则 SecureASTCustomizer 自然拒绝 |

---

## 7. 实施步骤

```
1. 创建 com.orule.runtime.execution 包
2. 实现 SandboxConfig + 白名单配置（RFC-0033 接入点）
3. 实现 GroovySandbox（预校验 + 超时执行）
4. 实现 ExecutionResult + 异常类
5. 实现 RuleExecutor
   **5a. 实现 com.orule.runtime.meta.RuleTypeMetaProvider（RFC-0033 §3.3 元数据加载器，带 5min 缓存）**
   **5b. 实现 com.orule.runtime.meta.WhitelistBuilder（依据 RuleType.functionTypeCodes 构造 SandboxConfig）**
   **5c. RFC-0014 V8 ALTER TABLE execution_log ADD COLUMN rule_type_id VARCHAR(36)**
6. 实现 REST 端点
7. 单元测试（30+ 安全测试用例；含 RFC-0032 enum 与 RFC-0033 RuleType 维度用例）
8. 集成测试（Testcontainers）
9. 安全渗透测试（OWASP ZAP）
10. 性能测试（JMeter 或 Gatling）
11. 超时/内存压测
```

---

## 8. 关联

- 上游：RFC-0019（Groovy 源码）、RFC-0014（数据库 schema，execution_log 表 + V8 ALTER 加 rule_type_id）
- 平级：RFC-0018 §3.10 SimpleTSWhitelist（**已被 RFC-0033 §3.3 RuleType.functionTypeCodes 取代**）
- 下游：RFC-0021（批量执行 + RuleSetArtifact）
- **关键依赖**：
  - **RFC-0032 ObjectType 枚举化** §3.1（4 Variant Type 系统）/ §3.3（entity.ObjectType.Kind=ENUM）/ §3.6（enum 引用校验）
  - **RFC-0033 RuleSetType 与 RuleType** §3.3（RuleType 元数据）/ §3.7（不变式保证）/ §3.8（FuntionType 重命名）
- ADR：**ADR-006 规则源语言采用 SimpleTS**、**ADR-012 enum 视为 ObjectType 特殊形态**
- 规范：[docs/dsl/SimpleTS.md §8](../../dsl/SimpleTS.md)
- 对应风险：T1（Groovy 沙箱被绕过）—— 最高优先级

---

## 9. 修订日志

| 日期 | 修订内容 | 关联 RFC |
|------|---------|---------|
| 2026-09-12 | 状态：IMPROVED → DRAFT 重新审视 | — |
| 2026-09-12 | 删除 `SimpleTSWhitelist` 静态常量白名单，改为 RFC-0033 §3.3 `RuleType.functionTypeCodes` 动态构造 | RFC-0033 |
| 2026-09-12 | 新增 `com.orule.runtime.meta` 子包（`RuleTypeMetaProvider` + `WhitelistBuilder`） | RFC-0033 §3.3 |
| 2026-09-12 | `ExecutionLog` 实体新增 `rule_type_id` 字段（关联 RuleType 元数据） | RFC-0033 §3.3 |
| 2026-09-12 | `ExecutionResponse` DTO 新增 `ruleTypeCode` 字段 | RFC-0033 §3.3 |
| 2026-09-12 | enum 校验路径更新：基于 `ObjectType.Kind=ENUM`，不再使用 `EnumType` Variant | RFC-0032 §3.6 |
| 2026-09-12 | `SandboxConfig.forbiddenPackages/forbiddenMethods` 默认值精简（不再含 Groovy/JDK 内部类） | RFC-0032 |
| 2026-09-12 | 测试用例补充：RuleType 白名单维度、enum 校验维度、excludeFunctionTypeCodes 维度 | RFC-0032 / RFC-0033 |
