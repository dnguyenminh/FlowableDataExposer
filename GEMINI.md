# Gemini CLI Guide for FlowableDataExposer

This document provides a comprehensive guide for the Gemini CLI agent to understand and interact with the `FlowableDataExposer` project. It is based on the detailed architectural and use case documents provided.

## Main Features

The project's functionality is centered around two distinct features for making data accessible:

1.  **Exposing Properties:** This feature extracts specific properties from the source JSON blob and populates them into columns of the main **work table**. This is used to "promote" critical data into first-class columns on the primary business object's table.
    -   **Metadata Schema:** Governed by `@core/src/main/resources/metadata/expose-mapping-schema.json`.

2.  **Indexing Properties:** This feature extracts properties from the source JSON blob and populates them into separate, **dedicated index tables**. These tables are purpose-built for reporting, analytics, or search, and are decoupled from the main work table.
    -   **Metadata Schema:** Governed by `@core/src/main/resources/metadata/index-mapping-schema.json`.

While both features extract data from the same "source of truth" blob, their destination and purpose are different, as defined by their respective metadata schemas.

---

## 1. Core Architectural Philosophy

The project follows a "Source of Truth" vs. "Derived Data" architecture, inspired by Pega.

-   **Source of Truth (The Blob):** The `sys_case_data_store` table is the permanent, immutable source of truth. It stores the full state of a case/process instance as an encrypted JSON blob. This data is never deleted.
-   **Derived Data (The Indexes):** Relational "flat" tables (the work table columns and dedicated index tables) are used for reporting and searching. This derived data is considered disposable and can be regenerated from the "Source of Truth" blob at any time.

## 2. Tech Stack

-   **Java:** Version 21 (utilizing Virtual Threads and Records)
-   **Build:** Gradle with Kotlin DSL (`build.gradle.kts`)
-   **Frameworks:** Spring Boot 3.x, Flowable 7.x
-   **Data Handling:** JsonPath for querying JSON, AES-256-GCM for encryption
-   **Caching:** Caffeine Cache for metadata

## 3. Key Components & Data Flow

### 3.1. UC-01: Case Data Store (Snapshot & Persist)

-   **Trigger:** An interceptor (`ExposeInterceptor`, `casePersistDelegate`) catches commit events from the Flowable Engine when a task completes.
-   **Process:**
    1.  The system automatically captures the entire `variableMap` of the case/process.
    2.  It enriches this map with canonical audit fields from the `FlowableObject` hierarchy (e.g., `createTime`, `startUserId`, `tenantId`).
    3.  The full JSON payload is encrypted using **Envelope Encryption** (a unique Data Key per record, itself encrypted by a Master Key).
    4.  The encrypted blob and the encrypted data key are saved to the permanent `sys_case_data_store` table.

### 3.2. UC-02: Dynamic Indexer (Property Exposure & Indexing)

-   **Trigger:** Asynchronously after a new record is saved to `sys_case_data_store`.
-   **Process:**
    1.  A worker (`CaseDataWorker`), using **Virtual Threads**, picks up the new record.
    2.  It decrypts the JSON blob.
    3.  It resolves the complete metadata mappings (for both exposing and indexing) for the object's class, applying the strict precedence rules (see Section 4.2).
    4.  It uses **JsonPath** to extract data from the JSON.
    5.  It performs dynamic `UPSERT` operations to populate the corresponding columns in the work table and/or rows in the dedicated index tables, according to the resolved metadata.

### 3.3. UC-04: Re-indexing Engine

-   **Purpose:** To refresh the derived data when metadata changes.
-   **Process:**
    1.  A user or admin triggers the process for a specific `className`.
    2.  The system fetches all historical records for that class from `sys_case_data_store`.
    3.  For each record, it runs the standard "Dynamic Indexer" process (decrypt, extract with *new* metadata, upsert).
-   **Significance:** This feature is crucial as it guarantees that reporting tables can always be rebuilt from the source of truth, making schema changes agile and safe.

## 4. Hierarchical Metadata System

This system is deterministic, auditable, and designed for safety, especially during re-indexing.

### 4.1. Canonical Class Hierarchy

All data objects in the system inherit from a common ancestor to ensure consistent metadata and audit trails.

-   **`FlowableObject` (Ancestor):** Contains common audit and identification fields (`className`, `createTime`, `startUserId`, `lastUpdated`, `lastUpdateUserId`, `tenantId`).
-   **`WorkObject`:** For CMMN Case Instances (`caseInstanceId`, `businessKey`, `state`).
-   **`ProcessObject`:** For BPMN Process Instances (`processInstanceId`, `processDefinitionId`, `parentInstanceId`).
-   **`DataObject`:** For reusable or nested data objects.

### 4.2. Metadata Precedence & Resolution Rules

When resolving the final set of mappings for a class, the system follows a strict, deterministic order of precedence. **This is a critical rule.**

1.  **Child Class (Highest):** Mappings or `remove:true` declarations on the specific child class always win.
2.  **Mixins:** Applied in the order they are declared (left-to-right). In case of conflict between mixins, the **last declared mixin wins**. Mixins take precedence over parent classes.
3.  **Parent Chain (Lowest):** Applied from the nearest parent up to the most distant ancestor. A parent's mapping is only used if the field is not defined by the child or any of its mixins.

**Other Key Rules:**
-   **`remove:true`:** Is treated like a mapping and follows the same precedence rules. A higher-precedence definition can "re-introduce" a column that was removed by a lower-precedence one.
-   **Type Conflicts:** The resolver **must** detect and report a diagnostic if a `plainColumn` is mapped with incompatible data types from different sources.
-   **Circular Dependencies:** The resolver **must** detect and fail on circular `parent` or `mixin` references to prevent infinite loops.
-   **Provenance:** Every resolved mapping must include its origin (`sourceClass`, `sourceKind` (file/db), `sourceModule`) for debugging and auditing.

## 5. Database Schema

-   **`sys_case_data_store` (Permanent):**
    -   `id` (PK), `case_instance_id`, `entity_type`, `payload` (Encrypted BLOB), `encrypted_key`, `created_at`, `updated_at`.
-   **Metadata Tables:**
    -   `sys_expose_class_def`: Defines the class hierarchy (parent, mixins).
    -   `sys_expose_mapping`: Defines JsonPath-to-column mappings.
-   **Index Tables (User-Defined):**
    -   e.g., `idx_order_report`, `case_plain_order`. These are the flat tables for reporting.

## 6. Build and Test

### 6.1. Commands

Use the Gradle wrapper to build the project and run tests:

```bash
./gradlew clean test
```

### 6.2. Required Tests (as per documentation)

The test suite must cover the following critical behaviors:

-   **Metadata Resolver Unit Tests:**
    -   Child override precedence.
    -   Mixin ordering (last wins).
    -   `remove:true` semantics.
    -   Cycle detection (parent/mixin).
    -   Type-conflict detection.
-   **Auto-DDL Generation Tests:** Verify that DDL can be generated from metadata and applied to an in-memory DB (H2).
-   **End-to-End Integration Test (`CaseDataWorkerTest`):**
    1.  Persist a complex object.
    2.  Verify it's stored and encrypted in `sys_case_data_store`.
    3.  Verify the `CaseDataWorker` successfully extracts and populates an index table.
    4.  Simulate a re-index by deleting from the index table and ensuring the worker can repopulate it from the blob.
