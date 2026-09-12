-- V1: 元数据域（DomainType / ObjectType / AttributeType / FunctionLib）
-- RFC-0032 §3.2 重构版：
--   1) 4 表字段名 code → program_code（语义清晰，强调"可编程代码"）
--   2) attribute_type.type_json 拆为 3 列（data_type + sub_data_type_program_code + _2）
--   3) object_type 新增 kind (CLASS|ENUM) + enum_values (JSON)
--   4) enum 不再独立表，合入 object_type(kind=ENUM)

CREATE TABLE domain_type (
    id              VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    owner_code      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_program_code (program_code)
);

CREATE TABLE object_type (
    id              VARCHAR(36)  NOT NULL,
    domain_id       VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'CLASS',  -- CLASS | ENUM
    enum_values     JSON,                                   -- 仅 kind=ENUM 时使用
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_domain_program_code (domain_id, program_code),
    CONSTRAINT fk_object_domain FOREIGN KEY (domain_id) REFERENCES domain_type(id)
);

CREATE INDEX idx_object_kind ON object_type(kind);

CREATE TABLE attribute_type (
    id                          VARCHAR(36)  NOT NULL,
    object_id                   VARCHAR(36)  NOT NULL,
    program_code                VARCHAR(64)  NOT NULL,
    name                        VARCHAR(128) NOT NULL,
    data_type                   VARCHAR(32)  NOT NULL,           -- primitive|object|list|map
    sub_data_type_program_code  VARCHAR(64),                     -- primitive.name 或 object/list/map 目标 programCode
    sub_data_type_program_code2 VARCHAR(64),                     -- 仅 map 使用（value 类型 programCode）
    is_required                 BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value               VARCHAR(255),
    description                 TEXT,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_object_program_code (object_id, program_code),
    CONSTRAINT fk_attr_object FOREIGN KEY (object_id) REFERENCES object_type(id)
);

CREATE INDEX idx_attr_data_type ON attribute_type(data_type);

-- function_lib.signature 保持 JSON（沿用 RFC-0031；技术债 TD-002）
CREATE TABLE function_lib (
    id              VARCHAR(36)  NOT NULL,
    program_code    VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    signature       JSON         NOT NULL,
    description     TEXT,
    category        VARCHAR(64),
    is_builtin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_func_program_code (program_code)
);
