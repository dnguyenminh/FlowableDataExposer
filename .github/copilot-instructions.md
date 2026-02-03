# Copilot / AI agent quick instructions — FlowableDataExposer

Purpose: get an AI coding agent productive immediately. Focus on the Flowable BPMN+CMMN case-data capture, encryption envelope, and indexer design used across the repo.

High-level architecture (one-line):
- Flowable (BPMN + CMMN) → delegate/engine-interceptor (captures full variable map) → encrypted sys_case_data_store (envelope AES-256-GCM) → asynchronous CaseDataWorker → relational index (idx_order_report).
- Async worker uses Java 21 Virtual Threads for high‑scale parallel reindexing; Metadata is cached (Caffeine) to avoid DB hot‑loops.

Key components & where to look (fast links):
- deployment/engine behaviour: `vn.com.fecredit.flowable.exposer.config.CombinedDeployment` (normalizes CMMN, removes flowable:sameDeployment, writes diagnostics in `model-validator/`).
- capture points: `flowable` delegates and listeners — `flowable/CasePersistDelegate*`, `service/ExposeInterceptor`.
- worker / indexer: `service/CaseDataWorker` + `repository/IdxReportRepository` (writes `entity/IdxReport`).
- storage / encryption: `entity/SysCaseDataStore` + `service/KeyManagementService` (AES-256-GCM; tests use in-memory master key).
- canonical resources: `src/main/resources/processes/*.bpmn` and `src/main/resources/cases/*.cmmn` (keep filenames & BPMN/CMMN ids in sync with delegate keys).
- application entry / tests: `FlowableExposerApplication`, `CaseDataWorkerTest`, `CombinedDeploymentIntegrationTest`, `ModelValidatorRendererTest`.
- metadata & mapping: `service/MetadataResolver` (file-backed canonical defs in `src/main/resources/metadata/*.json` **and** DB table `sys_expose_class_def` for runtime/admin overrides). JsonPath examples in tests and `CaseDataWorker`.
  - Resolution rules: **child > mixins (left→right, last wins) > parent**; `remove:true` follows the same precedence. Resolver records provenance and emits diagnostics for cycles / type conflicts.
  - Auto-DDL & migrations: `MetadataDdlGenerator` can produce idempotent ALTER statements for dev use and generate Flyway-ready SQL + reindex plan for DBA review (production changes must be applied via migrations).
  - Quick commands: run metadata checks `.\gradlew.bat validateMetadata`, generate migration SQL `.\gradlew.bat :tools:generateMetadataMigrations` (dev-only helper), run field-check in UI at `/admin/metadata-ui.html`.
  - Tests to add when changing metadata: unit tests for precedence/mixins/remove, type-conflict detection and a reindex E2E that verifies newly created plain columns are populated.
- CLI & admin actions:
  - validate metadata files: .\gradlew.bat validateMetadata  (or `scripts\validate-metadata.ps1`)
  - import metadata to DB (for admin/demo): .\gradlew.bat exportMetadataToDb  (writes to `sys_expose_class_def`)
  - Admin API: POST /api/metadata/validate, /api/metadata/field-check, /api/metadata/apply, /api/metadata/reindex/{entityType}
  - Developer/BA UI (new): open `/admin/metadata-ui.html` on a local instance to run Field‑Check, edit mappings and toggle `exportToPlain` before applying to DB. THIS PAGE IS FOR DEV/BA ONLY — protect with auth in production.
- File format: `src/main/resources/metadata/<class>.json` — see `Order.json` for an example. Runtime prefers the latest enabled DB version when present; otherwise falls back to file-backed canonical definition.
- authoritative design docs / prompts: `.github/prompts/FlowableDataExposerArchitectDesign.prompt.md` and `.github/prompts/UsecaseDocument.md` — read these first for business rules (reindex, inheritance/override, UI mockups).

Developer workflows (exact commands — Windows PowerShell):
- build all: .\gradlew.bat clean build
- run unit/integration tests: .\gradlew.bat test
- run a single test class: .\gradlew.bat test --tests "vn.com.fecredit.flowable.exposer.CaseDataWorkerTest"
- run a single test method: .\gradlew.bat test --tests "**.CaseDataWorkerTest.bpmnTest_fullRoundtrip_encrypt_store_and_index_and_reindex"
- run the app locally: .\gradlew.bat bootRun  (or debug from IDE by running `FlowableExposerApplication`)
- regenerate model diagnostics (renders + validation): .\gradlew.bat test --tests "**.ModelValidatorRendererTest" — outputs in `build/tmp/test-output/` and `model-validator/`.
- quick validate & render (scans resources/* and writes PNGs): .\gradlew.bat validateModels  -> outputs to `build/model-validator/` (or use `scripts\validate-models.ps1` on Windows)
- run only reindex-related tests: .\gradlew.bat test --tests "**.CaseDataWorkerTest*reindex*"

Project-specific conventions / gotchas (do not change without tests):
- Flowable auto-deploy is disabled (`src/main/resources/application.properties`: flowable.auto-deploy-resources=false). CombinedDeployment performs the controlled deployment — modify it when changing resource names/ordering.
- Keep the validated CMMN on the classpath: `src/main/resources/cases/orderCase.cmmn` — some Modeler exports are intentionally excluded (`cases/orderCase.xml` is excluded).
- Resource-name/key coupling: process/case ids in BPMN/CMMN must match code queries (example: process id `orderProcess`, case id `orderCase`). Delegates use delegateExpression (e.g. `flowable:delegateExpression="${casePersistDelegate}"`).
- Deterministic tests: prefer historic queries where live runtime queries are non-deterministic (tests already follow this pattern).
- Encryption: tests use an ephemeral in-memory master key (see `KeyManagementService`) — production must use an external KMS; SysCaseDataStore stores Base64(iv + ciphertext) and wrapped data keys.
- Metadata inheritance/override: MetadataResolver supports inheritance (child inherits parent's mappings) and child override semantics — add unit tests that assert inheritance and override behavior when changing mappings.
- Immutable source-of-truth: `sys_case_data_store` is treated as append-only/immutable; do not add destructive operations without a migration+audit plan.
- Security constraint: master key MUST NOT be persisted in DB or repo (tests use in-memory master key only). Any change to key handling requires an end-to-end decrypt test.
- Performance: Metadata lookups must be cached (Caffeine) — do not replace with uncached DB calls inside tight loops in `CaseDataWorker`.

Common troubleshooting (fast fixes):
- CMMN fails to register at runtime: check `CombinedDeployment` logs and `model-validator/` output; Modeler exports often need reordering (CombinedDeployment strips `sameDeployment`).
- Tests failing with resource-not-found: ensure `cases/orderCase.cmmn` and `processes/orderProcess.bpmn` are present on classpath (build.gradle intentionally excludes the raw Modeler XML).
- No data written to idx table: set a breakpoint in `CaseDataWorker` or `ExposeInterceptor`; unit tests exercise the full round‑trip (see `CaseDataWorkerTest`).
- Reindex seems slow / OOM: verify MetadataResolver uses Caffeine and worker uses virtual threads; run the reindex test (see `CaseDataWorkerTest`) which simulates delete+rebuild.

Where to add new features (short recipes):
- Add BPMN/CMMN: put validated XML in `src/main/resources/processes/` or `src/main/resources/cases/` and add a focused test in `CaseDataWorkerTest` or a new integration test mirroring existing patterns.
- Capture new fields for indexing: extend `IdxReport` + `IdxReportRepository`, update `CaseDataWorker` JSON -> field mapping, and add unit tests that assert DB rows (see existing test assertions).
- Change deployment normalization: update `CombinedDeployment` and add/adjust `CombinedDeploymentIntegrationTest` — preserve the diagnostic output behavior (writes to `model-validator/`).
- Add/modify Metadata mappings: update `MetadataResolver` (and `sys_expose_class_def` if you add DB backing), add JsonPath examples in tests (e.g. `$.amount`, `$.approvers[0].name`, `$.meta['priority']`) and a `reindexAll(String entityType)` test that verifies historical blobs are re-indexed.
- UI & BA-facing work: mapping UI must support Field‑Check (validate JsonPath against a real blob) and a Re-index All action — add acceptance tests that exercise the management API (if present) or the MetadataResolver directly.

Files & tests you will almost always need to read for feature work:
- src/main/java/.../config/CombinedDeployment.java
- src/main/java/.../service/{ExposeInterceptor,CaseDataWorker,KeyManagementService,MetadataResolver}
- src/main/resources/{processes,cases}
- src/test/java/.../CaseDataWorkerTest.java and CombinedDeploymentIntegrationTest.java
- model-validator/ and build/tmp/test-output/ for rendered diagnostics
- Design & business rules (authoritative): `.github/prompts/FlowableDataExposerArchitectDesign.prompt.md`, `.github/prompts/UsecaseDocument.md`
- Performance & E2E evidence: `E2E_TEST_RESULTS.md` (shows JsonPath fields tested and reindex scenarios)

Acceptance criteria for PRs (practical, test-backed):
- New/changed BPMN or CMMN must include a unit or integration test that exercises deployment and the full encrypt→store→index flow.
- Backwards-compatible DB mapping (migrations or new table) and an E2E-like test that asserts both SysCaseDataStore and IdxReport outcomes.
- If changing encryption/key handling, include a test that can decrypt the stored payload using the repository helper methods.
- If you change Metadata or add index columns: include a `reindexAll` integration test that deletes index rows and verifies rebuild from `sys_case_data_store`.
- Metadata inheritance/override: include unit tests that prove child-class mappings inherit and override parent mappings as described in `UsecaseDocument.md`.
- Performance safety: for metadata-backed changes include a test that asserts MetadataResolver uses caching (Caffeine) under load or a benchmark-style test that demonstrates no N+1 on metadata lookups.

Questions I should ask you before making changes:
- Which resource id(s) (BPMN/CMMN) must remain stable? (they're referenced by key in code/tests)
- Are you changing storage schema or adding index fields? (requires migrations + E2E test)

If anything above is unclear or you'd like the agent to produce a PR template / starter tests for a specific change, tell me which feature or file to target and I'll generate the code + tests.

- Capture new/changed metadata: mappings can now annotate fields with `exportToPlain` and `plainColumn` to request a normalized export into a `case_plain_*` table (example: `case_plain_order`).
- Runtime: `CaseDataWorker` will write both to the index tables (e.g. `idx_order_report`) and to the plain table when mappings request it. See `entity/CasePlainOrder` and `repository/CasePlainOrderRepository`.
- DB migration: `migrations/V1__create_case_plain_order.sql` (provide Flyway/DBAs this example; tests use Hibernate auto DDL).
- Tests: `CaseDataWorkerTest` asserts `case_plain_order` receives `order_total` when `Order.json` mapping includes `exportToPlain:true`.