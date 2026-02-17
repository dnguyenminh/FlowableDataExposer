# AGENTS.md — architect mode (non-obvious constraints)

- Design constraint: `sys_case_data_store` is immutable; all derived index tables are rebuildable from it — never rely on in-place blob edits.
- Backfill must be idempotent and chunked. Use `CaseDataWorker` pattern with short transactions per batch; record progress to `sys_expose_index_job` to allow resume/retry.
- Schema rollout: create new columns before backfilling. Use `MetadataDdlGenerator` to emit ALTER statements; include DDL in `migrations/` for CI reproducibility.
- Metadata precedence rules (child > mixin last wins > parent) are deterministic and enforced by `MetadataResolveEngine`. Type conflicts must surface as diagnostics in CI strict mode.
- Performance: metadata lookup uses a cache; avoid expensive metadata queries during hot paths — use `MetadataResolver` caching helpers.
