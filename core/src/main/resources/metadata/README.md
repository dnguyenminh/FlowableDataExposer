Core metadata ownership

Purpose: declare canonical *base* metadata that the core indexer and worker rely on. Only low-level Flowable / audit / runtime parents belong here.

Canonical base classes (must remain in core/src/main/resources/metadata/classes):
- FlowableObject — audit & identification (createTime, startUserId, lastUpdated, lastUpdateUserId, tenantId, className)
- WorkObject — case-level (caseInstanceId, businessKey, state) — parent: FlowableObject
- ProcessObject — process-level (processInstanceId, processDefinitionId, parentInstanceId) — parent: FlowableObject
- DataObject — shared nested-data parent (id, type) — parent: FlowableObject

Migration policy
- Domain/nested classes (e.g., Meta, Customer, Item, ApprovalDecision, Params) must live in the owning module (web) and not be duplicated in core/main resources.
- If a canonical definition moves, mark the original file as `deprecated: true` and set `migratedToModule` to the new owner.
- Deletions must be performed in a follow-up cleanup PR after team review to avoid transient CI breakage.

Why
- Keeps core lean and stable (only runtime/audit parents) and avoids conflicting canonical definitions across modules.
- MetadataResolver enforces this: it ignores `deprecated: true` and `migratedToModule` files on the classpath.

Automatic DDL & migration workflow
- The framework supports generating idempotent DDL for `plainColumn` requests (developer convenience). Use `MetadataDdlGenerator` to produce `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` for H2/Postgres and to generate Flyway-ready SQL for DBA review.
- Production policy: **do not** apply schema changes automatically in production. Generate migration SQL via the tooling, submit the migration to DBAs, and include a reindex/backfill plan produced by the framework.

Developer steps (recommended)
1. Add/modify metadata in `web/src/main/resources/metadata/classes` (domain module).  
2. Run `.\gradlew.bat validateMetadata` and use the Field‑Check UI to verify JsonPath samples.  
3. Generate migration SQL: `.\gradlew.bat :core:generateMetadataMigration -Pclass=Order` (produces vetted SQL + reindex plan).  
4. Submit migration + reindex plan to DBAs; CI will run the metadata unit/integration tests and the ownership lint.

CI & ownership
- CI enforces: no circular `parent`/`mixins`, compatible `plainColumn` types, and that core only provides the canonical base classes (FlowableObject, WorkObject, ProcessObject, DataObject).

If you'd like I can open the cleanup PR (remove deprecated core domain files) and add the CI ownership check as part of the same change.