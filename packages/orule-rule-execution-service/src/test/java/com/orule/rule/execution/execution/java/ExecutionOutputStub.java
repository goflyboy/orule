package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.api.dto.ExecutionOutput;

import java.util.Map;

/**
 * Tiny helper for tests that need to fabricate {@link ExecutionOutput}
 * without pulling in the full Groovy stack.
 */
public final class ExecutionOutputStub {

    private ExecutionOutputStub() {}

    public static ExecutionOutput success(Map<String, Object> context) {
        return new ExecutionOutput(context, true, null, null);
    }

    public static ExecutionOutput failed(String code, String message) {
        return new ExecutionOutput(Map.of(), false, code, message);
    }
}
