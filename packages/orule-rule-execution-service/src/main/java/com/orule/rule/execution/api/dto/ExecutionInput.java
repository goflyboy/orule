package com.orule.rule.execution.api.dto;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Service -> Executor internal input (RFC-0040 §3.4 ExecutionInput; RFC-0045 §4.6).
 *
 * <p>NOT exposed via HTTP; this is the envelope that the Service assembles
 * before invoking the sandbox executor.
 *
 * @param executorType        SPI route key (JavaSourceExecutor / PythonExecutor / ...)
 * @param sourceCode          SimpleTS -> Java source string (post RFC-0019 transform)
 * @param context             input context collection (deep-copied; isolated from external Request)
 * @param metadata            metadata from rule-management-service (traceId/operatorId/tenant)
 * @param resolvedObjectTypes RFC-0045: ObjectType metadata used to generate the Groovy
 *                            domain-class prefix and to hydrate top-level binding slots.
 *                            Empty list means "no metadata available" — the executor
 *                            falls back to the sourceCode verbatim (RFC-0043 §4.2
 *                            compatibility path).
 */
@Schema(description = "Service -> Executor internal input", hidden = true)
public record ExecutionInput(
        @Schema(description = "Executor type identifier")
        String executorType,

        @Schema(description = "Source code string (Java/Groovy/Python)")
        String sourceCode,

        @Schema(description = "Input context collection (deep-copied)")
        Map<String, Object> context,

        @Schema(description = "Execution metadata (traceId / operatorId / tenant)")
        ExecutionMetadata metadata,

        @Schema(description = "Resolved ObjectType metadata for prefix + hydrate (RFC-0045)")
        List<ResolvedObjectType> resolvedObjectTypes
) {
    /** RFC-0043 §4.2 compatibility ctor: empty metadata list. */
    public ExecutionInput(String executorType, String sourceCode,
                          Map<String, Object> context, ExecutionMetadata metadata) {
        this(executorType, sourceCode, context, metadata, List.of());
    }
}
