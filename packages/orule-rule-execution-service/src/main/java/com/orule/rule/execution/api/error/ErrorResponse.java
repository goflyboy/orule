package com.orule.rule.execution.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified error response body (RFC-0040 §16.6 error code contract).
 *
 * <p>All 4xx / 5xx responses use this structure. {@code success=false} is always set.
 * HTTP 200 success responses do NOT use this structure.
 *
 * <p>Example:
 * <pre>
 * {
 *   "success": false,
 *   "errorCode": "RULE_NOT_FOUND",
 *   "errorMessage": "Rule not found: ORDER_VIP_DISCOUNT",
 *   "traceId": "0af7651916cd43dd8448eb211c80319c"
 * }
 * </pre>
 */
@Schema(description = "Unified error response body (HTTP 4xx / 5xx)")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @Schema(description = "Business success flag", example = "false")
        boolean success,

        @Schema(description = "Error code (§16.6)",
                example = "RULE_NOT_FOUND",
                allowableValues = {"EVAL_FAILED", "RUNTIME_ERROR",
                        "RULE_NOT_FOUND", "RULE_SET_NOT_FOUND", "TASK_NOT_FOUND",
                        "RATE_LIMIT_EXCEEDED", "EXECUTOR_NOT_REGISTERED", "INTERNAL_ERROR"})
        String errorCode,

        @Schema(description = "Error details", example = "Rule not found: ORDER_VIP_DISCOUNT")
        String errorMessage,

        @Schema(description = "Trace ID", example = "0af7651916cd43dd8448eb211c80319c")
        String traceId,

        @Schema(description = "HTTP status code", example = "404")
        Integer httpStatus
) {
}
