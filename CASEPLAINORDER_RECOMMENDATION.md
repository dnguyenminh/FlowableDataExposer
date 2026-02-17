# CasePlainOrder.java - Architecture Analysis & Recommendation

**Analysis Date:** February 15, 2026
**Status:** OBSOLETE - Safe to Remove

---

## Executive Summary

The `CasePlainOrder.java` class is **dead code** from the old JPA-based architecture. It is:

- ❌ Not used anywhere in active code
- ❌ Not required by the new metadata-driven architecture
- ❌ Not imported by any active classes
- ✅ Safe to delete with zero impact

---

## Architecture Evolution

### Phase 1: Old Architecture (Obsolete)

```
Data Flow:
User → OrderController.getOrderPlain()
  ↓
CasePlainOrderRepository.findByCaseInstanceId()
  ↓
JPA: SELECT FROM case_plain_order (Entity-driven)
  ↓
Return CasePlainOrder entity
  ↓
Map to JSON response

Classes Involved:
✗ CasePlainOrder (JPA entity) - OBSOLETE
✗ CasePlainOrderRepository (JPA repo) - OBSOLETE
✗ CaseDataWorker (old version) - OBSOLETE
```

### Phase 2: Current Architecture (Active)

```
Data Flow:
User → OrderController.reindexCase()
  ↓
CaseDataWorker.reindexByCaseInstanceId()
  ↓
1. MetadataResolver.resolveForClass()
2. Read metadata: { "class": "Order", "tableName": "case_plain_order" }
3. Validate schema (Work Class Metadata Schema)
4. Extract data from sys_case_data_store
5. Auto-create table if missing
6. Parameterized SQL INSERT (ZERO entity classes needed)
  ↓
Database: INSERT INTO case_plain_order
  ↓
User queries table directly with SQL

Classes Involved:
✓ CaseDataWorker (new version) - ACTIVE
✓ MetadataResolver - ACTIVE
✓ MetadataDefinition - ACTIVE
✗ CasePlainOrder (entity) - NOT USED
✗ CasePlainOrderRepository (repo) - NOT USED
```

---

## Current Code Analysis

### Search Results: CasePlainOrder References

**Active Code (core module):**
```
core/src/main/java/CaseDataWorkerHelpers.java
  Line 26: //    public static void setCreatedAtIfMissing(CasePlainOrder p) - COMMENTED OUT
  Line 38: //    public static void setRequestedByFromJson(CasePlainOrder p) - COMMENTED OUT
  Line 47: //    public static void ensureDefaultPriority(CasePlainOrder p) - COMMENTED OUT

core/src/test/java/CaseDataWorkerAutoTableCreationTest.java
  Line 156: assertThat(isValidIdentifier("CasePlainOrder")).isTrue();
           ↑ Only used as a string test, not the class itself
```

**Backup/Legacy Code:**
```
complexsample-bk/ (BACKUP FOLDER - not used)
  ├── entity/CasePlainOrder.java
  ├── repository/CasePlainOrderRepository.java
  └── job/CaseDataWorker.java (old implementation)
```

**Active but Unused:**
```
complexSample/src/main/java/vn/com/fecredit/complexsample/entity/CasePlainOrder.java
  ↑ EXISTS but NOT IMPORTED OR USED ANYWHERE
```

---

## Why CasePlainOrder is Obsolete

### 1. Endpoints Removed
```java
// ❌ REMOVED from OrderController:
GET /api/orders/{caseInstanceId}  // was using CasePlainOrderRepository
GET /api/orders  // was using CasePlainOrderRepository

// ✅ ACTIVE in OrderController:
POST /api/orders/{caseInstanceId}/reindex  // uses CaseDataWorker
```

### 2. Repository Removed
```java
// ❌ CasePlainOrderRepository NOT IN ACTIVE CODE
// Only in complexsample-bk/ (backup)

// Instead using:
jdbc.update("INSERT INTO case_plain_order (...) VALUES (?, ?, ?)")
```

### 3. New Data Access Pattern
```java
// OLD: JPA-based
plainRepo.findByCaseInstanceId(id);
plainRepo.save(entity);

// NEW: SQL-based (metadata-driven)
jdbc.update(dynamicSql, paramValues);
```

### 4. No Imports in Active Code
```
grep -r "import.*CasePlainOrder" core/src/main/java/
  → Result: ZERO matches (all are commented out)

grep -r "import.*CasePlainOrder" web/src/main/java/
  → Result: ZERO matches
```

---

## Data: Before & After

### BEFORE (With CasePlainOrder)
```
OrderController
  └─ @Autowired CasePlainOrderRepository plainRepo
       └─ CasePlainOrder (JPA entity)
            └─ Database table (JPA-managed schema)

Issues:
- Tightly coupled to entity class
- Schema changes require entity updates
- Limited to predefined columns
- Hard-coded mapping logic in CaseDataWorker
```

### AFTER (Without CasePlainOrder)
```
OrderController
  └─ @Autowired CaseDataWorker (no entity needed!)
       └─ MetadataResolver (reads metadata JSON)
            └─ Dynamic SQL (auto-creates tables with any schema)

Benefits:
- Decoupled from entity classes
- Schema defined in metadata JSON
- Unlimited columns per entity type
- Generic mapping logic handles all entities
```

---

## Removal Impact Analysis

### Will Break?
| Item | Impact | Reason |
|------|--------|--------|
| **Compilation** | ❌ NO | Not imported anywhere |
| **Tests** | ❌ NO | No tests depend on it |
| **Core module** | ❌ NO | Not in core module |
| **Web module** | ❌ NO | OrderController updated |
| **Any active code** | ❌ NO | Not imported anywhere |

### Code Quality Impact?
| Item | Impact |
|------|--------|
| **Dead code removed** | ✅ POSITIVE |
| **Codebase clarity** | ✅ POSITIVE |
| **Maintenance burden reduced** | ✅ POSITIVE |
| **Fewer dependencies** | ✅ POSITIVE |
| **Architecture clarity** | ✅ POSITIVE |

---

## Recommendation

### ✅ REMOVE CasePlainOrder.java

**File to delete:**
```
/home/ducnm/projects/java/FlowableDataExposer/
  complexSample/src/main/java/vn/com/fecredit/complexsample/entity/
    CasePlainOrder.java
```

**Why:**
1. ✅ Not used in active code
2. ✅ Represents obsolete architecture
3. ✅ Zero breaking changes
4. ✅ Simplifies codebase
5. ✅ Reduces confusion for new developers
6. ✅ Future-proofs against refactoring

**Effort:**
- Delete 1 file (156 lines)
- No other changes needed
- No compilation/test impacts

---

## Optional: Keep as Reference Only

If you want to keep for historical documentation:

```java
/**
 * @deprecated Replaced by metadata-driven dynamic table creation (February 2026).
 * 
 * This class represents the legacy JPA-based approach to plain data storage.
 * The new architecture uses:
 * - MetadataDefinition: Defines table schema in JSON
 * - CaseDataWorker: Creates tables dynamically at runtime
 * - Direct SQL: No JPA entity classes needed
 * 
 * For new implementations, use metadata JSON files and CaseDataWorker.
 */
@Deprecated(since = "2026-02-15", forRemoval = true)
@Entity
@Table(name = "case_plain_order")
public class CasePlainOrder {
    // ... existing code
}
```

---

## Summary

| Question | Answer |
|----------|--------|
| **Is it used?** | ❌ NO |
| **Is it needed?** | ❌ NO |
| **Safe to remove?** | ✅ YES |
| **Any breaking changes?** | ❌ NO |
| **Recommendation** | ✅ DELETE |

---

**Conclusion:** The `CasePlainOrder.java` class should be deleted. It is dead code from an obsolete architecture and serves no purpose in the current metadata-driven system.

---

*Analysis completed: February 15, 2026*

