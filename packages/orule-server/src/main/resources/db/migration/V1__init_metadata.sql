-- V1: 元数据域（DomainType / ObjectType / AttributeType / EnumType / FunctionLib）
-- RFC-0014 §3.2

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
    data_type       VARCHAR(32)  NOT NULL,
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value   VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_code (object_id, code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE TABLE enum_type (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enum_code (code)
);

CREATE TABLE enum_value (
    id              VARCHAR(36)  NOT NULL,
    enum_id         VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enum_value_code (enum_id, code),
    CONSTRAINT fk_enum_value_enum FOREIGN KEY (enum_id) REFERENCES enum_type(id)
);

CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    signature       VARCHAR(255) NOT NULL,
    description     TEXT,
    category        VARCHAR(64),
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_code (code)
);
