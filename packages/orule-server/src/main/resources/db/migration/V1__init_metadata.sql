-- V1: 元数据域（DomainType / ObjectType / AttributeType / FunctionLib）
-- RFC-0031 §3.2 重构版：Type 系统统一为 JSON 树，删除 enum 独立表

CREATE TABLE domain_type (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_code (code)
);

CREATE TABLE object_type (
    id              VARCHAR(36)  NOT NULL,
    domain_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_domain_code (domain_id, code),
    CONSTRAINT fk_object_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE TABLE attribute_type (
    id              VARCHAR(36)  NOT NULL,
    object_id       VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    data_type       VARCHAR(32)  NOT NULL,           -- kind 标签: primitive|enum|object|list|map
    type_json       JSON         NOT NULL,            -- 完整 Type 结构
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value   VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_code (object_id, code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE INDEX idx_attr_data_type ON attribute_type(data_type);

-- function_lib.signature 改为 JSON，存储完整 Type 树签名
-- 例：{"params":[{"kind":"primitive","name":"number"}, ...], "return":{"kind":"primitive","name":"number"}}
CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    signature       JSON         NOT NULL,            -- 函数签名（参数 + 返回类型的 Type 树）
    description     TEXT,
    category        VARCHAR(64),
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_code (code)
);

-- 删除：enum_type 与 enum_value 表（enum 定义内联到 attribute_type.type_json 中）
