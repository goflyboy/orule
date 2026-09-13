package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Execution metadata (RFC-0040 §3.4 ExecutionInput.metadata).
 *
 * <p>Aggregates JWT / Feign client context before passing to the sandbox,
 * so trace_id can be auto-injected into OpenTelemetry span.
 */
@Schema(description = "Execution metadata")
public record ExecutionMetadata(
        @Schema(description = "OpenTelemetry trace_id", example = "0af7651916cd43dd8448eb211c80319c")
        String traceId,

        @Schema(description = "Operator ID", example = "user-1001")
        String operatorId,

        @Schema(description = "Tenant ID", example = "tenant-acme")
        String tenantId
) {
}
