package com.orule.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * MCP 工具 {@code orule.rule.publishCompiledGroovy} 入参。
 * 由 orule-llm-studio Skill #2 simplets-to-groovy 产出。
 *
 * <p>字段稳定原则：新增字段必须可空；删除字段需走 RFC。
 *
 * @see <a href="https://github.com/orule/orule/blob/main/docs/rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md#36-落库服务mcp-端点">RFC-0019 §3.6</a>
 */
public record GroovySourceIntakeRequest(

    /**
     * 编译产物 Groovy 源码。
     * 失败时为空串（Skill #2 约定）。
     * 成功时最大 100 KB（与 RFC-0020 §3.3 MAX_SCRIPT_LENGTH 一致）。
     *
     * <p>校验语义：失败时允许空串；成功时由 service 层校验非空。
     */
    @Size(max = 100_000)
    String groovySource,

    /**
     * 编译失败时的错误信息模板（RFC-0018 §3.8）。
     * 成功时为 null。
     */
    String compileLog,

    /**
     * sha256(groovySource)，服务端校验一致性。
     * 失败时为 sha256("") = "e3b0c44..."。
     */
    @NotBlank
    String sha256,

    /**
     * Skill 端编译耗时（毫秒），审计用。
     */
    @Min(0)
    long durationMs
) {
    /**
     * SHA-256("") 的标准值，用于失败用例占位。
     */
    public static final String EMPTY_STRING_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 默认构造器：用于 JSON 反序列化。 */
    public GroovySourceIntakeRequest {
        // record canonical constructor（Jakarta validation 自动校验）
    }
}
