# BPMN vs CMMN Integration Summary

## ✅ Both BPMN and CMMN Tests Passing

**Test Results:**
- ✅ `bpmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex()` - **PASSED** (1.788s)
- ✅ `cmmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex()` - **PASSED** (0.184s)

---

## 🔄 Key Differences: BPMN vs CMMN

### 1. **Flowable Dependency**

**Solution:** Use unified Flowable starter
```gradle
implementation 'org.flowable:flowable-spring-boot-starter:7.2.0'
```
This includes both BPMN and CMMN engines (plus DMN, Form, Content, IDM).

❌ **Don't use separate starters** (causes Spring Boot auto-configuration conflicts):
```gradle
// This causes @ConditionalOnMissingBean deduction errors
implementation 'org.flowable:flowable-spring-boot-starter-process:7.2.0'
implementation 'org.flowable:flowable-spring-boot-starter-cmmn:7.2.0'
```

---

### 2. **Delegate Interface**

| Aspect | BPMN | CMMN |
|--------|------|------|
| **Interface** | `org.flowable.engine.delegate.JavaDelegate` | `org.flowable.cmmn.api.delegate.PlanItemJavaDelegate` |
| **Method** | `execute(DelegateExecution execution)` | `execute(DelegatePlanItemInstance planItem)` |
| **Instance ID** | `execution.getProcessInstanceId()` | `planItem.getCaseInstanceId()` |
| **Variables** | `execution.getVariables()` | `planItem.getVariables()` |
| **Component Name** | `@Component("casePersistDelegate")` | `@Component("casePersistCmmnDelegate")` |

---

### 3. **Delegate Implementation**

#### BPMN Delegate (`CasePersistDelegate.java`)
```java
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {
    
    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        Map<String, Object> vars = execution.getVariables();
        var saved = exposeInterceptor.persistCase(processInstanceId, "Order", vars);
        caseDataWorker.process(saved);
    }
}
```

#### CMMN Delegate (`CasePersistCmmnDelegate.java`)
```java
import org.flowable.cmmn.api.delegate.DelegatePlanItemInstance;
import org.flowable.cmmn.api.delegate.PlanItemJavaDelegate;

@Component("casePersistCmmnDelegate")
public class CasePersistCmmnDelegate implements PlanItemJavaDelegate {
    
    @Override
    public void execute(DelegatePlanItemInstance planItem) {
        String caseInstanceId = planItem.getCaseInstanceId();
        Map<String, Object> vars = planItem.getVariables();
        var saved = exposeInterceptor.persistCase(caseInstanceId, "Order", vars);
        caseDataWorker.process(saved);
    }
}
```

**Key Insight:** Both delegates have identical business logic—only the Flowable integration differs.

---

### 4. **Process/Case Definition**

#### BPMN Process (`orderProcess.bpmn20.xml`)
```xml
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn">
  <process id="orderProcess" name="Order Process" isExecutable="true">
    <startEvent id="start" name="Start"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="persist"/>
    <serviceTask id="persist" name="Persist Case" 
                 flowable:delegateExpression="${casePersistDelegate}"/>
    <sequenceFlow id="flow2" sourceRef="persist" targetRef="end"/>
    <endEvent id="end" name="End"/>
  </process>
</definitions>
```

#### CMMN Case (`orderCase.cmmn`)
```xml
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:flowable="http://flowable.org/cmmn">
  <case id="orderCase" name="Order Case">
    <casePlanModel id="casePlanModel" name="Order Case Plan">
      <planItem id="planItem1" name="Persist Case" definitionRef="persistTask"/>
      <task id="persistTask" name="Persist Case" flowable:type="java" 
            flowable:delegateExpression="${casePersistCmmnDelegate}"/>
    </casePlanModel>
  </case>
</definitions>
```

**Key Differences:**
- BPMN uses `<serviceTask>` with explicit sequence flows
- CMMN uses `<planItem>` + `<task>` with implicit execution rules
- Both use `flowable:delegateExpression="${...}"` to reference Spring beans

---

### 5. **Runtime Service & Execution**

| Aspect | BPMN | CMMN |
|--------|------|------|
| **Service** | `org.flowable.engine.RuntimeService` | `org.flowable.cmmn.api.CmmnRuntimeService` |
| **Start Method** | `runtimeService.startProcessInstanceByKey("orderProcess", variables)` | `cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("orderCase").variables(variables).start()` |
| **Return Type** | `org.flowable.engine.runtime.ProcessInstance` | `org.flowable.cmmn.api.runtime.CaseInstance` |
| **ID Accessor** | `processInstance.getId()` | `caseInstance.getId()` |

---

### 6. **Test Code Comparison**

#### BPMN Test
```java
@Test
public void bpmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex() {
    // Setup variables...
    
    // Start BPMN process
    org.flowable.engine.runtime.ProcessInstance pi = 
        runtimeService.startProcessInstanceByKey("orderProcess", variables);
    
    // Verify encrypted storage
    var saved = storeRepo.findByCaseInstanceId(pi.getId());
    assertNotNull(saved);
    
    // Verify index created
    var idx = idxRepo.findByCaseInstanceId(pi.getId());
    assertTrue(idx.isPresent());
    assertEquals(123.45, idx.get().getTotalAmount(), 0.01);
    
    // Test reindex
    idxRepo.deleteByCaseInstanceId(pi.getId());
    worker.reindexAll("Order");
    var idx2 = idxRepo.findByCaseInstanceId(pi.getId());
    assertTrue(idx2.isPresent());
}
```

#### CMMN Test
```java
@Test
public void cmmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex() {
    // Setup variables...
    
    // Start CMMN case
    org.flowable.cmmn.api.runtime.CaseInstance ci = 
        cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("orderCase")
            .variables(variables)
            .start();
    
    // Verify encrypted storage
    var saved = storeRepo.findByCaseInstanceId(ci.getId());
    assertNotNull(saved);
    
    // Verify index created
    var idx = idxRepo.findByCaseInstanceId(ci.getId());
    assertTrue(idx.isPresent());
    assertEquals(456.78, idx.get().getTotalAmount(), 0.01);
    
    // Test reindex
    idxRepo.deleteByCaseInstanceId(ci.getId());
    worker.reindexAll("Order");
    var idx2 = idxRepo.findByCaseInstanceId(ci.getId());
    assertTrue(idx2.isPresent());
}
```

**Key Insight:** Test logic is nearly identical—only the Flowable API calls differ.

---

## 🎯 Unified Architecture

Both BPMN and CMMN paths converge at the **same encryption and indexing pipeline**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Flowable Engine Layer                         │
├─────────────────────────────────┬───────────────────────────────┤
│  BPMN ProcessEngine             │  CMMN CmmnEngine              │
│  └─> CasePersistDelegate        │  └─> CasePersistCmmnDelegate  │
│      (JavaDelegate)             │      (PlanItemJavaDelegate)   │
└─────────────────────────────────┴───────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│             ExposeInterceptor.persistCase()                      │
│  - Serialize variables to JSON                                   │
│  - Generate AES-256 data key (per record)                        │
│  - Encrypt JSON with data key                                    │
│  - Wrap data key with master key                                 │
│  - Store to sys_case_data_store (Base64 CLOB)                    │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              CaseDataWorker.process()                            │
│  - Unwrap data key with master key                               │
│  - Decrypt payload                                               │
│  - Extract properties via JsonPath                               │
│  - Upsert to idx_order_report                                    │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│            Database (H2 in-memory for tests)                     │
│  - sys_case_data_store (encrypted blobs)                         │
│  - idx_order_report (indexed properties)                         │
└─────────────────────────────────────────────────────────────────┘
```

**Benefit:** Single encryption/indexing codebase serves both BPMN processes and CMMN cases.

---

## 📝 Lessons Learned

1. **Unified Flowable Starter Required**
   - Separate BPMN/CMMN starters cause Spring Boot conditional bean conflicts
   - Use `flowable-spring-boot-starter:7.2.0` for both engines

2. **Delegate Interface Mismatch**
   - BPMN requires `JavaDelegate`
   - CMMN requires `PlanItemJavaDelegate`
   - Cannot share single delegate class between BPMN and CMMN

3. **Business Logic Reuse**
   - Both delegates call identical services (`ExposeInterceptor`, `CaseDataWorker`)
   - Only Flowable API integration differs

4. **Instance ID Mapping**
   - BPMN `processInstanceId` maps to `caseInstanceId` in storage
   - CMMN `caseInstanceId` directly maps to `caseInstanceId` in storage
   - Both are treated as opaque identifiers by encryption/indexing pipeline

5. **H2 1.4.200 Compatibility**
   - Flowable 7.2.0 DDL requires H2 1.4.200 (not 2.x)
   - Both BPMN and CMMN engines share same H2 database

---

## 🚀 Production Recommendations

### Option 1: Maintain Separate Delegates (Current Approach)
✅ **Pros:**
- Clear separation of concerns
- Type-safe Flowable API usage
- Easy to add BPMN-specific or CMMN-specific logic

❌ **Cons:**
- Code duplication between delegates
- Two beans to maintain

### Option 2: Unified Abstraction Layer
Create a common service that both delegates call:

```java
@Service
public class CasePersistenceService {
    public void persistAndIndex(String instanceId, String entityType, Map<String, Object> variables) {
        var saved = exposeInterceptor.persistCase(instanceId, entityType, variables);
        caseDataWorker.process(saved);
    }
}
```

Then both delegates become thin wrappers:
```java
@Component("casePersistDelegate")
public class CasePersistDelegate implements JavaDelegate {
    @Autowired CasePersistenceService service;
    
    public void execute(DelegateExecution execution) {
        service.persistAndIndex(
            execution.getProcessInstanceId(), 
            "Order", 
            execution.getVariables()
        );
    }
}

@Component("casePersistCmmnDelegate")
public class CasePersistCmmnDelegate implements PlanItemJavaDelegate {
    @Autowired CasePersistenceService service;
    
    public void execute(DelegatePlanItemInstance planItem) {
        service.persistAndIndex(
            planItem.getCaseInstanceId(), 
            "Order", 
            planItem.getVariables()
        );
    }
}
```

✅ **Pros:**
- Single source of truth for business logic
- Easy to unit test
- DRY principle

---

## ✅ Current Status

**Both BPMN and CMMN integration fully working:**
- ✅ Unified Flowable starter configured
- ✅ BPMN delegate implemented and tested
- ✅ CMMN delegate implemented and tested
- ✅ Both use same encryption/indexing pipeline
- ✅ Both tests pass with 100% success rate
- ✅ H2 1.4.200 compatibility validated
- ✅ Schema management (Flowable + JPA) coordinated
- ✅ Reindex works for both BPMN and CMMN instances

**Ready for production migration with:**
- KMS integration for master key
- Database-backed metadata resolver
- Production database (PostgreSQL/MySQL)
- Error handling and audit logging
