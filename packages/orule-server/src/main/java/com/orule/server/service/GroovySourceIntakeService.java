package com.orule.server.service;

import com.orule.common.dto.GroovySourceIntakeRequest;
import com.orule.common.dto.GroovySourceIntakeResponse;
import com.orule.common.entity.CompileStatus;
import com.orule.common.entity.RuleArtifact;
import com.orule.common.entity.RuleVersion;
import com.orule.common.exception.NotFoundException;
import com.orule.common.storage.ArtifactStorage;
import com.orule.common.storage.UploadResult;
import com.orule.server.repository.RuleArtifactRepository;
import com.orule.server.repository.RuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 接收本地 Skill 编译产物（Groovy 源码），落 RuleVersion + RuleArtifact + ArtifactStorage。
 *
 * <p><b>不做</b>任何 TS / SimpleTS / Groovy 校验 — 仅做 SHA256 一致性校验 + 字段非空校验。
 * 编译期校验由 orule-llm-studio Skill #2 simplets-to-groovy 负责。
 *
 * <p>运行期校验由 orule-runtime Groovy 沙箱（RFC-0020 SecureASTCustomizer）兜底。
 *
 * <p><b>失败语义</b>：compileLog 非空 = 失败 → 保留旧 groovySource（不覆盖），不调用 storage.upload，
 * 但仍然记录 RuleArtifact（compileStatus=FAILED + compileLog 完整内容），便于审计。
 *
 * <p><b>upsert 语义</b>：因 {@code rule_artifact.rule_version_id} 唯一约束，
 * 同一 RuleVersion 多次 intake 是 replace（每次都新建 RuleArtifact.id）。
 *
 * @see <a href="https://github.com/orule/orule/blob/main/docs/rfcs/RFC-0019-SimpleTS转Groovy代码生成器.md#36-落库服务mcp-端点">RFC-0019 §3.6</a>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroovySourceIntakeService {

    private final RuleVersionRepository versionRepo;
    private final RuleArtifactRepository artifactRepo;
    private final ArtifactStorage storage;

    /**
     * 主入口：接收一次 Skill 编译产物落库请求。
     *
     * @param ruleVersionId RuleVersion 主键
     * @param req           Skill 编译产物
     * @return 落库结果（含 artifactId + compileStatus + storedAt）
     * @throws NotFoundException   ruleVersionId 不存在
     * @throws IllegalArgumentException SHA256 mismatch / 字段非空校验失败
     */
    @Transactional
    public GroovySourceIntakeResponse intake(String ruleVersionId, GroovySourceIntakeRequest req) {
        log.info("Intake start: ruleVersionId={} durationMs={} compileLogIsNull={}",
            ruleVersionId, req.durationMs(), req.compileLog() == null);

        // 1. 字段非空校验（业务语义，超出 Jakarta validation 的 NotBlank）
        if (ruleVersionId == null || ruleVersionId.isBlank()) {
            throw new IllegalArgumentException("ruleVersionId 不能为空");
        }
        if (req.sha256() == null || req.sha256().isBlank()) {
            throw new IllegalArgumentException("sha256 不能为空");
        }

        // 2. 查 RuleVersion（@Transactional 内 lazy load rule 安全）
        RuleVersion version = versionRepo.findById(ruleVersionId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", ruleVersionId));

        // 3. SHA256 校验
        String actual = sha256Hex(req.groovySource());
        if (!actual.equalsIgnoreCase(req.sha256())) {
            throw new IllegalArgumentException(
                "sha256 mismatch: declared=" + req.sha256() + " actual=" + actual);
        }

        // 4. 判定状态：compileLog 为空 / 空白 = 成功
        boolean success = req.compileLog() == null || req.compileLog().isBlank();
        CompileStatus status = success ? CompileStatus.SUCCESS : CompileStatus.FAILED;

        // 4.1 成功时：groovySource 必须非空；失败时：compileLog 必须非空
        if (success && (req.groovySource() == null || req.groovySource().isBlank())) {
            throw new IllegalArgumentException(
                "compileLog 为空（视为成功）时 groovySource 必须非空");
        }
        if (!success && (req.compileLog() == null || req.compileLog().isBlank())) {
            throw new IllegalArgumentException(
                "compileLog 与 groovySource 不能同时为空");
        }

        // 5. 成功时：落 RuleVersion.groovy_source（失败保留旧值，便于审计"上次成功的产物"）
        if (success) {
            version.setGroovySource(req.groovySource());
            versionRepo.save(version);
        } else {
            log.warn("Intake FAILED: ruleVersionId={} compileLog.length={}",
                ruleVersionId, req.compileLog().length());
        }

        // 6. 上传 ArtifactStorage（仅成功）
        UploadResult upload = success
            ? storage.upload(buildArtifactKey(version), req.groovySource().getBytes(StandardCharsets.UTF_8))
            : emptyUploadResult();

        // 7. 落 RuleArtifact（成功 / 失败均记录；upsert 语义由 RuleArtifact.ruleVersion 一对一带 unique 约束保障）
        String artifactId = UUID.randomUUID().toString();
        RuleArtifact artifact = RuleArtifact.builder()
            .id(artifactId)
            .ruleVersion(version)
            .storageType(upload.storageType())
            .storagePath(upload.storagePath())
            .storageUrl(upload.url())
            .fileSize(upload.fileSize())
            .sha256(req.sha256())
            .compileStatus(status.name())
            .compileLog(req.compileLog())
            .build();
        artifactRepo.save(artifact);

        // 8. 构造响应
        Instant storedAt = Instant.now();
        log.info("Intake done: ruleVersionId={} artifactId={} status={}",
            ruleVersionId, artifactId, status);

        return new GroovySourceIntakeResponse(
            ruleVersionId, artifactId, status.name(), storedAt);
    }

    /**
     * 构建 ArtifactStorage key。
     * 格式：{@code rules/<ruleCode>/v<version>.groovy}
     */
    private String buildArtifactKey(RuleVersion version) {
        // rule 是 LAZY 但 @Transactional 内可安全访问
        String ruleCode = version.getRule().getCode();
        int ver = version.getVersion();
        return String.format("rules/%s/v%d.groovy", ruleCode, ver);
    }

    /**
     * 失败时的占位 UploadResult（不调用 storage.upload）。
     */
    private UploadResult emptyUploadResult() {
        return new UploadResult("", "", 0L, GroovySourceIntakeRequest.EMPTY_STRING_SHA256, "");
    }

    /**
     * 计算 SHA-256 hex（小写）。
     */
    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
