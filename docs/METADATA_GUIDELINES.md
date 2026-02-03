# Metadata guidelines — precedence, mixins, provenance, auto-DDL, and CI

Purpose: provide a single, actionable reference for developers and BAs on how metadata is resolved, how to request plain exports, and what the framework will/do enforce.

1) Deterministic precedence (one-liner)
- child (class) > mixins (declared left→right, last wins) > parent chain (nearest → farthest).

2) Mixins
- Use `mixins: ["MixinA","MixinB"]` to compose reusable mapping sets.
- Mixins are applied in declared order; later mixins override earlier ones.
- Mixins can be DB-backed (admin overrides) or file-backed (canonical).

3) Remove & override
- `remove:true` behaves like a mapping with precedence: a higher-precedence layer (child or later mixin) can re-introduce the column.

4) Provenance & diagnostics
- Every resolved FieldMapping includes provenance: `sourceClass`, `sourceKind` (file|db), `sourceModule`, `sourceLocation`.
- Resolver emits diagnostics for: circular references, `plainColumn` type conflicts, missing required FlowableObject fields.

5) Auto-DDL and migration workflow
- `MetadataDdlGenerator` produces idempotent DDL (e.g. `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...`).
- Developer flow (recommended):
  1. Update metadata and run `.\gradlew.bat validateMetadata` + UI Field-Check.
  2. Generate migration SQL with the helper (dev-only): `.\gradlew.bat :core:generateMetadataMigration -Pclass=<ClassName>`.
  3. Submit migration + reindex/backfill plan to DBAs; run `reindexAll(className)` after migration.
- Production rule: schema changes must be applied via DB migration; framework DDL is for developer convenience and migration generation only.

6) Type conflicts
- If two sources map to the same `plainColumn` with incompatible types, resolver records a diagnostic.
- CI strict-mode: fail the build on type conflicts for `plain` exports.

7) Cycle detection
- Parent/mixin cycles are detected and reported. CI must fail on cycles.

8) Tests you must add when changing metadata
- Unit: precedence (child/mixins/parent), mixin ordering, remove semantics, type-conflict detection, cycle detection, provenance present.
- Integration: E2E reindex that proves mixin/parent fields populate `case_plain_*` columns and that reindex can backfill newly created columns.

9) CI/Lint rules (recommended)
- Fail CI on:
  - circular parent/mixin references
  - incompatible `plainColumn` types across merged metadata
  - core module exposing non-core/domain classes
- Emit warnings (but not fail) on duplicate definitions across modules; show provenance in report.

10) Examples
- Precedence: `Order` (parent=WorkObject) + `mixins: [A,B]` and A/B define `shared_col` -> final `shared_col` is from **B** unless `Order` overrides it.
- Remove: MixinA defines `x_col`; Child sets `{"column":"x_col","remove":true}` -> `x_col` absent unless re-declared by child.

11) Helpful commands
- Validate metadata: `.\gradlew.bat validateMetadata`
- Run field-check against a sample blob (UI): open `/admin/metadata-ui.html` and use Field-Check
- Generate migration SQL (dev helper): `.\gradlew.bat :core:generateMetadataMigration -Pclass=Order`

---
If you'd like, I can add a CI job that implements the lint rules above and a README section that documents the expected PR checklist for metadata changes.