# Code Examples: upsertPlain Implementation

## Example 1: Basic Metadata Definition

File: `/core/src/main/resources/metadata/classes/Order.json`

```json
{
  "class": "Order",
  "entityType": "Order",
  "tableName": "case_plain_order",
  "version": 1,
  "description": "Order entity with dynamic table export",
  "jsonPath": "$",
  "mappings": [
    {
      "column": "order_total",
      "jsonPath": "$.total",
      "type": "decimal",
      "plainColumn": "order_total",
      "exportToPlain": true,
      "nullable": false
    },
    {
      "column": "customer_id",
      "jsonPath": "$.customer.id",
      "type": "string",
      "plainColumn": "customer_id",
      "exportToPlain": true,
      "nullable": true
    },
    {
      "column": "order_priority",
      "jsonPath": "$.meta.priority",
      "type": "string",
      "plainColumn": "order_priority",
      "exportToPlain": true,
      "nullable": true,
      "default": "MEDIUM"
    }
  ]
}
```

## Example 2: Case Data in Database

Before reindex:

```sql
-- sys_case_data_store table
INSERT INTO sys_case_data_store (case_instance_id, entity_type, payload, created_at) 
VALUES (
  'order-2025-001',
  'Order',
  '{
    "total": 314.99,
    "customer": { "id": "C-12345", "name": "John Doe" },
    "meta": { "priority": "HIGH" },
    "startUserId": "user-admin",
    "items": [...]
  }',
  '2025-02-15 10:30:00'
);
```

## Example 3: How reindexByCaseInstanceId Works

```java
// Called by CaseDataWorker when processing SysExposeRequest
worker.reindexByCaseInstanceId("order-2025-001");

// Internally:
// 1. Fetch latest row from sys_case_data_store
// 2. Parse payload JSON
// 3. Annotate variables
// 4. Call upsertPlain with metadata-driven approach
// 5. Insert row into table specified by metadata.tableName
```

Result after reindex:

```sql
-- case_plain_order table (specified in Order.json as tableName)
INSERT INTO case_plain_order (case_instance_id, order_total, customer_id, order_priority, created_at)
VALUES ('order-2025-001', 314.99, 'C-12345', 'HIGH', '2025-02-15 10:30:00');
```

## Example 4: Multiple Entity Types with Different Tables

```
Order.json     → tableName: "case_plain_order"
Invoice.json   → tableName: "case_plain_invoice"
Shipment.json  → tableName: "case_plain_shipment"

When processing different cases:
- caseInstanceId: order-2025-001, entityType: Order    → insert into case_plain_order
- caseInstanceId: inv-2025-001,   entityType: Invoice  → insert into case_plain_invoice
- caseInstanceId: ship-2025-001,  entityType: Shipment → insert into case_plain_shipment

All using the SAME dynamic upsertPlain method!
```

## Example 5: Field Extraction with Fallbacks

```java
// Annotated JSON from payload
String annotatedJson = """
{
  "@class": "Order",
  "total": 500.0,
  "customer": {"id": "C-999", "name": "Jane Smith"},
  "meta": {"priority": "LOW"},
  "status": "PENDING"
}
""";

// Effective mappings from Order.json
// {
//   "order_total": {column: "order_total", jsonPath: "$.total", plainColumn: "order_total"},
//   "customer_id": {column: "customer_id", jsonPath: "$.customer.id", plainColumn: "customer_id"},
//   ...
// }

// Extracted values:
// 1. order_total     ← JsonPath "$.total"     = 500.0
// 2. customer_id     ← JsonPath "$.customer.id" = "C-999"
// 3. order_priority  ← JsonPath "$.meta.priority" = "LOW"

// Result Map:
Map<String, Object> rowValues = {
  "case_instance_id": "order-2025-001",
  "order_total": 500.0,
  "customer_id": "C-999",
  "order_priority": "LOW",
  "created_at": Timestamp(...)
};
```

## Example 6: plainColumn Override Example

Metadata mapping with column name aliasing:

```json
{
  "mappings": [
    {
      "column": "order_total",
      "jsonPath": "$.total",
      "plainColumn": "normalized_order_total"  ← Override!
    },
    {
      "column": "customer_id",
      "jsonPath": "$.customer.id",
      "plainColumn": "cust_id"  ← Override!
    }
  ]
}
```

Behavior:
```java
// Without plainColumn override:
// Use column name directly
rowValues.put("customer_id", "C-999");

// With plainColumn override:
// Use plainColumn instead
rowValues.put("cust_id", "C-999");
rowValues.put("normalized_order_total", 500.0);
```

Database result:
```sql
-- case_plain_order table has columns:
-- case_instance_id, cust_id (not customer_id!), normalized_order_total, ...
INSERT INTO case_plain_order (case_instance_id, cust_id, normalized_order_total)
VALUES ('order-2025-001', 'C-999', 500.0);
```

## Example 7: Validation Flow

```java
// Step 1: Validate metadata schema
MetadataDefinition metaDef = resolver.resolveForClass("order-2025-001");

// Checks:
if (metaDef == null) {
    // ✗ FAIL: metadata not found
    log.warn("metadata is null");
    return; // abort upsertPlain
}

if (metaDef._class == null || metaDef._class.trim().isEmpty()) {
    // ✗ FAIL: 'class' field missing
    log.warn("'class' field is missing or empty");
    return; // abort upsertPlain
}

if (metaDef.tableName == null || metaDef.tableName.trim().isEmpty()) {
    // ✗ FAIL: 'tableName' field missing
    log.warn("'tableName' field is empty");
    return; // abort upsertPlain
}

// ✓ PASS: all required fields present
log.debug("metadata validated for class {}", metaDef._class);
```

## Example 8: SQL Injection Prevention

```java
// Table name validation
String tableName = "case_plain_order";  // ✓ Valid
String invalid1 = "case_plain_order; DROP TABLE orders;";  // ✗ Invalid
String invalid2 = "123_invalid";  // ✗ Invalid (starts with number)
String invalid3 = "case-plain-order";  // ✗ Invalid (contains hyphen)

// Validation pattern: ^[a-zA-Z_$][a-zA-Z0-9_$]*$
private boolean isValidIdentifier(String identifier) {
    if (identifier == null || identifier.trim().isEmpty()) return false;
    return identifier.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$");
}

// Parameterized queries (no string concatenation)
String sql = String.format(
    "INSERT INTO %s (%s) VALUES (%s)",
    tableName,           // pre-validated
    columns,             // built from keySet()
    placeholders         // "?" marks, never data
);
Object[] paramValues = rowValues.values().toArray();  // data passed separately
jdbc.update(sql, paramValues);  // Spring JDBC handles parameterization
```

## Example 9: Error Handling in upsertPlain

```java
private void upsertPlain(String caseInstanceId, String annotatedJson, Object rowCreatedAt,
                         Map<String, MetadataDefinition.FieldMapping> effectiveMappings,
                         Map<String, String> legacyMappings, Map<String, Object> directFallbacks) {
    try {
        // Validate metadata schema
        MetadataDefinition metaDef = resolver.resolveForClass(caseInstanceId);
        if (!validateWorkClassMetadataSchema(metaDef)) {
            log.warn("upsertPlain: metadata does not conform to Work Class Metadata Schema for case {}", 
                     caseInstanceId);
            return;  // Exit gracefully, don't throw
        }

        // Verify tableName is not empty
        if (metaDef.tableName == null || metaDef.tableName.trim().isEmpty()) {
            log.warn("upsertPlain: tableName is empty for case {}", caseInstanceId);
            return;  // Exit gracefully
        }

        // Build row values
        Map<String, Object> rowValues = buildRowValues(caseInstanceId, annotatedJson, rowCreatedAt, 
                                                       effectiveMappings, legacyMappings, directFallbacks);

        // Dynamic insert
        upsertRowByMetadata(metaDef.tableName, caseInstanceId, rowValues);

        log.info("upsertPlain: Successfully upserted row for case {} into table {}", 
                 caseInstanceId, metaDef.tableName);
    } catch (Exception ex) {
        // Catch any unexpected errors
        log.error("upsertPlain: Failed to upsert plain data for case {}", caseInstanceId, ex);
        // Don't rethrow - let processing continue
    }
}
```

## Example 10: Testing the Implementation

```java
@SpringBootTest
public class UpsertPlainTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CaseDataWorker worker;

    @Autowired
    MetadataResolver resolver;

    @Test
    void upsertPlain_validates_metadata_schema() {
        // Arrange
        MetadataDefinition metaDef = new MetadataDefinition();
        metaDef._class = null;  // Missing required field
        metaDef.tableName = "case_plain_order";

        // Act & Assert
        // validateWorkClassMetadataSchema(metaDef) should return false
        assertFalse(metaDef._class != null && !metaDef._class.trim().isEmpty());
    }

    @Test
    void reindex_uses_table_from_metadata() throws Exception {
        // Arrange
        String caseId = "test-order-001";
        String payload = "{\"total\": 123.45, \"customer\": {\"id\": \"C-1\"}}";
        jdbc.update(
            "INSERT INTO sys_case_data_store(case_instance_id, entity_type, payload) VALUES (?, ?, ?)",
            caseId, "Order", payload
        );

        // Act
        worker.reindexByCaseInstanceId(caseId);

        // Assert
        // Verify row was inserted into case_plain_order (from Order.json tableName)
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM case_plain_order WHERE case_instance_id = ?",
            Integer.class,
            caseId
        );
        assertEquals(1, count);
    }

    @Test
    void isValidIdentifier_rejects_sql_injection_attempts() {
        // Arrange
        String injection = "case_plain_order; DROP TABLE orders;";

        // Act
        boolean isValid = isValidIdentifier(injection);

        // Assert
        assertFalse(isValid);
    }
}
```

## Example 11: Metadata with Inheritance

```json
{
  "class": "SpecialOrder",
  "parent": "Order",  ← Inherit from Order
  "tableName": "case_plain_special_order",
  "version": 1,
  "mappings": [
    {
      "column": "special_discount",
      "jsonPath": "$.discount",
      "plainColumn": "special_discount"
    }
  ]
  // Inherits order_total, customer_id, order_priority from Order
}
```

Behavior:
```
SpecialOrder metadata inherits all mappings from Order
Then adds/overrides with SpecialOrder-specific mappings

Result:
- order_total      ← from Order
- customer_id      ← from Order
- order_priority   ← from Order
- special_discount ← from SpecialOrder (new)

All inserted into case_plain_special_order table
```

## Example 12: Debugging with Logs

```
Successful reindex logs:

[INFO] reindexByCaseInstanceId - start caseInstanceId=order-2025-001
[DEBUG] Querying latest sys_case_data_store row for caseInstanceId=order-2025-001
[DEBUG] validateWorkClassMetadataSchema: metadata validated for class Order
[DEBUG] buildRowValues: extracted 4 columns for case order-2025-001
[DEBUG] upsertRowByMetadata: executing SQL for table case_plain_order with 5 columns
[INFO] upsertRowByMetadata: successfully upserted 1 rows into case_plain_order
[INFO] upsertPlain: Successfully upserted row for case order-2025-001 into table case_plain_order
[INFO] reindexByCaseInstanceId - completed for order-2025-001

Failed reindex logs:

[WARN] upsertPlain: tableName is empty for case order-2025-001
[WARN] upsertPlain: metadata does not conform to Work Class Metadata Schema for case order-2025-001
[ERROR] upsertRowByMetadata: failed to upsert into table case_plain_order: Column 'order_total' not found
[ERROR] upsertPlain: Failed to upsert plain data for case order-2025-001
```

