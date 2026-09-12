package com.orule.server.service;

import com.orule.common.dto.GroovySourceIntakeRequest;
import com.orule.common.dto.GroovySourceIntakeResponse;
import com.orule.common.entity.Rule;
import com.orule.common.entity.RuleArtifact;
import com.orule.common.entity.RuleVersion;
import com.orule.common.exception.NotFoundException;
import com.orule.common.storage.ArtifactStorage;
import com.orule.common.storage.UploadResult;
import com.orule.server.repository.RuleArtifactRepository;
import com.orule.server.repository.RuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GroovySourceIntakeService 单元测试（RFC-0019 §3.6 + ADR-012-Aprime §5）。
 *
 * <p>Mockito 模拟 RuleVersionRepository / RuleArtifactRepository / ArtifactStorage，
 * 不启动 Spring 上下文。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>成功用例：完整落库 + Artifact 上传</li>
 *   <li>失败用例：compileLog 非空 → FAILED，不上传 storage</li>
 *   <li>SHA256 mismatch → IllegalArgumentException</li>
 *   <li>groovySource 缺失（@NotBlank 由 controller 层处理，这里不重复）</li>
 *   <li>ruleVersionId 不存在 → NotFoundException</li>
 *   <li>ruleVersionId 缺失 → IllegalArgumentException</li>
 *   <li>sha256 缺失 → IllegalArgumentException</li>
 *   <li>upsert 语义：每次 intake 都生成新 artifactId</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class GroovySourceIntakeServiceTest {

    @Mock private RuleVersionRepository versionRepo;
    @Mock private RuleArtifactRepository artifactRepo;
    @Mock private ArtifactStorage storage;

    @InjectMocks private GroovySourceIntakeService service;

    private static final String RULE_VERSION_ID = "rv-001";
    private static final String RULE_CODE = "ORDER_DISCOUNT";
    private static final int VERSION = 3;

    private RuleVersion mockVersion;

    @BeforeEach
    void setUp() {
        mockVersion = RuleVersion.builder()
            .id(RULE_VERSION_ID)
            .rule(Rule.builder().id("r-001").code(RULE_CODE).build())
            .version(VERSION)
            .status("MAINTENANCE")
            .groovySource("// old")
            .build();
        // 用 lenient 模式（不是每个 test 都需要这个 stub）
        org.mockito.Mockito.lenient()
            .when(versionRepo.findById(RULE_VERSION_ID))
            .thenReturn(Optional.of(mockVersion));
    }

    // === 1. 成功用例 ===

    @Test
    @DisplayName("成功：完整落库 + Artifact 上传")
    void intake_success() {
        String groovy = "def execute(Map context) { return context }";
        String sha256 = sha256(groovy);
        var req = new GroovySourceIntakeRequest(groovy, null, sha256, 42L);

        when(storage.upload(eq("rules/" + RULE_CODE + "/v" + VERSION + ".groovy"), any(byte[].class)))
            .thenReturn(new UploadResult(
                "rules/" + RULE_CODE + "/v" + VERSION + ".groovy",
                "http://localhost:8080/api/v1/artifacts/rules/" + RULE_CODE + "/v" + VERSION + ".groovy",
                (long) groovy.getBytes(StandardCharsets.UTF_8).length,
                sha256,
                "local"));

        GroovySourceIntakeResponse resp = service.intake(RULE_VERSION_ID, req);

        // 1. version.groovySource 已更新
        assertThat(mockVersion.getGroovySource()).isEqualTo(groovy);
        verify(versionRepo, times(1)).save(mockVersion);

        // 2. storage.upload 被调用
        verify(storage, times(1)).upload(anyString(), any(byte[].class));

        // 3. artifact 已 save，参数正确
        ArgumentCaptor<RuleArtifact> captor = ArgumentCaptor.forClass(RuleArtifact.class);
        verify(artifactRepo, times(1)).save(captor.capture());
        RuleArtifact saved = captor.getValue();
        assertThat(saved.getRuleVersion()).isEqualTo(mockVersion);
        assertThat(saved.getCompileStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getCompileLog()).isNull();
        assertThat(saved.getSha256()).isEqualTo(sha256);

        // 4. 响应正确
        assertThat(resp.ruleVersionId()).isEqualTo(RULE_VERSION_ID);
        assertThat(resp.artifactId()).isEqualTo(saved.getId());
        assertThat(resp.compileStatus()).isEqualTo("SUCCESS");
        assertThat(resp.storedAt()).isNotNull();
    }

    // === 2. 失败用例 ===

    @Test
    @DisplayName("失败：compileLog 非空 → FAILED，不调用 storage")
    void intake_failed() {
        String groovy = "";
        String sha256 = sha256(groovy);
        String compileLog = "TSS 编译失败:\n\n  ✗ 第 3 行 第 5 列: 未声明的标识符 'invoice'";
        var req = new GroovySourceIntakeRequest(groovy, compileLog, sha256, 35L);

        GroovySourceIntakeResponse resp = service.intake(RULE_VERSION_ID, req);

        // 1. version.groovySource 保留旧值
        assertThat(mockVersion.getGroovySource()).isEqualTo("// old");
        verify(versionRepo, never()).save(any());

        // 2. storage.upload 不被调用
        verify(storage, never()).upload(anyString(), any(byte[].class));

        // 3. artifact 仍被记录（用于审计失败原因）
        ArgumentCaptor<RuleArtifact> captor = ArgumentCaptor.forClass(RuleArtifact.class);
        verify(artifactRepo, times(1)).save(captor.capture());
        RuleArtifact saved = captor.getValue();
        assertThat(saved.getCompileStatus()).isEqualTo("FAILED");
        assertThat(saved.getCompileLog()).isEqualTo(compileLog);

        assertThat(resp.compileStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("失败：compileLog 全空白 → 视为成功")
    void intake_compileLogBlankTreatedAsSuccess() {
        String groovy = "def execute(Map context) {}";
        String sha256 = sha256(groovy);
        var req = new GroovySourceIntakeRequest(groovy, "   \n\t  ", sha256, 10L);

        when(storage.upload(anyString(), any(byte[].class)))
            .thenReturn(new UploadResult("k", null, (long) groovy.length(), sha256, "local"));

        GroovySourceIntakeResponse resp = service.intake(RULE_VERSION_ID, req);

        assertThat(resp.compileStatus()).isEqualTo("SUCCESS");
    }

    // === 3. SHA256 mismatch ===

    @Test
    @DisplayName("SHA256 mismatch → IllegalArgumentException")
    void intake_sha256Mismatch() {
        var req = new GroovySourceIntakeRequest(
            "def execute(Map context) {}", null,
            "0000000000000000000000000000000000000000000000000000000000000000",
            10L);

        assertThatThrownBy(() -> service.intake(RULE_VERSION_ID, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sha256 mismatch");

        verify(versionRepo, never()).save(any());
        verify(storage, never()).upload(anyString(), any(byte[].class));
        verify(artifactRepo, never()).save(any());
    }

    // === 4. ruleVersionId 不存在 ===

    @Test
    @DisplayName("ruleVersionId 不存在 → NotFoundException")
    void intake_ruleVersionNotFound() {
        when(versionRepo.findById("rv-missing")).thenReturn(Optional.empty());
        var req = new GroovySourceIntakeRequest("x", null, sha256("x"), 10L);

        assertThatThrownBy(() -> service.intake("rv-missing", req))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("RuleVersion");
    }

    // === 5. ruleVersionId 缺失 ===

    @Test
    @DisplayName("ruleVersionId 缺失 → IllegalArgumentException")
    void intake_ruleVersionIdMissing() {
        var req = new GroovySourceIntakeRequest("x", null, sha256("x"), 10L);

        assertThatThrownBy(() -> service.intake(null, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ruleVersionId");

        assertThatThrownBy(() -> service.intake("", req))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.intake("   ", req))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // === 6. sha256 缺失 ===

    @Test
    @DisplayName("sha256 缺失 → IllegalArgumentException")
    void intake_sha256Missing() {
        var req = new GroovySourceIntakeRequest("x", null, null, 10L);

        assertThatThrownBy(() -> service.intake(RULE_VERSION_ID, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sha256");

        var req2 = new GroovySourceIntakeRequest("x", null, "", 10L);
        assertThatThrownBy(() -> service.intake(RULE_VERSION_ID, req2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // === 7. upsert 语义 ===

    @Test
    @DisplayName("upsert 语义：同一 RuleVersion 多次 intake，每次都生成新 artifactId")
    void intake_upsert() {
        String groovy1 = "def execute(Map c) { return c }";
        String groovy2 = "def execute(Map c) { c.discount = 30; return c }";
        var req1 = new GroovySourceIntakeRequest(groovy1, null, sha256(groovy1), 10L);
        var req2 = new GroovySourceIntakeRequest(groovy2, null, sha256(groovy2), 20L);

        when(storage.upload(anyString(), any(byte[].class)))
            .thenAnswer(inv -> new UploadResult("k", null, 100L, "sha", "local"));

        GroovySourceIntakeResponse resp1 = service.intake(RULE_VERSION_ID, req1);
        GroovySourceIntakeResponse resp2 = service.intake(RULE_VERSION_ID, req2);

        // 不同 artifactId
        assertThat(resp1.artifactId()).isNotEqualTo(resp2.artifactId());
        // version.groovySource 被第二次覆盖
        assertThat(mockVersion.getGroovySource()).isEqualTo(groovy2);
        // 两次都触发 save（upsert 语义）
        verify(artifactRepo, times(2)).save(any(RuleArtifact.class));
    }

    // === Helpers ===

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
