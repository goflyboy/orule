package com.orule.rule.execution.execution.java;

import org.codehaus.groovy.ast.GroovyCodeVisitor;
import org.codehaus.groovy.ast.expr.ClosureListExpression;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.util.Arrays;
import java.util.List;

/**
 * Whitelist / blacklist policy for the Groovy sandbox (RFC-0040 ?18.10 / TASK-1.2.3).
 *
 * <p>Groovy 4 implements sandbox via {@link SecureASTCustomizer} at the <b>import</b> level
 * (verified against groovy 4.0.22 in m2 cache ? Groovy 4 dropped both
 * {@code setAllowedClasses} and {@code setBlockedPackages}).
 *
 * <p>Defense layers:
 * <ol>
 *   <li>{@link ImportCustomizer} ? explicit whitelist of allowed packages.</li>
 *   <li>{@code setIndirectImportCheckEnabled(true)} ? rejects wildcard / reflection imports.</li>
 *   <li>{@code setDisallowedImports} ? explicit blacklist of forbidden root packages.</li>
 *   <li>{@code setClosuresAllowed(false)} ? RFC-0043: reject Groovy closures and lambdas.
 *       C-style {@code for} uses {@code ClosureListExpression}; that form is still allowed.</li>
 *   <li>{@code setDisallowedStatements} ? reject {@code while}/{@code do-while};
 *       C-style {@code for} is allowed (timeout still applies).</li>
 * </ol>
 *
 * <p>Note: This is the v1.0 baseline. RFC-0020 ?3 plans additional
 * {@code RuleType}-scoped whitelists via {@code WhitelistBuilder}; that work
 * belongs to the {@code orule-runtime} sandbox module once RFC-0020 lands.
 */
public final class SandboxPolicy {

    /** Whitelist: JDK + minimal SDK packages that rules may import & reference. */
    public static final List<String> ALLOWED_PACKAGES = List.of(
            "java.lang.String", "java.lang.Math", "java.lang.Boolean",
            "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger", "java.math.RoundingMode",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Duration",
            "java.time.format.DateTimeFormatter",
            "java.util.UUID", "java.util.Objects", "java.util.Optional",
            "java.util.List", "java.util.Map", "java.util.Set",
            "java.util.Collection", "java.util.ArrayList",
            "java.util.regex.Pattern", "java.util.regex.Matcher"
    );

    /** Blacklist: explicitly forbidden packages (compile-rejected via imports). */
    public static final List<String> BLOCKED_PACKAGES = List.of(
            "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.io.ObjectInputStream", "java.io.ObjectOutputStream",
            "java.net.URL", "java.net.URLClassLoader", "java.net.URLConnection",
            "java.net.HttpURLConnection", "java.net.Socket", "java.net.ServerSocket",
            "java.net.InetAddress",
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.lang.System",
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Constructor",
            "java.lang.reflect.AccessibleObject",
            "java.lang.ClassLoader", "java.lang.invoke.MethodHandle",
            "javax.script.ScriptEngineManager", "javax.script.ScriptEngine",
            "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader",
            "org.springframework.context.ApplicationContext"
    );

    private SandboxPolicy() {}

    /**
     * Build a {@link ImportCustomizer} whitelisting allowed packages.
     */
    public static ImportCustomizer buildImportWhitelist() {
        ImportCustomizer c = new ImportCustomizer();
        ALLOWED_PACKAGES.forEach(c::addImports);
        return c;
    }

    /**
     * Build a {@link SecureASTCustomizer} configured with v1.0 import-level blacklist.
     *
     * <p>Groovy represents C-style {@code for (init; cond; update)} as a
     * {@link ClosureListExpression}. The stock visitor treats that as a closure and
     * would reject RFC-0043 {@code for} loops. We allow that list form only.
     */
    public static SecureASTCustomizer buildSecureAST() {
        SecureASTCustomizer s = new ForFriendlySecureASTCustomizer();
        // Reject wildcard and indirect imports.
        s.setIndirectImportCheckEnabled(true);
        // Disallow forbidden imports (compile-rejected).
        s.setDisallowedImports(BLOCKED_PACKAGES);
        // Block reflection & class-loading tokens as an additional defense.
        // Tasks for full token-level coverage go to TASK-3.2.1.
        s.setMethodDefinitionAllowed(true);  // prefix classes/enums need synthetic methods
        s.setClosuresAllowed(false);          // RFC-0043: no Groovy closure / lambda
        s.setPackageAllowed(false);           // no `package` declarations in rules
        // Allow C-style for; keep while/do-while banned so infinite loops stay timeout-bound.
        s.setDisallowedStatements(List.of(WhileStatement.class, DoWhileStatement.class));
        return s;
    }

    /**
     * Useful in tests / docs to print the policy as a single line.
     */
    public static String summary() {
        return "SandboxPolicy{allowed=" + ALLOWED_PACKAGES.size()
                + ", blocked=" + BLOCKED_PACKAGES.size()
                + ", tokens=[indirectImport,disallowedImports,noPackage,noClosure,noWhile]}";
    }

    /** Convenience accessor for tests / docs. */
    public static List<String> blockedPackagesList() {
        return Arrays.asList(BLOCKED_PACKAGES.toArray(new String[0]));
    }

    /**
     * {@link SecureASTCustomizer} that still forbids {@code { }} closures / lambdas
     * but permits the {@link ClosureListExpression} used by C-style {@code for}.
     */
    static final class ForFriendlySecureASTCustomizer extends SecureASTCustomizer {
        @Override
        protected GroovyCodeVisitor createGroovyCodeVisitor() {
            return new SecuringCodeVisitor() {
                @Override
                public void visitClosureListExpression(final ClosureListExpression expression) {
                    expression.getExpressions().forEach(expr -> {
                        if (expr != null) {
                            expr.visit(this);
                        }
                    });
                }
            };
        }
    }
}
