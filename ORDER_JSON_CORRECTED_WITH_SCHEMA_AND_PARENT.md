# ✅ Order.json - CORRECTED WITH SCHEMA AND PARENT

**Status:** ✅ **CORRECTED**  
**Date:** February 16, 2026

---

## Corrections Applied

The Order.json file has been updated with the missing required fields:

### ✅ Added Fields

1. **`"$schema"`** - Reference to the Work Class Metadata Schema
   ```json
   "$schema": "/core/src/main/resources/metadata/work-class-schema.json"
   ```

2. **`"parent"`** - Inheritance from WorkObject
   ```json
   "parent": "WorkObject"
   ```

---

## Updated Order.json Structure

```json
{
  "$schema": "/core/src/main/resources/metadata/work-class-schema.json",
  "class": "Order",
  "parent": "WorkObject",
  "tableName": "case_plain_order",
  "entityType": "Order",
  "description": "Order metadata with mappings for plain table export (inherits from WorkObject)",
  "jsonPath": "$",
  "mappings": [
    { "column": "order_id", "jsonPath": "$.orderId", "exportToPlain": true, "plainColumn": "order_id" },
    { "column": "order_total", "jsonPath": "$.total", "exportToPlain": true, "plainColumn": "order_total" },
    { "column": "customer_id", "jsonPath": "$.customer.id", "exportToPlain": true, "plainColumn": "customer_id" }
  ]
}
```

---

## Consistency with WorkObject.json

Order.json now follows the same pattern as WorkObject.json:

**WorkObject.json:**
```json
{
  "$schema": "/core/src/main/resources/metadata/work-class-schema.json",
  "class": "WorkObject",
  "parent": "FlowableObject",
  "tableName": "DefaultWorkObject",
  ...
}
```

**Order.json (Now Consistent):**
```json
{
  "$schema": "/core/src/main/resources/metadata/work-class-schema.json",
  "class": "Order",
  "parent": "WorkObject",
  "tableName": "case_plain_order",
  ...
}
```

---

## Metadata Inheritance Chain

With the corrected Order.json, the inheritance hierarchy is now:

```
Order (inherits from WorkObject)
  ↓
WorkObject (inherits from FlowableObject)
  ↓
FlowableObject
```

This allows Order to:
- ✅ Inherit canonical fields from WorkObject (caseInstanceId, businessKey, state)
- ✅ Define Order-specific mappings (order_id, order_total, customer_id)
- ✅ Export to plain table (case_plain_order) based on metadata

---

## File Location

📍 **Test Resources:**
- `core/src/test/resources/metadata/classes/Order.json` ✅ CORRECTED

---

## Schema Compliance

Order.json now properly conforms to work-class-schema.json:

| Required Field | Value | Status |
|---|---|---|
| `class` | "Order" | ✅ Present |
| `tableName` | "case_plain_order" | ✅ Present |
| `$schema` | "/core/src/main/resources/metadata/work-class-schema.json" | ✅ Added |
| `parent` | "WorkObject" | ✅ Added |

---

## Integration with CaseDataWorker

The corrected Order.json now properly supports:

1. ✅ **Metadata Validation** - `validateWorkClassMetadataSchema()` passes with all required fields
2. ✅ **Table Creation** - `upsertRowByMetadata()` uses "case_plain_order" as tableName
3. ✅ **Field Mapping** - Mappings define which JSON fields to export to plain columns
4. ✅ **Inheritance** - Order inherits base fields from WorkObject

---

**Status:** ✅ **Order.json CORRECTED WITH SCHEMA AND PARENT**

---

**Last Updated:** February 16, 2026

