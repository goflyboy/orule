package com.orule.common.whitelist;

import java.time.Instant;
import java.util.List;

/**
 * SimpleTS 白名单（消费侧镜像）。
 *
 * <p><b>数据流向</b>：源端 TS（{@code orule-llm-studio/simplets-whitelist.ts}）
 * → 构建期单向同步（{@code scripts/sync-whitelist.mjs}）
 * → 本 JSON（{@code orule-common/src/main/resources/simplets-whitelist.json}）
 * → 启动期 {@link SimpletsWhitelistLoader#fromClasspath()} 加载
 * → orule-runtime Groovy 沙箱 + 校验逻辑 消费。
 *
 * <p><b>Schema 来源</b>：本 record 字段必须与 orule-llm-studio 的
 * {@code exportToJSON()} 返回结构 100% 一致。任何字段新增 / 删除 / 重命名
 * 必须在两侧同步修改，并 bump version。
 *
 * <p><b>字段稳定原则</b>：新增字段必须可空；删除字段需走 RFC。
 *
 * @see <a href="https://github.com/orule/orule/blob/main/docs/rfcs/RFC-0018-SimpleTS解析器.md#310-simplets-白名单单一来源">RFC-0018 §3.10</a>
 * @see <a href="https://github.com/orule/orule/blob/main/docs/adr/ADR-012-Aprime-本地Skill编译与MCPLangLib库.md">ADR-012-Aprime</a>
 */
public record SimpletsWhitelist(
    /** 白名单版本（semver 字符串），源端 TS 的 {@code WHITELIST_VERSION}。 */
    String version,

    /** 本次生成时间（ISO-8601 UTC，源端 TS 在 exportToJSON 时取 now）。 */
    Instant updatedAt,

    /** 允许的 SimpleTS 语句类型（如 IfStmt / ForStmt）。 */
    List<String> statementKinds,

    /** 允许的 SimpleTS 表达式类型（如 BinaryExpr / MemberAccess）。 */
    List<String> expressionKinds,

    /** 允许的二元运算符（如 {@code +} {@code ==} {@code &&}）。 */
    List<String> binaryOps,

    /** 允许的一元运算符（如 {@code -} {@code !}）。 */
    List<String> unaryOps,

    /** SimpleTS 全局函数（无类前缀，Groovy 沙箱侧允许的方法名集合）。 */
    List<String> globalFunctions,

    /** SimpleTS 全局函数的方法签名（沙箱 deny-unless-allow 依据）。 */
    List<MethodSignature> methodSignatures
) {

    /**
     * 方法签名 record。
     *
     * <p>示例：
     * <pre>
     * { "methodName": "now", "returnType": "java.time.LocalDateTime", "paramTypes": [] }
     * </pre>
     */
    public record MethodSignature(
        String methodName,
        String returnType,
        List<String> paramTypes
    ) {}
}
