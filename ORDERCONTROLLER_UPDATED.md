# ✅ OrderController Updated - CasePlainOrderRepository Removed

**Date:** February 15, 2026
**Status:** ✅ COMPLETE

---

## Summary

The `OrderController` has been updated to remove all references to `CasePlainOrderRepository` which was removed as part of the refactoring to use dynamic work tables managed by `CaseDataWorker`.

---

## Changes Made

### Removed Imports
```java
// Removed (no longer available):
// import vn.com.fecredit.complexsample.entity.CasePlainOrder;
// import vn.com.fecredit.complexsample.repository.CasePlainOrderRepository;
```

### Removed Constructor Parameter
**Before:**
```java
public OrderController(RuntimeService runtimeService,
                       CasePlainOrderRepository plainRepo,
                       org.springframework.context.ApplicationContext appCtx) {
    this.runtimeService = runtimeService;
    this.plainRepo = plainRepo;  // ❌ REMOVED
    this.appCtx = appCtx;
}
```

**After:**
```java
public OrderController(RuntimeService runtimeService,
                       org.springframework.context.ApplicationContext appCtx) {
    this.runtimeService = runtimeService;
    this.appCtx = appCtx;
}
```

### Removed Endpoints

#### 1. GET /{caseInstanceId} - getOrderPlain()
```java
// REMOVED - Plain order data is now stored in dynamic work tables
@GetMapping("/{caseInstanceId}")
public ResponseEntity<?> getOrderPlain(@PathVariable String caseInstanceId) {
    return plainRepo.findByCaseInstanceId(caseInstanceId)...
}
```

**Why:** Plain order data is now automatically extracted and stored in metadata-defined work tables by `CaseDataWorker`. Direct repository access no longer needed.

#### 2. GET / - listPlainOrders()
```java
// REMOVED - Use dynamic table queries instead
@GetMapping
public ResponseEntity<?> listPlainOrders(@RequestParam(required = false) String customerId) {
    List<CasePlainOrder> list = plainRepo.findAll();...
}
```

**Why:** Data retrieval should use the metadata-defined tables directly, not a pre-built repository.

#### 3. Helper Method - sanitize()
```java
// REMOVED - No longer needed
private String sanitize(String key) {
    if (key == null) return "default";
    return key.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
}
```

**Why:** Unused method, removed as cleanup.

---

## Remaining Endpoints

### ✅ POST / - startOrder()
Starts a new order process (BPMN) or case (CMMN).

### ✅ GET /{caseInstanceId}/steps - getCaseSteps()
Gets the case instance steps/history.

### ✅ POST /{caseInstanceId}/reindex - reindexCase()
Triggers reindexing of case data into work tables based on metadata.

---

## How Plain Order Data is Now Handled

### Old Approach (Removed)
```
User Query
    ↓
GET /api/orders/{caseInstanceId}
    ↓
CasePlainOrderRepository.findByCaseInstanceId()
    ↓
Return CasePlainOrder entity
```

### New Approach (Current)
```
Case Data Created
    ↓
sys_case_data_store (append-only)
    ↓
POST /api/orders/{caseInstanceId}/reindex
    ↓
CaseDataWorker.reindexByCaseInstanceId()
    ↓
Extract & store in metadata-defined work tables
  (e.g., case_plain_order, case_plain_invoice, etc.)
    ↓
User queries work tables directly
```

---

## Database Structure Changes

### Before
- `case_plain_order` (JPA-managed entity)
- Hard-coded schema via `CasePlainOrder` class

### After
- `case_plain_order` (auto-created by CaseDataWorker)
- Schema defined in metadata JSON:
  ```json
  {
    "class": "Order",
    "tableName": "case_plain_order",
    "mappings": [...]
  }
  ```

---

## API Usage Examples

### Start an Order (BPMN Process)
```bash
POST /api/orders
Content-Type: application/json

{
  "total": 314.99,
  "customer": { "id": "C-123", "name": "John Doe" }
}

Response: { "id": "order-001", "kind": "process" }
```

### Start an Order Case (CMMN)
```bash
POST /api/orders?type=cmmn
Content-Type: application/json

{
  "total": 314.99,
  "customer": { "id": "C-123", "name": "John Doe" }
}

Response: { "id": "case-001", "kind": "case" }
```

### Get Case Steps
```bash
GET /api/orders/case-001/steps

Response: [
  { "stepName": "Create", "completedAt": "..." },
  { "stepName": "Approve", "completedAt": "..." },
  { "stepName": "Execute", "completedAt": "..." }
]
```

### Trigger Reindex
```bash
POST /api/orders/case-001/reindex

Response: 202 Accepted
```

---

## Migration Impact

### For Existing Code
- ✅ No breaking changes to active endpoints
- ⚠️ Old query endpoints (GET /api/orders, GET /api/orders/{id}) removed
- ✅ Reindex endpoint still available

### For Data Access
- Old: Query `case_plain_order` JPA entity through repository
- New: Query `case_plain_order` database table directly
  
**SQL Example:**
```sql
-- Instead of JPA repository:
-- plainRepo.findByCaseInstanceId("case-001")

-- Use direct database query:
SELECT * FROM case_plain_order WHERE case_instance_id = 'case-001';
```

### For New Entity Types
- Old: Add new entity class + repository + mapping
- New: Define metadata JSON + call reindex
  
**Metadata Example:**
```json
{
  "class": "Invoice",
  "tableName": "case_plain_invoice",
  "mappings": [
    { "column": "invoice_number", "jsonPath": "$.number" },
    { "column": "invoice_amount", "jsonPath": "$.amount" }
  ]
}
```

---

## Compilation Status

✅ **No errors**

Minor warnings (non-critical):
- Unchecked assignment in map conversion (expected)
- Duplicate methods in OrderControllerHelpers (code organization)

---

## Testing Recommendations

1. **Test Process Start**
   - POST /api/orders with BPMN process
   - Verify process instance created

2. **Test Case Start**
   - POST /api/orders?type=cmmn with CMMN case
   - Verify case instance created

3. **Test Reindex**
   - POST /api/orders/{caseInstanceId}/reindex
   - Verify data in work table

4. **Test Case Steps**
   - GET /api/orders/{caseInstanceId}/steps
   - Verify history retrieved

---

## Files Modified

**File:** `/web/src/main/java/vn/com/fecredit/simplesample/web/OrderController.java`

**Changes:**
- Removed `CasePlainOrderRepository` dependency
- Removed `plainRepo` field
- Updated constructor
- Removed `getOrderPlain()` endpoint
- Removed `listPlainOrders()` endpoint
- Removed `sanitize()` helper
- Kept BPMN/CMMN process management
- Kept reindex functionality
- Kept case steps functionality
- Added Javadoc documentation
- Added proper logging

**Total lines changed:** ~60 lines removed

---

## Summary

The `OrderController` has been successfully refactored to:
✅ Remove dependency on removed `CasePlainOrderRepository`
✅ Keep BPMN/CMMN case management functionality
✅ Maintain reindex capability for metadata-driven storage
✅ Support new dynamic work table approach
✅ Improve code documentation

The system now uses metadata-driven storage instead of hard-coded entity classes, providing flexibility to add new entity types without code changes!

---

**Status:** ✅ **READY FOR TESTING & DEPLOYMENT**

