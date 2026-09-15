package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lambda-based assertion surface for the
 * {@code GET /executions/{taskId}/logs} detail JSON.
 */
@FunctionalInterface
public interface DetailAssert {
    void check(JsonNode detail);
}
