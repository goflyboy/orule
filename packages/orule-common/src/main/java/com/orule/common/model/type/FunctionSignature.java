package com.orule.common.model.type;

import java.util.List;

/**
 * 函数签名（RFC-0031 §3.1）。
 *
 * <p>存储到 {@code function_lib.signature} JSON 列，结构：
 * <pre>{@code
 * {
 *   "params": [
 *     { "kind": "primitive", "name": "number" },
 *     { "kind": "primitive", "name": "number" }
 *   ],
 *   "return": { "kind": "primitive", "name": "number" }
 * }
 * }</pre>
 */
public record FunctionSignature(List<Type> params, Type returnType) {

    public FunctionSignature {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (returnType == null) {
            throw new IllegalArgumentException("returnType must not be null");
        }
        params = List.copyOf(params); // 不可变
    }
}
