package com.orule.rule.execution.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Executor -> Service output (RFC-0040 §3.4 ExecutionOutput).
 *
 * @param context      ObjectInst collection with accumulated mutations
 * @param success      business success flag
 * @param errorCode    §16.6 error code (null when success=true)
 * @param errorMessage error details (null when success=true)
 */
@Schema(description = "Executor -> Service internal output", hidden = true)
public record ExecutionOutput(
        @Schema(description = "ObjectInst collection with accumulated mutations")
        Map<String, Object> context,

        @Schema(description = "Business success flag")
        boolean success,

        @Schema(description = "Error code (null when success=true)")
        String errorCode,

        @Schema(description = "Error details (null when success=true)")
        String errorMessage
) {
}
