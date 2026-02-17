# ✅ Added "mixins" Property to Schema Files

**Date:** February 16, 2026  
**Status:** ✅ **COMPLETE**

---

## Changes Made

### 1. class-schema.json
**File:** `/core/src/main/resources/metadata/class-schema.json`

**Added "mixins" property below "parent":**
```json
"properties": {
  "class": { "type": "string" },
  "parent": { "type": "string" },
  "mixins": {
    "type": "array",
    "items": { "type": "string" },
    "description": "List of mixin classes to apply to this class"
  },
  "entityType": { "type": "string" },
  ...
}
```

### 2. work-class-schema.json
**File:** `/core/src/main/resources/metadata/work-class-schema.json`

**Added "mixins" property below "parent":**
```json
"properties": {
  "class": { "type": "string" },
  "parent": { "type": "string" },
  "mixins": {
    "type": "array",
    "items": { "type": "string" },
    "description": "List of mixin classes to apply to this class"
  },
  "entityType": { "type": "string" },
  ...
}
```

---

## Property Definition

**Name:** `mixins`  
**Type:** `array`  
**Item Type:** `string`  
**Description:** "List of mixin classes to apply to this class"  
**Required:** No (optional property)  
**Position:** Below `parent` property, before `entityType` property

---

## Usage Example

In metadata JSON files (Order.json, WorkObject.json, etc.):

```json
{
  "$schema": "/core/src/main/resources/metadata/work-class-schema.json",
  "class": "Order",
  "parent": "WorkObject",
  "mixins": ["TimestampMixin", "AuditMixin"],
  "tableName": "case_plain_order",
  ...
}
```

---

## Impact

- ✅ Both schema files now support the `mixins` property
- ✅ Metadata validation will recognize and validate mixins as an array of strings
- ✅ MetadataValidationUtil will validate mixins according to the schema definition
- ✅ Optional field - existing metadata without mixins will continue to work

---

## Files Updated

1. `/core/src/main/resources/metadata/class-schema.json` - Added mixins property
2. `/core/src/main/resources/metadata/work-class-schema.json` - Added mixins property

---

**Status:** ✅ **READY TO USE**

