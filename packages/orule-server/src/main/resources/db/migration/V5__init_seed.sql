-- V5: 种子数据（内置 DomainType / EnumType / FunctionLib 示例）
-- RFC-0014 §3.6

INSERT INTO domain_type (id, code, name, description, owner_code) VALUES
  ('dom-order', 'ORDER', 'ORDER Domain', 'Order related rules', 'system'),
  ('dom-customer', 'CUSTOMER', 'CUSTOMER Domain', 'Customer related rules', 'system');

INSERT INTO enum_type (id, code, name, description) VALUES
  ('enum-tier', 'CustomerTier', 'Customer Tier', 'VIP / Gold / Silver / Bronze'),
  ('enum-pay', 'PayMethod', 'Payment Method', 'ALIPAY / WECHAT / CARD / CASH');

INSERT INTO enum_value (id, enum_id, code, name, sort_order) VALUES
  ('enum-tier-vip', 'enum-tier', 'VIP', 'VIP Customer', 1),
  ('enum-tier-gold', 'enum-tier', 'GOLD', 'Gold Customer', 2),
  ('enum-tier-silver', 'enum-tier', 'SILVER', 'Silver Customer', 3),
  ('enum-tier-bronze', 'enum-tier', 'BRONZE', 'Bronze Customer', 4),
  ('enum-pay-alipay', 'enum-pay', 'ALIPAY', 'Alipay', 1),
  ('enum-pay-wechat', 'enum-pay', 'WECHAT', 'WeChat Pay', 2),
  ('enum-pay-card', 'enum-pay', 'CARD', 'Credit Card', 3),
  ('enum-pay-cash', 'enum-pay', 'CASH', 'Cash', 4);

INSERT INTO function_lib (id, code, name, signature, description, category, is_builtin) VALUES
  ('func-max', 'max', 'Maximum', 'max(a, b)', 'Return the larger of two values', 'math', TRUE),
  ('func-min', 'min', 'Minimum', 'min(a, b)', 'Return the smaller of two values', 'math', TRUE),
  ('func-abs', 'abs', 'Absolute Value', 'abs(a)', 'Return absolute value', 'math', TRUE),
  ('func-round', 'round', 'Round', 'round(a, n)', 'Round to n decimal places', 'math', TRUE),
  ('func-upper', 'upper', 'Upper Case', 'upper(s)', 'Convert to upper case', 'string', TRUE),
  ('func-lower', 'lower', 'Lower Case', 'lower(s)', 'Convert to lower case', 'string', TRUE),
  ('func-contains', 'contains', 'Contains', 'contains(s, sub)', 'Check if string contains substring', 'string', TRUE),
  ('func-now', 'now', 'Current Time', 'now()', 'Return current timestamp', 'date', TRUE),
  ('func-days', 'days', 'Days Between', 'days(d1, d2)', 'Return number of days between two dates', 'date', TRUE);

INSERT INTO artifact_storage_config (id, code, storage_type, is_default, config_json, is_active) VALUES
  ('storage-local', 'local-default', 'local', TRUE, '{"basePath":"C:/Users/Administrator/orule/data/artifacts","baseUrl":"http://localhost:8080/api/v1/artifacts"}', TRUE);
