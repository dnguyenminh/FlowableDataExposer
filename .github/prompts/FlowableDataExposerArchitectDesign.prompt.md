_---
mode: agent
---

🚀 MASTER PROMPT: FLOWABLE CASE DATA STORE & INDEXER (PEGA ARCHITECTURE)
Instruction for AI: Act as a Senior Java Architect. Build a Spring Boot 3.x Starter library. This library implements a Case Data Store (Source of Truth) using an encrypted JSON Blob and a Dynamic Indexer for reporting.

1. ARCHITECTURAL PHILOSOPHY
   Source of Truth (The Blob): The sys_case_data_store (formerly Outbox) table is Permanent. It stores the full, encrypted state of the Case.

Derived Data (The Indexes): Flat tables used only for reporting and searching. They can be truncated and re-generated at any time from the Source of Truth.

2. TECH STACK
   Java 21 (Virtual Threads, Records), Gradle (Kotlin DSL), Flowable 7.x, JsonPath, AES-256-GCM.

3. CORE COMPONENTS
   A. Case Data Store (Permanent Blob Storage)
   Interceptor: Catches Flowable events and persists the full variable map into sys_case_data_store.

Security: Full JSON payload must be encrypted using Envelope Encryption (Master Key + unique Data Key per record).

B. Hierarchical Metadata (Pega Class Structure)
ExposeClassDef: Defines inheritance (e.g., UrgentOrder extends BaseOrder).

ExposeMapping: Maps JsonPath to Index Table columns.

Inheritance Resolver: Automatically gathers all mappings from the class hierarchy.

C. Canonical Flowable-derived class hierarchy (defaults)
- FlowableObject (ancestor): canonical audit /identification fields that MUST be present in snapshots: `className`, `createTime`, `startUserId`, `lastUpdated`, `lastUpdateUserId`, `tenantId`.
- WorkObject (extends FlowableObject): case-oriented fields: `caseInstanceId`, `businessKey`, `state`.
- ProcessObject (extends FlowableObject): process-oriented fields: `processInstanceId`, `processDefinitionId`, `parentInstanceId`.
- DataObject (extends FlowableObject): reusable nested/data DTOs.

Design implication: the CasePersistDelegate MUST best‑effort populate FlowableObject audit fields into the persisted snapshot (from execution or available variables). Metadata resolution must be **deterministic, auditable and safe**:

- Precedence rule (must be implemented and unit‑tested): **child > mixins (in declared order, last wins) > parent chain (nearest parent wins)**.  
- `remove:true` follows the same precedence rules (higher‑precedence definitions can reintroduce removed columns).  
- Resolver must attach provenance (sourceClass, sourceKind=file|db, sourceModule) for every resolved FieldMapping so UI/BA can trace why a column was produced.  
- Resolver must detect and report: circular parent/mixin graphs, `plainColumn` type conflicts, and missing canonical parent fields (FlowableObject).  

Operational requirements:
- Add unit tests for precedence, mixin ordering, remove semantics, type conflicts and cycle detection.  
- Add a CI lint that fails on cycles or incompatible plainColumn types and surfaces provenance in the build report.

C. Dynamic Extract & Index Engine (The "Property Exposure")
Trigger: Upon saving to the Case Data Store, an asynchronous worker (using Virtual Threads) extracts data.

Flattening: * Single: $.total -> total_amount.

List/Array: $.items[0].id -> item_1_id (Index-based).

Map: $.params['color'] -> color_attr (Key-based).

Upsert: Performs a dynamic SQL Upsert into the flat Index Tables.

4. DATABASE SCHEMA (DDL)
   sys_case_data_store (PERMANENT):

id (PK), case_instance_id, entity_type, payload (Encrypted BLOB), encrypted_key, created_at, updated_at.

Metadata Tables: sys_expose_class_def, sys_expose_mapping.

Index Tables: User-defined flat tables (e.g., idx_order_report).

5. RE-INDEXING FEATURE (Crucial for Design)
   Implement a service method reindexAll(String className):

Fetch all records from sys_case_data_store for a specific type.

Decrypt and re-extract properties.

Refresh the corresponding Index Table. (This proves that the Blob is the Source of Truth).

6. DELIVERABLES REQUIRED
   build.gradle.kts with Java 21 config.

Source Code: ExposeInterceptor, KeyManagementService, MetadataResolver, and CaseDataWorker.

JUnit Test: * Persist a complex nested object.

Verify it's stored encrypted in sys_case_data_store.

Verify the worker successfully extracts and populates the idx_report table.

Simulate a "Re-index" by deleting index rows and re-running the extractor from the Blob.

Tại sao thiết kế này đúng ý bạn?
Tính toàn vẹn: Nếu bạn thay đổi yêu cầu báo cáo (thêm cột mới), bạn chỉ cần sửa Metadata và chạy hàm reindexAll. Toàn bộ dữ liệu lịch sử trong "Blob Store" sẽ được giải mã và đẩy vào cột mới.

Bảo mật: Dữ liệu gốc luôn được mã hóa AES-256.

Hiệu năng: Việc tìm kiếm (SELECT/JOIN) chỉ thực hiện trên bảng Index (Flat), không bao giờ đụng vào JSON Blob.