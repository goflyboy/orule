package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.ExecutorType;
import com.orule.rule.execution.execution.RuleExecutor;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Default RuleExecutor implementation backed by a Groovy sandbox (RFC-0040 §3.8 + TASK-1.2.2).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Wrap {@code sourceCode} into a {@code return ...} script.</li>
 *   <li>Bind {@code input.context} as variables.</li>
 *   <li>Compile via {@link GroovyClassCache} (SHA-256 indexed).</li>
 *   <li>Run script; collect variables back into {@link ExecutionOutput#context()}.</li>
 * </ol>
 *
 * <p>Errors:
 * <ul>
 *   <li>Compile failure → {@link RuleEvalException} (HTTP 200 + EVAL_FAILED)</li>
 *   <li>Runtime exception in script body → {@link RuleRuntimeException} (HTTP 200 + RUNTIME_ERROR)</li>
 * </ul>
 *
 * <p>NOTE: This implementation does NOT enforce timeout — that responsibility lives in
 * {@code RuleExecutorService} (TASK-1.2.5) per RFC-0040 §3.13.
 */
@Component
public class JavaSourceExecutor implements RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceExecutor.class);

    private final GroovyClassCache cache;

    /**
     * Public no-arg constructor for JDK {@link java.util.ServiceLoader}.
     * Spring DI uses the single-argument {@link #JavaSourceExecutor(GroovyClassCache)}.
     */
    public JavaSourceExecutor() {
        this(new GroovyClassCache());
    }

    public JavaSourceExecutor(GroovyClassCache cache) {
        this.cache = cache;
    }

    @Override
    public String executorType() {
        return ExecutorType.JAVA_SOURCE.typeId();
    }

    @Override
    public ExecutionOutput execute(ExecutionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (input.sourceCode() == null || input.sourceCode().isBlank()) {
            return new ExecutionOutput(input.context(), false,
                    "EVAL_FAILED", "sourceCode is empty");
        }

        // 1. Build a GroovyShell with sandbox enforcement.
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.addCompilationCustomizers(SandboxPolicy.buildImportWhitelist());
        cfg.addCompilationCustomizers(SandboxPolicy.buildSecureAST());
        GroovyShell shell = new GroovyShell(cfg);

        // 2. Wrap source as a single expression: if rule body is a statement list,
        //    we run it as-is; if it is an expression, we return the result.
        //    v1.0 wraps everything in a script body (Groovy syntax), so the rule
        //    body is expected to assign to variables which we read back via the
        //    binding after execution.
        String wrapped = wrapSource(input.sourceCode());

        // 3. Compile (or fetch from cache).
        //    Cached Script instances are reused for compile-perf only; we always
        //    instantiate a fresh binding per execution so that user-declared
        //    `def sum = ...` variables don't leak between consecutive runs of
        //    the same script (Groovy 3 retains script state across runs).
        Script cachedScript;
        try {
            cachedScript = cache.getOrCompile(wrapped, shell);
        } catch (CompilationFailedException ex) {
            // MultipleCompilationErrorsException extends CompilationFailedException.
            log.warn("Rule compilation failed: traceId={}, error={}",
                    safeTraceId(input), ex.getMessage());
            throw new RuleEvalException("Failed to compile rule: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            // Other Groovy compile-time errors fall under EVAL_FAILED too.
            log.warn("Rule compile-time error: traceId={}, error={}",
                    safeTraceId(input), ex.getMessage());
            throw new RuleEvalException(ex.getMessage(), ex);
        }

        // 4. Create a fresh Binding per execution; bind input context as script variables.
        Binding binding = new Binding();
        if (input.context() != null) {
            input.context().forEach(binding::setVariable);
        }

        // 5. Run a fresh Script instance: cached parse + new binding == state isolation.
        try {
            // Script.class has no public 2-arg ctor in Groovy 3; we use the dynamic
            // approach of running it through a subclass reuse. The simplest portable
            // way is to clear script state via wrapper class.
            Script runnable = cloneScriptWithFreshBinding(cachedScript, binding);
            runnable.run();
        } catch (RuntimeException ex) {
            log.warn("Rule runtime error: traceId={}, error={}",
                    safeTraceId(input), ex.getMessage());
            throw new RuleRuntimeException(ex.getMessage(), ex);
        }

        // 6. Read back all binding variables as the accumulated output context.
        Map<String, Object> output = new HashMap<>();
        for (Object keyObj : binding.getVariables().keySet()) {
            String name = (String) keyObj;
            output.put(name, binding.getVariables().get(name));
        }
        return new ExecutionOutput(output, true, null, null);
    }

    private static String wrapSource(String ruleBody) {
        // Trim and ensure each rule body is treated as a script body.  JavaSourceExecutor
        // expects the caller (Service) to have already produced "Java-with-Groovy-syntax"
        // source via RFC-0019 (SimpleTS -> Groovy generator); no further transformation
        // is required here.
        return ruleBody.stripLeading();
    }

    /**
     * Produce a fresh Script instance bound to the given Binding. Groovy 3 retains
     * user-defined variables across calls to {@link Script#run()}, so we cannot reuse
     * a cached Script instance directly. Instead we instantiate a new object of the
     * cached Script's compiled class and {@code setBinding()} on it.
     *
     * <p>The Groovy 3 {@code Script} base class does not expose a public copy
     * constructor, so we use reflection to invoke the no-arg constructor of the
     * cached script's compiled class.
     */
    private static Script cloneScriptWithFreshBinding(Script cached, Binding binding) {
        try {
            Script fresh = cached.getClass().getDeclaredConstructor().newInstance();
            fresh.setBinding(binding);
            return fresh;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "GroovyScript class " + cached.getClass().getName()
                            + " has no public no-arg constructor", e);
        }
    }

    private static String safeTraceId(ExecutionInput input) {
        return input.metadata() != null ? input.metadata().traceId() : "<none>";
    }
}
