package com.orule.common.dto;

import java.time.Instant;

/**
 * MCP 工具 {@code orule.rule.publishCompiledGroovy} 出参。
 *
 * @see GroovySourceIntakeRequest
 */
public record GroovySourceIntakeResponse(

    /** RuleVersion 主键（与入参路径一致）。 */
    String ruleVersionId,

    /** RuleArtifact 主键（UUID，新生成）。 */
    String artifactId,

    /** SUCCESS / FAILED / PENDING。 */
    String compileStatus,

    /** 服务端落库时间。 */
    Instant storedAt
) {}
