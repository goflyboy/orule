-- V2: 规则域（RuleSet / Rule / RuleVersion）
-- RFC-0014 §3.3

CREATE TABLE rule_set (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    domain_id       VARCHAR(36)  NOT NULL,
    owner_code      VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'MAINTENANCE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ruleset_code (code),
    CONSTRAINT fk_ruleset_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE TABLE rule (
    id              VARCHAR(36)  NOT NULL,
    rule_set_id     VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    sort_order      INT          NOT NULL DEFAULT 0,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_set_code (rule_set_id, code),
    CONSTRAINT fk_rule_ruleset FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
);

CREATE TABLE rule_version (
    id              VARCHAR(36)  NOT NULL,
    rule_id         VARCHAR(36)  NOT NULL,
    version         INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'MAINTENANCE',
    description     TEXT,
    simple_ts       TEXT,
    groovy_source   TEXT,
    changelog       TEXT,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP    NULL,
    retired_at      TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_version (rule_id, version),
    CONSTRAINT fk_rule_version_rule FOREIGN KEY (rule_id) REFERENCES rule(id)
);

CREATE INDEX idx_rule_version_status ON rule_version(status);
CREATE INDEX idx_rule_version_published ON rule_version(rule_id, status);
