package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Service -> Executor internal input (RFC-0040 §3.4 ExecutionInput).
 *
 * <p>NOT exposed via HTTP; this is the envelope that the Service assembles
 * before invoking the sandbox executor. Becomes part of the SPI signature
 * in Sprint 1.2 (see §3.7).
 *
 * @param executorType SPI route key (JavaSourceExecutor / PythonExecutor / ...)
 * @param sourceCode   SimpleTS -> Java source string (post RFC-0019 transform)
 * @param context      input ObjectInst collection (deep-copied; isolated from external Request)
 * @param metadata     metadata from rule-management-service (traceId/operatorId/tenant)
 */
@Schema(description = "Service -> Executor internal input", hidden = true)
public record ExecutionInput(
        @Schema(description = "Executor type identifier")
        String executorType,

        @Schema(description = "Source code string (Java/Groovy/Python)")
        String sourceCode,

        @Schema(description = "Input ObjectInst collection (deep-copied)")
        Map<String, Object> context,

        @Schema(description = "Execution metadata (traceId / operatorId / tenant)")
        ExecutionMetadata metadata
) {
}
