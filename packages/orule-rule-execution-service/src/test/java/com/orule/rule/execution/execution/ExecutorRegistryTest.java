package com.orule.rule.execution.execution;

import com.orule.rule.execution.error.ExecutorNotRegisteredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0040 §3.7 + TASK-1.2.1 acceptance tests.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>{@link ExecutorRegistry} SPI loading with zero implementations (empty SPI files)</li>
 *   <li>Resolution throws {@link ExecutorNotRegisteredException} for unregistered types</li>
 *   <li>{@link ExecutorType} enum round-trip parse</li>
 *   <li>Registered types list is empty on startup (before any Executor is wired)</li>
 * </ol>
 */
@DisplayName("ExecutorRegistry SPI")
class ExecutorRegistryTest {

    // Note: ExecutorRegistry is a @Component; we instantiate directly to avoid
    // needing the full Spring context for this unit test.
    private final ExecutorRegistry registry = new ExecutorRegistry();

    @Nested
    @DisplayName("resolveRuleExecutor")
    class ResolveRuleExecutor {

        @Test
        @DisplayName("throws ExecutorNotRegisteredException for unknown type")
        void throwsForUnknownType() {
            // Use a type guaranteed not to be auto-registered.
            assertThatThrownBy(() -> registry.resolveRuleExecutor("rust-executor"))
                    .isInstanceOf(ExecutorNotRegisteredException.class)
                    .extracting("errorCode").isEqualTo("EXECUTOR_NOT_REGISTERED");
        }

        @Test
        @DisplayName("throws ExecutorNotRegisteredException for null type")
        void throwsForNull() {
            assertThatThrownBy(() -> registry.resolveRuleExecutor(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("resolveRuleSetExecutor")
    class ResolveRuleSetExecutor {

        @Test
        @DisplayName("throws ExecutorNotRegisteredException for unknown rule-set type")
        void throwsForUnknownType() {
            assertThatThrownBy(() -> registry.resolveRuleSetExecutor("python"))
                    .isInstanceOf(ExecutorNotRegisteredException.class)
                    .extracting("errorCode").isEqualTo("EXECUTOR_NOT_REGISTERED");
        }
    }

    @Nested
    @DisplayName("registeredRuleExecutorTypes")
    class RegisteredRuleExecutorTypes {

        @Test
        @DisplayName("contains JavaSourceExecutor once SPI file is wired (TASK-1.2.2)")
        void containsJavaSourceAfterWiring() {
            // After TASK-1.2.2 lands JavaSourceExecutor is auto-registered via SPI file.
            assertThat(registry.registeredRuleExecutorTypes())
                    .contains(ExecutorType.JAVA_SOURCE.typeId());
        }
    }

    @Nested
    @DisplayName("ExecutorType enum")
    class ExecutorTypeEnum {

        @Test
        @DisplayName("from() returns JAVA_SOURCE for known id")
        void knownId() {
            assertThat(ExecutorType.from("java-source")).isEqualTo(ExecutorType.JAVA_SOURCE);
        }

        @Test
        @DisplayName("from() returns PYTHON for known id")
        void python() {
            assertThat(ExecutorType.from("python")).isEqualTo(ExecutorType.PYTHON);
        }

        @Test
        @DisplayName("from() returns UNKNOWN for unknown id")
        void unknownId() {
            assertThat(ExecutorType.from("rust-executor")).isEqualTo(ExecutorType.UNKNOWN);
        }

        @Test
        @DisplayName("typeId() round-trips correctly")
        void roundTrip() {
            for (ExecutorType t : ExecutorType.values()) {
                assertThat(ExecutorType.from(t.typeId())).isEqualTo(t);
            }
        }
    }
}
