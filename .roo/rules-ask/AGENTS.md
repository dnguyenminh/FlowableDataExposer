# AGENTS.md — ask mode (non-obvious context)

- Metadata files live per-module: `core/src/main/resources/metadata/classes/`, `web/src/main/resources/metadata/classes/`, `complexSample/src/main/resources/metadata/classes/` — each can override mappings. Use `MetadataResolver` to inspect final resolved metadata.
- Provenance fields (`sourceClass`, `sourceKind`, `sourceModule`, `sourceLocation`) are attached during metadata resolution and matter for CI strict-mode conflicts.
- Index mappings vs expose mappings are different schemas (`index-mapping-schema.json` vs `expose-mapping-schema.json` in core resources). Use the correct schema when validating.
- Reindex operations should be audited via `sys_expose_index_job` / `sys_expose_request` tables; searches for progress rely on these persisted records.
