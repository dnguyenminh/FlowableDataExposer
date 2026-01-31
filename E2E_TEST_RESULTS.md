# E2E Test Results - Flowable Data Exposer

## ✅ Test Status: **ALL PASSED**

**Tests:** 
1. `CaseDataWorkerTest.bpmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex()` ✅ **PASSED** (1.788s)
2. `CaseDataWorkerTest.cmmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex()` ✅ **PASSED** (0.184s)

**Total Duration:** 1.972 seconds  
**Success Rate:** 100%  
**Date:** 2026-01-29

---

## 🎯 What Was Tested

This end-to-end test validates the complete encrypted Case Data Store design integrated with **both Flowable BPMN and CMMN engines**:

### 1. **Flowable BPMN Process Execution** ✅
- ✅ Started real BPMN process (`orderProcess`) via `RuntimeService.startProcessInstanceByKey()`
- ✅ Process instance created successfully with ID
- ✅ Sequence flows executed: `start → persist (serviceTask) → end`
- ✅ `CasePersistDelegate` (implements `JavaDelegate`) invoked by BPMN engine
- ✅ Encrypted case data stored with process instance ID
- ✅ Index created with values: `totalAmount=123.45`, `item1Id="item-123"`, `colorAttr="red"`

### 2. **Flowable CMMN Case Execution** ✅
- ✅ Started real CMMN case (`orderCase`) via `CmmnRuntimeService.createCaseInstanceBuilder()`
- ✅ Case instance created successfully with ID
- ✅ Plan item executed: `persistTask` (human task with Java delegate)
- ✅ `CasePersistCmmnDelegate` (implements `PlanItemJavaDelegate`) invoked by CMMN engine
- ✅ Encrypted case data stored with case instance ID
- ✅ Index created with values: `totalAmount=456.78`, `item1Id="item-456"`, `colorAttr="blue"`

### 3. **JavaDelegate Integration** ✅
- ✅ **BPMN Delegate:** `CasePersistDelegate` implements `org.flowable.engine.delegate.JavaDelegate`
  - Invoked via `flowable:delegateExpression="${casePersistDelegate}"`
  - Reads process variables from `DelegateExecution.getVariables()`
  - Uses `execution.getProcessInstanceId()` as case identifier
- ✅ **CMMN Delegate:** `CasePersistCmmnDelegate` implements `org.flowable.cmmn.api.delegate.PlanItemJavaDelegate`
  - Invoked via `flowable:delegateExpression="${casePersistCmmnDelegate}"`
  - Reads case variables from `DelegatePlanItemInstance.getVariables()`
  - Uses `delegatePlanItemInstance.getCaseInstanceId()` as case identifier
- ✅ Both delegates call `ExposeInterceptor.persistCase()` with instance ID

### 4. **Envelope Encryption (AES-256-GCM)** ✅
- ✅ Process/case variables serialized to JSON
- ✅ Random data key generated per record
- ✅ Data encrypted with AES-256-GCM (authenticated encryption)
- ✅ Data key wrapped with master key (envelope encryption)
- ✅ Encrypted payload and wrapped key stored as Base64 strings in CLOB columns

### 5. **Persistent Storage** ✅
- ✅ Encrypted case data saved to `sys_case_data_store` table
- ✅ Fields stored: `case_instance_id` (BPMN process ID or CMMN case ID), `entity_type`, `payload` (CLOB), `encrypted_key` (CLOB)
- ✅ Query by `caseInstanceId` works for both BPMN and CMMN instances

### 6. **Dynamic Indexing** ✅
- ✅ `CaseDataWorker.process()` unwraps data key
- ✅ Decrypts payload successfully
- ✅ Extracts properties via JsonPath:
  - `$.total` → `total_amount`
  - `$.items[0].id` → `item_1_id`
  - `$.params.color` → `color_attr`
- ✅ Index row created in `idx_order_report` table for both BPMN and CMMN instances
- ✅ Values validated for BPMN: `totalAmount=123.45`, `item1Id="item-123"`, `colorAttr="red"`
- ✅ Values validated for CMMN: `totalAmount=456.78`, `item1Id="item-456"`, `colorAttr="blue"`

### 7. **Reindex Capability** ✅
- ✅ Index row deleted via `deleteByCaseInstanceId()`
- ✅ `CaseDataWorker.reindexAll("Order")` executed
- ✅ All encrypted blobs fetched by entity type (both BPMN and CMMN instances)
- ✅ Decryption → extraction → re-indexing completed
- ✅ Index rows rebuilt with correct values for both instances

---

## 🏗️ Technical Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Programming language |
| Spring Boot | 3.2.6 | Framework |
| Flowable | 7.2.0 | BPMN & CMMN engines (unified starter) |
| H2 Database | 1.4.200 | In-memory database (test) |
| Hibernate | 6.4.8 | ORM / JPA |
| Jackson | (Spring Boot) | JSON serialization |
| JsonPath | 2.9.0 | Property extraction |

---

## 🔧 Key Configuration

### Flowable Dependency
```gradle
implementation 'org.flowable:flowable-spring-boot-starter:7.2.0'
```
**Note:** Uses unified starter (includes BPMN, CMMN, DMN, Form, Content, IDM engines)

### Database Schema Management
- **Flowable tables:** Managed by `flowable.database-schema-update=true`
- **Application tables:** Managed by `spring.jpa.hibernate.ddl-auto=create`
- **H2 compatibility:** Downgraded to 1.4.200 for Flowable DDL compatibility

### BPMN Process (`orderProcess.bpmn20.xml`)
```xml
<process id="orderProcess" name="Order Process" isExecutable="true">
  <startEvent id="start"/>
  <sequenceFlow id="flow1" sourceRef="start" targetRef="persist"/>
  <serviceTask id="persist" flowable:delegateExpression="${casePersistDelegate}"/>
  <sequenceFlow id="flow2" sourceRef="persist" targetRef="end"/>
  <endEvent id="end"/>
</process>
```

### CMMN Case (`orderCase.cmmn`)
```xml
<case id="orderCase" name="Order Case">
  <casePlanModel id="casePlanModel" name="Order Case Plan">
    <planItem id="planItem1" name="Persist Case" definitionRef="persistTask"/>
    <task id="persistTask" name="Persist Case" flowable:type="java" 
          flowable:delegateExpression="${casePersistCmmnDelegate}"/>
  </casePlanModel>
</case>
```

---

## 📊 Test Data Flow

### BPMN Flow
```
User Request
  └─> RuntimeService.startProcessInstanceByKey("orderProcess", variables)
       └─> Flowable BPMN Engine starts process
            └─> Execute serviceTask "persist"
                 └─> Invoke ${casePersistDelegate} (CasePersistDelegate implements JavaDelegate)
                      └─> ExposeInterceptor.persistCase(processInstanceId, "Order", variables)
                           ├─> Serialize variables to JSON
                           ├─> Generate random AES-256 data key
                           ├─> Encrypt JSON with data key → Base64 payload
                           ├─> Wrap data key with master key → Base64 encrypted_key
                           └─> Save to sys_case_data_store (CLOB columns)
                      └─> CaseDataWorker.process(saved)
                           ├─> Unwrap data key with master key
                           ├─> Decrypt payload to JSON
                           ├─> Extract via JsonPath (total, items[0].id, params.color)
                           └─> Upsert to idx_order_report
  └─> Test assertions ✅
```

### CMMN Flow
```
User Request
  └─> CmmnRuntimeService.createCaseInstanceBuilder()
           .caseDefinitionKey("orderCase")
           .variables(variables)
           .start()
       └─> Flowable CMMN Engine starts case
            └─> Execute planItem "persistTask"
                 └─> Invoke ${casePersistCmmnDelegate} (CasePersistCmmnDelegate implements PlanItemJavaDelegate)
                      └─> ExposeInterceptor.persistCase(caseInstanceId, "Order", variables)
                           ├─> Serialize variables to JSON
                           ├─> Generate random AES-256 data key
                           ├─> Encrypt JSON with data key → Base64 payload
                           ├─> Wrap data key with master key → Base64 encrypted_key
                           └─> Save to sys_case_data_store (CLOB columns)
                      └─> CaseDataWorker.process(saved)
                           ├─> Unwrap data key with master key
                           ├─> Decrypt payload to JSON
                           ├─> Extract via JsonPath (total, items[0].id, params.color)
                           └─> Upsert to idx_order_report
  └─> Test assertions ✅
```

---

## 🔐 Security Validation

### Envelope Encryption Verified
1. **Data Key:** Generated per record (SecureRandom, 256-bit)
2. **Data Encryption:** AES-256-GCM with unique IV per encryption
3. **Key Wrapping:** Data key encrypted with master key (AES-256-GCM)
4. **Storage:** Encrypted payload and wrapped key stored separately
5. **Decryption:** Requires master key to unwrap data key, then decrypt payload

### CLOB Storage (Base64 Encoding)
- ✅ Payload stored as Base64(IV + ciphertext)
- ✅ Wrapped key stored as Base64(IV + wrapped_key)
- ✅ Database sees only Base64 text in CLOB columns
- ✅ Roundtrip works: Base64 → byte[] → decrypt → original data

---

## 🎉 Conclusion

**All design objectives achieved:**
1. ✅ Encrypted source of truth (sys_case_data_store)
2. ✅ Envelope encryption with per-record data keys
3. ✅ Dynamic indexer extracting JsonPath properties
4. ✅ Reindex capability (delete + rebuild from encrypted blobs)
5. ✅ **Flowable BPMN integration** (JavaDelegate pattern)
6. ✅ **Flowable CMMN integration** (PlanItemJavaDelegate pattern)
7. ✅ CLOB storage with Base64 encoding
8. ✅ **E2E tests pass with both BPMN and CMMN on H2**

**Key Architecture Insights:**
- **BPMN vs CMMN Delegates:** Different interfaces required
  - BPMN: `org.flowable.engine.delegate.JavaDelegate`
  - CMMN: `org.flowable.cmmn.api.delegate.PlanItemJavaDelegate`
- **Unified Flowable Starter:** `flowable-spring-boot-starter:7.2.0` includes both BPMN and CMMN engines
- **Instance ID Mapping:** 
  - BPMN uses `processInstanceId` as `caseInstanceId`
  - CMMN uses native `caseInstanceId`
- **Same Encryption/Index Pipeline:** Both BPMN and CMMN use identical `ExposeInterceptor` and `CaseDataWorker` services

**Next Steps for Production:**
1. Replace in-memory master key with AWS KMS / Azure Key Vault
2. Replace hard-coded MetadataResolver with DB-backed metadata (sys_expose_class_def)
3. Add error handling and transaction management
4. Switch from H2 to production database (PostgreSQL/MySQL)
5. Add audit logging for encryption/decryption operations
6. Implement key rotation strategy
7. Consider unified abstraction layer over BPMN/CMMN delegates for consistency


### 1. **Flowable BPMN Process Execution**
- ✅ Started real BPMN process (`orderProcess`) via `RuntimeService.startProcessInstanceByKey()`
- ✅ Process instance created successfully with ID
- ✅ Sequence flows executed: `start → persist (serviceTask) → end`

### 2. **JavaDelegate Integration**
- ✅ `CasePersistDelegate` invoked automatically by Flowable engine
- ✅ Delegate reads process variables from `DelegateExecution`
- ✅ Calls `ExposeInterceptor.persistCase()` with process instance ID

### 3. **Envelope Encryption (AES-256-GCM)**
- ✅ Process variables serialized to JSON
- ✅ Random data key generated per record
- ✅ Data encrypted with AES-256-GCM (authenticated encryption)
- ✅ Data key wrapped with master key (envelope encryption)
- ✅ Encrypted payload and wrapped key stored as Base64 strings in CLOB columns

### 4. **Persistent Storage**
- ✅ Encrypted case data saved to `sys_case_data_store` table
- ✅ Fields stored: `case_instance_id`, `entity_type`, `payload` (CLOB), `encrypted_key` (CLOB)
- ✅ Query by `caseInstanceId` works correctly

### 5. **Dynamic Indexing**
- ✅ `CaseDataWorker.process()` unwraps data key
- ✅ Decrypts payload successfully
- ✅ Extracts properties via JsonPath:
  - `$.total` → `total_amount`
  - `$.items[0].id` → `item_1_id`
  - `$.params.color` → `color_attr`
- ✅ Index row created in `idx_order_report` table
- ✅ Values validated: `totalAmount=123.45`, `item1Id="item-123"`, `colorAttr="red"`

### 6. **Reindex Capability**
- ✅ Index row deleted via `deleteByCaseInstanceId()`
- ✅ `CaseDataWorker.reindexAll("Order")` executed
- ✅ All encrypted blobs fetched by entity type
- ✅ Decryption → extraction → re-indexing completed
- ✅ Index row rebuilt with correct values

---

## 🏗️ Technical Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Programming language |
| Spring Boot | 3.2.6 | Framework |
| Flowable | 7.2.0 | BPMN engine |
| H2 Database | 1.4.200 | In-memory database (test) |
| Hibernate | 6.4.8 | ORM / JPA |
| Jackson | (Spring Boot) | JSON serialization |
| JsonPath | 2.9.0 | Property extraction |

---

## 🔧 Key Configuration

### Database Schema Management
- **Flowable tables:** Managed by `flowable.database-schema-update=true`
- **Application tables:** Managed by `spring.jpa.hibernate.ddl-auto=create`
- **H2 compatibility:** Downgraded to 1.4.200 for Flowable DDL compatibility

### BPMN Process (`orderProcess.bpmn20.xml`)
```xml
<process id="orderProcess" name="Order Process" isExecutable="true">
  <startEvent id="start"/>
  <sequenceFlow id="flow1" sourceRef="start" targetRef="persist"/>
  <serviceTask id="persist" flowable:delegateExpression="${casePersistDelegate}"/>
  <sequenceFlow id="flow2" sourceRef="persist" targetRef="end"/>
  <endEvent id="end"/>
</process>
```

---

## 📊 Test Data Flow

```
User Request
  └─> RuntimeService.startProcessInstanceByKey("orderProcess", variables)
       └─> Flowable Engine starts process
            └─> Execute serviceTask "persist"
                 └─> Invoke ${casePersistDelegate} (CasePersistDelegate)
                      └─> ExposeInterceptor.persistCase(processInstanceId, "Order", variables)
                           ├─> Serialize variables to JSON
                           ├─> Generate random AES-256 data key
                           ├─> Encrypt JSON with data key → Base64 payload
                           ├─> Wrap data key with master key → Base64 encrypted_key
                           └─> Save to sys_case_data_store (CLOB columns)
                      └─> CaseDataWorker.process(saved)
                           ├─> Unwrap data key with master key
                           ├─> Decrypt payload to JSON
                           ├─> Extract via JsonPath (total, items[0].id, params.color)
                           └─> Upsert to idx_order_report
  └─> Test assertions
       ├─> Process instance ID exists ✅
       ├─> Encrypted blob stored ✅
       ├─> Index row created with correct values ✅
       └─> Reindex rebuilds index correctly ✅
```

---

## 🔐 Security Validation

### Envelope Encryption Verified
1. **Data Key:** Generated per record (SecureRandom, 256-bit)
2. **Data Encryption:** AES-256-GCM with unique IV per encryption
3. **Key Wrapping:** Data key encrypted with master key (AES-256-GCM)
4. **Storage:** Encrypted payload and wrapped key stored separately
5. **Decryption:** Requires master key to unwrap data key, then decrypt payload

### CLOB Storage (Base64 Encoding)
- ✅ Payload stored as Base64(IV + ciphertext)
- ✅ Wrapped key stored as Base64(IV + wrapped_key)
- ✅ Database sees only Base64 text in CLOB columns
- ✅ Roundtrip works: Base64 → byte[] → decrypt → original data

---

## 🎉 Conclusion

**All design objectives achieved:**
1. ✅ Encrypted source of truth (sys_case_data_store)
2. ✅ Envelope encryption with per-record data keys
3. ✅ Dynamic indexer extracting JsonPath properties
4. ✅ Reindex capability (delete + rebuild from encrypted blobs)
5. ✅ Flowable BPMN integration (JavaDelegate pattern)
6. ✅ CLOB storage with Base64 encoding
7. ✅ E2E test passes with real Flowable engine on H2

**Next Steps for Production:**
1. Replace in-memory master key with AWS KMS / Azure Key Vault
2. Replace hard-coded MetadataResolver with DB-backed metadata (sys_expose_class_def)
3. Add error handling and transaction management
4. Switch from H2 to production database (PostgreSQL/MySQL)
5. Add audit logging for encryption/decryption operations
6. Implement key rotation strategy
