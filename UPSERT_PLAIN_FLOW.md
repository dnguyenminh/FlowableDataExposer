# Dynamic upsertPlain Flow Diagram

## High-Level Flow

```
reindexByCaseInstanceId(caseInstanceId)
    ↓
fetchLatestRow(caseInstanceId) → SysCaseDataStore
    ↓
parsePayload() → Map<String, Object> vars
    ↓
annotate(vars, entityType) → annotatedJson
    ↓
resolver.mappingsFor(entityType) → legacyMappings
resolver.mappingsMetadataFor(entityType) → effectiveMappings
    ↓
[upsertPlain] ← NEW IMPLEMENTATION
    ↓
validateWorkClassMetadataSchema(metaDef)
    ├─ Check: _class != null && !empty
    └─ Check: tableName != null && !empty
    ↓
buildRowValues(caseInstanceId, annotatedJson, rowCreatedAt, 
               effectiveMappings, legacyMappings, directFallbacks)
    ├─ Extract from effectiveMappings (use plainColumn override)
    ├─ Fallback to legacyMappings
    ├─ Fallback to directFallbacks
    ├─ Add created_at if available
    └─ Returns Map<String, Object> rowValues
    ↓
upsertRowByMetadata(tableName, caseInstanceId, rowValues)
    ├─ Validate isValidIdentifier(tableName)
    ├─ buildUpsertSql(tableName, rowValues)
    │   └─ "INSERT INTO {tableName} ({columns}) VALUES ({placeholders})"
    ├─ jdbc.update(sql, paramValues)
    └─ Commit to Database
    ↓
Target Table (specified in metadata.tableName)
```

## Metadata-Driven Table Routing

```
Metadata File (Order.json)
├─ class: "Order"
├─ entityType: "Order"
├─ tableName: "case_plain_order"  ← TABLE SELECTION
├─ mappings:
│  ├─ { column: "order_total", jsonPath: "$.total", plainColumn: "order_total" }
│  ├─ { column: "customer_id", jsonPath: "$.customer.id", plainColumn: "customer_id" }
│  └─ { column: "order_priority", jsonPath: "$.meta.priority" }
└─

Runtime Flow:
1. Load metadata for entityType "Order"
2. Extract tableName = "case_plain_order"
3. Use this table for INSERT/UPSERT
4. Other entity types would specify different tableNames
```

## Field Extraction Waterfall

```
For each FieldMapping in effectiveMappings:
    1. Try JsonPath extraction: JsonPath.read(annotatedJson, fm.jsonPath)
    2. If found, determine column name:
       a. If fm.plainColumn is set and non-empty → use fm.plainColumn
       b. Else → use fm.column
    3. Add to rowValues map
    ↓
For each legacy mapping in legacyMappings:
    1. If column not already set from effectiveMappings
    2. Try JsonPath extraction
    3. Add to rowValues map
    ↓
For each direct fallback in directFallbacks:
    1. If value is not null and column not already set
    2. Add to rowValues map
    ↓
Add created_at timestamp if not already set
```

## SQL Execution Flow

```
upsertRowByMetadata(tableName="case_plain_order", 
                   rowValues={ 
                     case_instance_id: "case-123",
                     order_total: 314.15,
                     customer_id: "C-1",
                     order_priority: "HIGH",
                     created_at: "2025-02-15T10:30:00Z"
                   })
    ↓
Validate Table Name
├─ Pattern: ^[a-zA-Z_$][a-zA-Z0-9_$]*$
├─ case_plain_order ✓ VALID
└─ 123invalid ✗ INVALID
    ↓
Build SQL Statement
    SQL: "INSERT INTO case_plain_order (case_instance_id, order_total, customer_id, order_priority, created_at) 
           VALUES (?, ?, ?, ?, ?)"
    ↓
Execute with JdbcTemplate
    jdbc.update(sql, [
        "case-123",
        314.15,
        "C-1",
        "HIGH",
        Timestamp(2025-02-15T10:30:00Z)
    ])
    ↓
Database Insertion ✓
```

## Error Handling Scenarios

```
Validation Failures:

1. metaDef is null
   → Log: "metadata is null"
   → Return false
   → upsertPlain aborts with warning

2. metaDef._class is null or empty
   → Log: "class field is missing or empty"
   → Return false
   → upsertPlain aborts with warning

3. metaDef.tableName is null or empty
   → Log: "tableName is empty"
   → Return false
   → upsertPlain aborts with warning

4. tableName contains invalid characters
   → Log: "invalid table name: {tableName}"
   → Return early from upsertRowByMetadata

5. JsonPath evaluation fails for a field
   → Log: "Failed to extract value for column {col}: {error}"
   → Skip that field (continue with others)
   → Use fallback mappings if available

6. Database INSERT fails
   → Log: "failed to upsert into table {tableName}: {error}"
   → Caught exception, transaction rolls back
   → reindexByCaseInstanceId marks request as "FAILED"
```

## Backward Compatibility Path

```
Old Approach:
plainRepo.upsertByCaseInstanceId(caseInstanceId, (CasePlainOrder p) -> {
    p.setOrderTotal(...);
    p.setCustomerId(...);
    p.setOrderPriority(...);
}, plainRepo);

New Approach:
upsertPlain(caseInstanceId, annotatedJson, rowCreatedAt, 
            effectiveMappings, legacyMappings, directFallbacks)

Migration Strategy:
1. Old code paths still exist (not removed)
2. New code paths active when metadata specifies tableName
3. Gradual metadata file updates: add tableName field
4. No breaking changes to existing API
5. Both approaches can coexist during transition
```

## Performance Characteristics

```
Per Case Instance Reindex:

1. Fetch latest row: 1 SQL SELECT
2. Parse JSON payload: in-memory JSON parsing
3. Annotate variables: in-memory processing
4. Resolve metadata: cached lookup (Caffeine)
5. Build row values: in-memory JsonPath evaluation
6. Validate schema: in-memory validation
7. Insert to target table: 1 SQL INSERT

Total: 2 SQL round-trips
       O(n) where n = number of field mappings
       
Caching:
- MetadataResolver uses Caffeine (10-minute TTL, 1024 entries)
- Metadata not re-resolved for same entityType within cache window
```

## Test Coverage Areas

```
Unit Tests:
├─ validateWorkClassMetadataSchema()
│  ├─ Valid metadata → true
│  ├─ Null _class → false
│  ├─ Empty _class → false
│  ├─ Null tableName → false
│  ├─ Empty tableName → false
│  └─ Valid metadata with all fields → true
├─ isValidIdentifier()
│  ├─ Alphanumeric + underscore → valid
│  ├─ Starts with letter → valid
│  ├─ Starts with underscore → valid
│  ├─ Starts with $ → valid
│  ├─ Starts with number → invalid
│  ├─ Contains hyphen → invalid
│  ├─ Contains space → invalid
│  └─ Empty string → invalid
├─ buildRowValues()
│  ├─ Extracts from effectiveMappings
│  ├─ Uses plainColumn override
│  ├─ Falls back to legacyMappings
│  ├─ Falls back to directFallbacks
│  ├─ Includes created_at
│  └─ Respects JsonPath failures
├─ buildUpsertSql()
│  ├─ Generates valid SQL syntax
│  ├─ Preserves column order
│  ├─ Uses parameterized placeholders
│  └─ Handles empty map safely

Integration Tests:
├─ reindex_creates_row_in_dynamic_table()
├─ reindex_multiple_entity_types()
├─ reindex_with_null_tableName_skipped()
├─ reindex_validates_table_name()
└─ reindex_handles_json_path_failures()
```

