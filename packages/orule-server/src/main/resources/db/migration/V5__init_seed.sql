-- V5: ????
-- RFC-0032 ?3.7 ????
--   1) CustomerTier ??? object_type(kind=ENUM)?values ?? enum_values
--   2) attribute_type ? 3 ????data_type + sub_data_type_program_code + sub2?
--   3) code ? program_code
-- RFC-0043?Order ?? list<Customer> / map<string, Customer>?function_lib ? List/Map ?????

INSERT INTO domain_type (id, program_code, name, description, owner_code) VALUES
  ('dom-order',    'ORDER',    'ORDER Domain',    'Order related rules',    'system'),
  ('dom-customer', 'CUSTOMER', 'CUSTOMER Domain', 'Customer related rules', 'system');

-- CustomerTier?ObjectType(kind=ENUM)
INSERT INTO object_type (id, domain_id, program_code, name, kind, enum_values) VALUES
  ('obj-customer-tier', 'dom-customer', 'CustomerTier', 'Customer Tier', 'ENUM',
   JSON_ARRAY(
     JSON_OBJECT('code', 'VIP',    'label', 'VIP ??',    'sortOrder', 1),
     JSON_OBJECT('code', 'GOLD',   'label', '????',    'sortOrder', 2),
     JSON_OBJECT('code', 'SILVER', 'label', '????',    'sortOrder', 3),
     JSON_OBJECT('code', 'BRONZE', 'label', '????',    'sortOrder', 4)
   ));

-- Customer / Order?ObjectType(kind=CLASS)
INSERT INTO object_type (id, domain_id, program_code, name, kind) VALUES
  ('obj-customer', 'dom-customer', 'Customer', 'Customer Entity', 'CLASS'),
  ('obj-order',    'dom-order',    'Order',    'Order Entity',    'CLASS');

-- Customer attributes?tier ???? programCode ?? CustomerTier (kind=ENUM)
INSERT INTO attribute_type (id, object_id, program_code, name, data_type, sub_data_type_program_code, is_required) VALUES
  ('attr-cust-id',   'obj-customer', 'id',   'Customer ID',   'primitive', 'string', TRUE),
  ('attr-cust-name', 'obj-customer', 'name', 'Customer Name', 'primitive', 'string', TRUE),
  ('attr-cust-tier', 'obj-customer', 'tier', 'Customer Tier', 'object',    'CustomerTier', TRUE);

-- Order attributes?list/map ?? sub ???????
INSERT INTO attribute_type (id, object_id, program_code, name, data_type, sub_data_type_program_code, sub_data_type_program_code2, is_required) VALUES
  ('attr-order-id',        'obj-order', 'id',            'Order ID',        'primitive', 'string',   NULL,       TRUE),
  ('attr-order-total',     'obj-order', 'totalAmount',   'Total Amount',    'primitive', 'number',   NULL,       TRUE),
  ('attr-order-discount',  'obj-order', 'discount',      'Discount',        'primitive', 'number',   NULL,       FALSE),
  ('attr-order-prices',    'obj-order', 'itemPrices',    'Item Prices',     'list',      'number',   NULL,       FALSE),
  ('attr-order-tax',       'obj-order', 'taxBreakdown',  'Tax Breakdown',   'map',       'string',   'number',   FALSE),
  ('attr-order-customers', 'obj-order', 'customers',     'Customers',       'list',      'Customer', NULL,       FALSE),
  ('attr-order-by-id',     'obj-order', 'customersById', 'Customers By Id', 'map',       'string',   'Customer', FALSE);

INSERT INTO function_lib (id, program_code, name, signature, description, category, is_builtin) VALUES
  ('func-max', 'max', 'Maximum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return the larger of two values', 'math', TRUE),
  ('func-min', 'min', 'Minimum',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return the smaller of two values', 'math', TRUE),
  ('func-abs', 'abs', 'Absolute Value',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return absolute value', 'math', TRUE),
  ('func-round', 'round', 'Round',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'number'),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Round to n decimal places', 'math', TRUE),
  ('func-upper', 'upper', 'Upper Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
   'Convert to upper case', 'string', TRUE),
  ('func-lower', 'lower', 'Lower Case',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
   'Convert to lower case', 'string', TRUE),
  ('func-contains', 'contains', 'Contains',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'string'),
       JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')),
   'Check if string contains substring', 'string', TRUE),
  ('func-now', 'now', 'Current Time',
   JSON_OBJECT('params', JSON_ARRAY(),
               'return', JSON_OBJECT('kind', 'primitive', 'name', 'date')),
   'Return current timestamp', 'date', TRUE),
  ('func-days', 'days', 'Days Between',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'primitive', 'name', 'date'),
       JSON_OBJECT('kind', 'primitive', 'name', 'date')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return number of days between two dates', 'date', TRUE),
  ('func-size', 'size', 'Size',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'list', 'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'number')),
   'Return number of elements in a list or map', 'collection', TRUE),
  ('func-get', 'get', 'Get',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'list', 'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
       JSON_OBJECT('kind', 'primitive', 'name', 'number')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
   'Get list element by index', 'collection', TRUE),
  ('func-contains-key', 'containsKey', 'Contains Key',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'map',
                   'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
                   'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
       JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')),
   'Check whether a map contains the given key', 'collection', TRUE),
  ('func-contains-value', 'containsValue', 'Contains Value',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'map',
                   'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
                   'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'string')),
       JSON_OBJECT('kind', 'primitive', 'name', 'string')),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')),
   'Check whether a map contains the given scalar value', 'collection', TRUE),
  ('func-keyset', 'keySet', 'Key Set',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'map',
                   'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
                   'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
     'return', JSON_OBJECT('kind', 'list', 'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
   'Return map keys as a list', 'collection', TRUE),
  ('func-values', 'values', 'Values',
   JSON_OBJECT(
     'params', JSON_ARRAY(
       JSON_OBJECT('kind', 'map',
                   'keyType', JSON_OBJECT('kind', 'primitive', 'name', 'string'),
                   'valueType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
     'return', JSON_OBJECT('kind', 'list', 'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
   'Return map values as a list', 'collection', TRUE),
  ('func-isempty', 'isEmpty', 'Is Empty',
   JSON_OBJECT(
     'params', JSON_ARRAY(JSON_OBJECT('kind', 'list', 'elementType', JSON_OBJECT('kind', 'primitive', 'name', 'string'))),
     'return', JSON_OBJECT('kind', 'primitive', 'name', 'boolean')),
   'Return whether a list or map is empty', 'collection', TRUE);

INSERT INTO artifact_storage_config (id, code, storage_type, is_default, config_json, is_active) VALUES
  ('storage-local', 'local-default', 'local', TRUE,
   '{"basePath":"C:/Users/Administrator/orule/data/artifacts","baseUrl":"http://localhost:8080/api/v1/artifacts"}',
   TRUE);
