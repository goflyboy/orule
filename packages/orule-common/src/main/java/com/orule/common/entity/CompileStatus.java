package com.orule.common.entity;

/**
 * RuleArtifact 编译状态枚举（RFC-0019 §3.6）。
 *
 * <p>存储：{@code VARCHAR(16)}，枚举名直接落库（不依赖 ordinal）。
 *
 * <p>转换关系：
 * <ul>
 *   <li>Skill 编译成功 → SUCCESS</li>
 *   <li>Skill 编译失败 → FAILED（compileLog 非空）</li>
 *   <li>Skill 产物未到位 → PENDING（默认）</li>
 * </ul>
 */
public enum CompileStatus {
    /** 编译成功，groovy_source 已落库 */
    SUCCESS,
    /** 编译失败，groovy_source 为空，compileLog 非空 */
    FAILED,
    /** 等待 Skill 编译产物到位 */
    PENDING
}
