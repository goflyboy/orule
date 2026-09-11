package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "rule_artifact")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleArtifact {
    @Id
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_version_id", nullable = false, unique = true)
    private RuleVersion ruleVersion;

    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "storage_url", length = 512)
    private String storageUrl;

    @Column(name = "file_size", nullable = false)
    @Builder.Default
    private Long fileSize = 0L;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "compile_status", nullable = false, length = 16)
    @Builder.Default
    private String compileStatus = "PENDING";

    @Column(name = "compile_log", columnDefinition = "TEXT")
    private String compileLog;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
