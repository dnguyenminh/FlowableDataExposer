# FlowableDataExposer

Lightweight service that captures Flowable BPMN/CMMN case data, stores encrypted case blobs and builds metadata-driven relational indexes (example: `idx_order_report`, `case_plain_order`).

Key features
- Capture full Flowable variable map at persistence points
- Encrypted append-only case blob store (AES‑256‑GCM envelope)
- JsonPath + metadata-driven indexer and optional typed (plain) column export
- Example BPMN/CMMN/DMN resources for an `Order` use-case

Quick start (developer)
1. Build & run tests:
   ```powershell
   .\gradlew.bat clean test
   ```
2. Run locally:
   ```powershell
   .\gradlew.bat bootRun
   # open http://localhost:8080/admin/metadata-ui.html (dev-only)
   ```

Important developer notes
- Java 21, Spring Boot 3.2.x, Flowable 7.2.x
- Metadata files: `src/main/resources/metadata/*.json` (see `Order.json`)
- Models: `src/main/resources/processes/*.bpmn`, `src/main/resources/cases/*.cmmn`, `src/main/resources/decisions/*.dmn`
- Reindex / migration: see `migrations/` and `CaseDataWorkerTest` for acceptance examples

Tests of interest
- E2E reindex & encryption: `CaseDataWorkerTest`
- Metadata validation/UI: `MetadataControllerTest`, `ModelValidatorRendererTest`

Contributing
- Open an issue or PR against `main`. Follow the repo conventions and include tests for behavior changes.

License
- MIT — see `LICENSE`.

Maintainer
- dnguyenminh (repository owner)
