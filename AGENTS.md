# AGENTS.md

This file provides guidance to agents when working with code in this repository.

- Build/test: use Gradle wrappers from project root. Run a single module test with:
  ./gradlew :core:test --tests "vn.com.fecredit.flowable.exposer.service.MetadataDdlGeneratorTest"
- Java runtime: project targets Java 21 (virtual threads). Don't run with older JDKs.
- Metadata sources: metadata JSON live under [`core/src/main/resources/metadata/classes/`](core/src/main/resources/metadata/classes:1) and module-specific folders (`web`, `complexSample`). The class loader registers files at startup via [`MetadataResourceLoader.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResourceLoader.java:1).
- DDL generation: always use [`MetadataDdlGenerator.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataDdlGenerator.java:1) to emit ALTER/CREATE statements; add migration SQL under `migrations/` (forward-only) rather than hand-editing DB.
- Blob immutability: `sys_case_data_store` is the source-of-truth and is immutable — never modify or delete blobs (see [`SysCaseDataStore.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/entity/SysCaseDataStore.java:1)).
- Reindex/backfill: orchestration is via `CaseDataWorker` and persisted jobs in `sys_expose_index_job` (`SysExposeIndexJob.java`). Prefer `reindexAll(className)` patterns; update job progress via the job entity rather than ad-hoc scripts.
- Metadata precedence & gotchas: resolution honors child > mixins (last wins) > parent chain. Use [`MetadataResolver.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java:1) to reproduce behavior — manual edits that bypass resolver produce inconsistent results.
- JSON Schema: metadata validation schemas are under [`core/src/main/resources/metadata/`](core/src/main/resources/metadata:1). Ensure `$schema` pointers in metadata files reference these for editor/CI validation.
- Logs & debugging: sample app listens on port 8080; check `complexSample_run.log` and module `logback-spring.xml` for boot diagnostics if bootRun fails.
- Tests: integration tests use H2. Keep tests co-located with sources (module src/test) to match Gradle config.

(Only non-obvious, project-specific rules are listed above.)
