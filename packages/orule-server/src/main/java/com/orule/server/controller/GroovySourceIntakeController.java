package com.orule.server.controller;

import com.orule.common.dto.GroovySourceIntakeRequest;
import com.orule.common.dto.GroovySourceIntakeResponse;
import com.orule.common.dto.Result;
import com.orule.server.service.GroovySourceIntakeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收本地 Skill 编译产物的 MCP 端点。
 *
 * <p>MCP 工具名：{@code orule.rule.publishCompiledGroovy}
 * <p>调用方：orule-llm-studio Skill #2 simplets-to-groovy
 *
 * <p><b>替代</b> RFC-0019 §3.6 原 CompileService（服务端编译）。本控制器不做任何 DSL 校验。
 *
 * <p>端点契约（与 RFC-0019 §3.7.1 一致）：
 * <pre>
 * POST /mcp/tools/orule.rule.publishCompiledGroovy?ruleVersionId=rv-001
 * Content-Type: application/json
 *
 * {
 *   "groovySource": "def execute(Map context) { ... }",
 *   "sha256": "abc123...",
 *   "compileLog": null,
 *   "durationMs": 42
 * }
 * </pre>
 *
 * <p>错误码：
 * <ul>
 *   <li>200 OK + Result&lt;GroovySourceIntakeResponse&gt;：落库成功（compileStatus 可能为 SUCCESS/FAILED）</li>
 *   <li>400 Bad Request：字段非空 / SHA256 mismatch</li>
 *   <li>404 Not Found：ruleVersionId 不存在</li>
 * </ul>
 */
@RestController
@RequestMapping("/mcp/tools")
@RequiredArgsConstructor
@Slf4j
public class GroovySourceIntakeController {

    private final GroovySourceIntakeService intakeService;

    /**
     * MCP 工具 orule.rule.publishCompiledGroovy 入口。
     *
     * @param ruleVersionId RuleVersion 主键（query param，与 RFC-0019 §3.7.1 路径参数约定一致）
     * @param req           Skill 编译产物
     */
    @PostMapping("/orule.rule.publishCompiledGroovy")
    public Result<GroovySourceIntakeResponse> publishCompiledGroovy(
        @RequestParam("ruleVersionId") String ruleVersionId,
        @Valid @RequestBody GroovySourceIntakeRequest req
    ) {
        log.debug("MCP intake call: ruleVersionId={} sha256={}", ruleVersionId, req.sha256());
        return Result.success(intakeService.intake(ruleVersionId, req));
    }
}
