# AGENTS.md

This file provides guidance to agents when working with code in this repository.

- Module layout: Gradle multi-module with modules `core`, `web`, `complexSample`. Edit the correct module: the runnable sample UI is in `complexSample` (not `web`)—static files are duplicated under both modules.
- Run the sample web app: `./gradlew :complexSample:bootRun -x test` (module-specific bootRun is required to serve `complexSample` resources).
- Packaged model filenames: the sample BPMN used by the UI is at `complexSample/src/main/resources/processes/online-order-process.bpmn`. UI previously referenced `/processes/orderProcess.bpmn`—fix filenames where the UI fetches `/processes/{name}`.
- UI endpoints and server mapping (non-obvious): the admin UI fetches
  - `/api/orders` and `/api/orders/{id}/steps` (see `complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java`),
  - packaged files under `/processes/{filename}` are served by `ProcessFileController` in `complexSample`.
- Source-of-truth rules (critical): `sys_case_data_store` is permanent and immutable; blobs are AES-256-GCM encrypted and use envelope encryption (data key encrypted by master key). Do NOT delete or modify these records; re-indexing regenerates derived tables.
- Metadata location & precedence (non-obvious): canonical class metadata JSON files live under `*/src/main/resources/metadata/classes/`. Resolution precedence implemented in code: child class mappings > mixins (last mixin wins) > parent chain > DB-backed (latest enabled) > file-backed.
- Encryption & keys: payloads encrypted with AES-256-GCM; the master key is never stored in DB (see `CasePersistDelegate` and persistence code in `core`).
- BPMN/CMMN extraction gotchas:
  - Use `getVariables()` (not `getProcessVariables()`) when extracting variables at PROCESS_STARTED (see comments in `OrderController`).
  - Event listener determines type by lowercasing entity class name and checking for `execution`/`processinstance` (BPMN) or `caseinstance` (CMMN).
- CaseDataWorker specifics: uses Java 21 Virtual Threads and JsonPath extraction; metadata is cached (Caffeine). See `core/src/main/java/.../job/CaseDataWorker.java` for threading and batch behavior.
- Re-indexing: supported via a service method to rebuild index tables from the blob store; index tables (`case_plain_*`) are derived and can be truncated.
- Tests: integration/E2E rely on H2 in-memory and specific auto-DDL; to run module tests or a single test use Gradle module-qualified commands, e.g. `./gradlew :core:test --tests "vn.com.fecredit.flowable.exposer.service.MetadataResolverTest"`.
- Model rendering utility: `core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelValidatorRenderer.java` can render BPMN/CMMN/DMN to PNG using reflective converters—it's feature-rich and may rely on optional converters present on the classpath.
- CI/validation gotchas: metadata type conflicts and circular inheritance cause CI strict-mode failures; editing mapping files must account for "remove: true" semantics and provenance tracking.
- Logging for local debugging: `complexSample/src/main/resources/logback-spring.xml` controls sample app logs; useful for diagnosing `bootRun` startup and REST handler behavior.
- Code must follow the SOLID principle.
- Each method must not be longer than 20 lines.
- Each java file must not be longer than 200 lines.
- Must use OOP design pattern as much as possible, using libraries for this is accepted.
  

Keep edits minimal and module-scoped: when changing the admin UI, prefer editing `complexSample/src/main/resources/static/admin/orders-ui.html` for the sample server, and update `ProcessFileController` only if you change served paths.