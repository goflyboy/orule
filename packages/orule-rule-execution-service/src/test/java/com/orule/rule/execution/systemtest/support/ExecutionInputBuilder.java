package com.orule.rule.execution.systemtest.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for the {@code context} JSON object passed to rule execution.
 *
 * <p>Two ways to populate:
 * <ul>
 *   <li>{@link #set(String, Object)} — explicit key/value (typical for primitives)</li>
 *   <li>{@link #also(Object)} — POJO via Jackson bean → map flattening (RFC-0042 §4.5)</li>
 * </ul>
 *
 * <pre>{@code
 * input().set("price", 100).also(customer)   // POJO fields merged at top-level
 * }</pre>
 *
 * <p>Field name collisions between {@code set(...)} and {@code also(...)} are
 * resolved last-write-wins; POJO fields merge first, then {@code set(...)}
 * overrides.
 */
public class ExecutionInputBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final LinkedHashMap<String, Object> ctx = new LinkedHashMap<>();

    ExecutionInputBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ExecutionInputBuilder set(String key, Object value) {
        ctx.put(key, value);
        return this;
    }

    /**
     * Convert {@code pojo} to a {@code Map<String,Object>} via Jackson and merge
     * into the context at the top level. Nulls in the bean map are dropped.
     */
    public ExecutionInputBuilder also(Object pojo) {
        if (pojo == null) {
            return this;
        }
        Map<String, Object> beanMap = mapper.convertValue(pojo, MAP_TYPE);
        for (Map.Entry<String, Object> e : beanMap.entrySet()) {
            if (e.getValue() != null) {
                ctx.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    /**
     * Bind the entire POJO under a single key (e.g. {@code put("customer", alice)})
     * — use this when the rule needs {@code customer.isVip()} rather than
     * top-level {@code isVip()}.
     */
    public ExecutionInputBuilder put(String key, Object value) {
        ctx.put(key, value);
        return this;
    }

    public Map<String, Object> build() {
        return Map.copyOf(ctx);
    }
}
