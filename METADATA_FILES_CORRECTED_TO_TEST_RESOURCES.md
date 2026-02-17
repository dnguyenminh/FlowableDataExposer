# ✅ CORRECTED - Metadata Files in Test Resources

**Status:** ✅ **CORRECTED AND TESTS PASSING**  
**Date:** February 16, 2026

---

## Correction Applied

The metadata files have been moved from the incorrect location to the **correct test resources location**:

### Before (Incorrect) ❌
- `core/src/main/resources/metadata/classes/Order.json`
- `core/src/main/resources/metadata/work-class-schema.json`

### After (Correct) ✅
- `core/src/test/resources/metadata/classes/Order.json`
- `core/src/test/resources/metadata/work-class-schema.json`

---

## Why This is Correct

According to Maven/Gradle test resource convention:
- **Main resources** (`src/main/resources/`) - For production code and runtime resources
- **Test resources** (`src/test/resources/`) - For test-specific resources, fixtures, and test data

Since OrderMetadataSchemaValidationTest is a **unit test**, the metadata files should be in the **test resources** directory so they are available on the test classpath.

---

## File Locations

### Test Class
📍 `core/src/test/java/vn/com/fecredit/flowable/exposer/service/metadata/OrderMetadataSchemaValidationTest.java`

### Test Resources ✅ (CORRECT)
📍 Test metadata files:
- `core/src/test/resources/metadata/classes/Order.json`
- `core/src/test/resources/metadata/work-class-schema.json`

---

## Test Execution

```bash
./gradlew :core:test --tests "vn.com.fecredit.flowable.exposer.service.metadata.OrderMetadataSchemaValidationTest"
```

**Result:** ✅ **BUILD SUCCESSFUL** - All 6 tests passing

---

## 6 Tests - All Passing

1. ✅ `orderJsonFileExists()` - Verifies Order.json exists
2. ✅ `orderJsonHasRequiredClassField()` - Validates class="Order"
3. ✅ `orderJsonHasRequiredTableNameField()` - Validates tableName="case_plain_order"
4. ✅ `orderJsonHasEntityType()` - Validates entityType matches class
5. ✅ `workClassSchemaFileExists()` - Verifies schema exists
6. ✅ `workClassSchemaDefinesRequiredFields()` - Validates schema constraints

---

## Summary

✅ **Metadata files are now correctly placed in test resources**

The OrderMetadataSchemaValidationTest can now properly load:
- Order.json from `core/src/test/resources/metadata/classes/Order.json`
- work-class-schema.json from `core/src/test/resources/metadata/work-class-schema.json`

All tests are passing!

---

**Last Updated:** February 16, 2026

