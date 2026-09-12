package com.orule.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orule.common.dto.GroovySourceIntakeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GroovySourceIntakeController 集成测试（RFC-0019 §3.7 + ADR-012-Aprime §5）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>成功用例：200 + Result 包装 + artifactId + compileStatus=SUCCESS</li>
 *   <li>失败用例（compileLog 非空）：200 + compileStatus=FAILED（落库但不更新 groovy_source）</li>
 *   <li>SHA256 mismatch：400</li>
 *   <li>ruleVersionId 不存在：404</li>
 *   <li>@NotBlank 触发：groovySource 空 / sha256 空 → 400</li>
 * </ul>
 *
 * <p>前置：复用 {@code application-test.yml}（H2 内存 + 临时 storage 目录）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GroovySourceIntakeControllerTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ObjectMapper om;
    private MockMvc mvc;

    @PostConstruct
    void init() {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    @DisplayName("成功：完整落库 + 端到端 200 + Result + compileStatus=SUCCESS")
    void publish_success() throws Exception {
        String groovy = "def execute(Map context) { return context }";
        String sha256 = sha256(groovy);
        var req = new GroovySourceIntakeRequest(groovy, null, sha256, 42L);

        String ruleVersionId = createRuleVersion();

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.ruleVersionId").value(ruleVersionId))
            .andExpect(jsonPath("$.data.compileStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.artifactId").exists())
            .andExpect(jsonPath("$.data.storedAt").exists());
    }

    @Test
    @DisplayName("失败：compileLog 非空 → 200 + compileStatus=FAILED")
    void publish_failed() throws Exception {
        String groovy = "";
        String sha256 = sha256(groovy);
        String compileLog = "TSS 编译失败:\n  ✗ 第 3 行 第 5 列: 未声明的标识符 'invoice'";
        var req = new GroovySourceIntakeRequest(groovy, compileLog, sha256, 35L);

        String ruleVersionId = createRuleVersion();

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.compileStatus").value("FAILED"))
            .andExpect(jsonPath("$.data.artifactId").exists());
    }

    @Test
    @DisplayName("SHA256 mismatch → 400")
    void publish_sha256Mismatch() throws Exception {
        String ruleVersionId = createRuleVersion();
        var req = new GroovySourceIntakeRequest(
            "def execute(Map c) {}", null,
            "0000000000000000000000000000000000000000000000000000000000000000", 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sha256")));
    }

    @Test
    @DisplayName("ruleVersionId 不存在 → 404")
    void publish_ruleVersionNotFound() throws Exception {
        String groovy = "def execute(Map c) {}";
        var req = new GroovySourceIntakeRequest(groovy, null, sha256(groovy), 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", "rv-does-not-exist-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("@NotBlank 触发：groovySource 空 → 400")
    void publish_blankGroovySource() throws Exception {
        String ruleVersionId = createRuleVersion();
        var req = new GroovySourceIntakeRequest("", null, sha256(""), 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("@NotBlank 触发：sha256 空 → 400")
    void publish_blankSha256() throws Exception {
        String ruleVersionId = createRuleVersion();
        var req = new GroovySourceIntakeRequest("def execute() {}", null, "", 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ruleVersionId 缺失 → 400")
    void publish_missingRuleVersionId() throws Exception {
        var req = new GroovySourceIntakeRequest("def execute() {}", null, sha256("x"), 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("groovySource 超 100KB → 400 (@Size 触发)")
    void publish_tooLargeGroovySource() throws Exception {
        String ruleVersionId = createRuleVersion();
        String big = "a".repeat(101 * 1024);  // 101 KB
        var req = new GroovySourceIntakeRequest(big, null, sha256(big), 10L);

        mvc.perform(post("/mcp/tools/orule.rule.publishCompiledGroovy")
                .param("ruleVersionId", ruleVersionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    // === Helper：建一个完整的 domain → ruleset → rule → rule_version 链 ===
    // 用 counter 保证 code 唯一（@Transactional 内多次 create 否则触发 uk 冲突）

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private String createRuleVersion() throws Exception {
        int n = COUNTER.incrementAndGet();
        String tag = "T" + n + "_" + UUID.randomUUID().toString().substring(0, 4);
        // domain
        String domainId = createDomain("ORDER_" + tag);
        // ruleset
        String ruleSetId = createRuleSet("ORDER_SET_" + tag, domainId);
        // rule
        String ruleId = createRule(ruleSetId, "ORDER_DISCOUNT_" + tag);
        // rule_version
        return createRuleVersion(ruleId);
    }

    private String createDomain(String programCode) throws Exception {
        String body = """
            {"programCode":"%s","name":"%s Domain","description":"d","ownerCode":"test"}
            """.formatted(programCode, programCode);
        var res = mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createRuleSet(String code, String domainId) throws Exception {
        String body = """
            {"code":"%s","name":"%s","description":"d",
             "domainId":"%s","ownerCode":"test"}
            """.formatted(code, code, domainId);
        var res = mvc.perform(post("/api/v1/rule-sets")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createRule(String ruleSetId, String code) throws Exception {
        String body = """
            {"ruleSetId":"%s","code":"%s","name":"%s",
             "description":"d","sortOrder":1,"ownerCode":"test"}
            """.formatted(ruleSetId, code, code);
        var res = mvc.perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createRuleVersion(String ruleId) throws Exception {
        String body = """
            {"ruleId":"%s","description":"v1"}
            """.formatted(ruleId);
        var res = mvc.perform(post("/api/v1/rule-versions")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
