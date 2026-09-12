-- V5: 种子数据（内置 DomainType / ObjectType / AttributeType / FunctionLib 示例）
-- RFC-0031 §3.2 重构版：enum 全部内联到 attribute_type.type_json

INSERT INTO domain_type (id, code, name, description, owner_code) VALUES
  ('dom-order', 'ORDER', 'ORDER Domain', 'Order related rules', 'system'),
  ('dom-customer', 'CUSTOMER', 'CUSTOMER Domain', 'Customer related rules', 'system');

INSERT INTO object_type (id, domain_id, code, name, description) VALUES
  ('obj-customer', 'dom-customer', 'Customer', 'Customer Entity', 'Customer metadata'),
  ('obj-order',    'dom-order',    'Order',    'Order Entity',    'Order metadata');

-- 注意：每个 attribute 的 type_json 必须包含完整的 type 结构
INSERT INTO attribute_type (id, object_id, code, name, data_type, type_json, is_required) VALUES
  ('attr-cust-id',
   'obj-customer', 'id', 'Customer ID', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-cust-name',
   'obj-customer', 'name', 'Customer Name', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-cust-tier',
   'obj-customer', 'tier', 'Customer Tier', 'enum',
   JSON_OBJECT(
     'kind', 'enum',
     'enumCode', 'CustomerTier',
     'values', JSON_ARRAY(
       JSON_OBJECT('code', 'VIP',    'label', 'VIP Customer',    'sortOrder', 1),
       JSON_OBJECT('code', 'GOLD',   'label', 'Gold Customer',   'sortOrder', 2),
       JSON_OBJECT('code', 'SILVER', 'label', 'Silver Customer', 'sortOrder', 3),
       JSON_OBJECT('code', 'BRONZE', 'label', 'Bronze Customer', 'sortOrder', 4)
     )
   ),
   TRUE),
  ('attr-order-id',
   'obj-order', 'id', 'Order ID', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'string'),
   TRUE),
  ('attr-order-total',
   'obj-order', 'totalAmount', 'Total Amount', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'number'),
   TRUE),
  ('attr-order-discount',
   'obj-order', 'discount', 'Discount', 'primitive',
   JSON_OBJECT('kind', 'primitive', 'name', 'number'),
   FALSE),
  ('attr-order-prices',
   'obj-order', 'itemPrices', 'Item Prices', 'list',
   JSON_OBJECT(
     'kind', 'list',
     'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   FALSE),
  ('attr-order-tax',
   'obj-order', 'taxBreakdown', 'Tax Breakdown', 'map',
   JSON_OBJECT(
     'kind', 'map',
     'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
     'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   FALSE);

INSERT INTO function_lib (id, code, name, signature, description, category, is_builtin) VALUES
  ('func-max',
   'max', 'Maximum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return the larger of two values', 'math', TRUE),
  ('func-min',
   'min', 'Minimum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return the smaller of two values', 'math', TRUE),
  ('func-abs',
   'abs', 'Absolute Value',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return absolute value', 'math', TRUE),
  ('func-round',
   'round', 'Round',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Round to n decimal places', 'math', TRUE),
  ('func-upper',
   'upper', 'Upper Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')
   ),
   'Convert to upper case', 'string', TRUE),
  ('func-lower',
   'lower', 'Lower Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')
   ),
   'Convert to lower case', 'string', TRUE),
  ('func-contains',
   'contains', 'Contains',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'string'),
       JSON_OBJECT('kind', 'primitive', 'name', 'string')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')
   ),
   'Check if string contains substring', 'string', TRUE),
  ('func-now',
   'now', 'Current Time',
   JSON_OBJECT('params', JSON_ARRAY(), 'return', JSON_OBJECT('kind', 'primitive', 'name', 'date')),
   'Return current timestamp', 'date', TRUE),
  ('func-days',
   'days', 'Days Between',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'date'),
       JSON_OBJECT('kind', 'primitive', 'name', 'date')
     ),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')
   ),
   'Return number of days between two dates', 'date', TRUE);

INSERT INTO artifact_storage_config (id, code, storage_type, is_default, config_json, is_active) VALUES
  ('storage-local', 'local-default', 'local', TRUE,
   '{"basePath":"C:/Users/Administrator/orule/data/artifacts","baseUrl":"http://localhost:8080/api/v1/artifacts"}',
   TRUE);
