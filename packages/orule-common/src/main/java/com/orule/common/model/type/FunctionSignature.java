package com.orule.common.model.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 *
 * <p>注：因为 {@code return} 是 Java 关键字无法作为 record 字段名，使用
 * {@link JsonProperty} 在字段和构造器参数上做 JSON ↔ Java 字段名映射
 * （JSON 用 {@code return}，Java 字段为 {@code returnType}）。
 */
public record FunctionSignature(
        @JsonProperty("params") List<Type> params,
        @JsonProperty("return") Type returnType
) {

    @JsonCreator
    public FunctionSignature {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (returnType == null) {
            throw new IllegalArgumentException("returnType must not be null");
        }
        params = List.copyOf(params);
    }

    /** 静态工厂：从已校验参数构造 */
    public static FunctionSignature of(List<Type> params, Type returnType) {
        return new FunctionSignature(params, returnType);
    }
}
