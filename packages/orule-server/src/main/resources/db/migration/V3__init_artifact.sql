-- V3: 制品域（RuleArtifact / RuleSetArtifact / ArtifactStorageConfig）
-- RFC-0014 §3.4

CREATE TABLE rule_artifact (
    id              VARCHAR(36)  NOT NULL,
    rule_version_id VARCHAR(36)  NOT NULL,
    storage_type    VARCHAR(16)  NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    storage_url     VARCHAR(512),
    file_size       BIGINT       NOT NULL DEFAULT 0,
    sha256          VARCHAR(64)  NOT NULL,
    compile_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    compile_log     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_artifact_version (rule_version_id),
    CONSTRAINT fk_rule_artifact_version FOREIGN KEY (rule_version_id) REFERENCES rule_version(id)
);

CREATE TABLE rule_set_artifact (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    version         INT          NOT NULL,
    storage_type    VARCHAR(16)  NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    storage_url     VARCHAR(512),
    file_size       BIGINT       NOT NULL DEFAULT 0,
    sha256          VARCHAR(64)  NOT NULL,
    rule_count      INT          NOT NULL DEFAULT 0,
    included_versions TEXT,
    concurrency     INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ruleset_artifact_version (rule_set_id, version),
    CONSTRAINT fk_ruleset_artifact_set FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
);

CREATE TABLE artifact_storage_config (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    storage_type    VARCHAR(16)  NOT NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    config_json     TEXT         NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_config_code (code)
);
