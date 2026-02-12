# AGENTS.md — architect mode (non-obvious constraints)

- sys_case_data_store is the permanent Source-of-Truth; indexes are derived and can be regenerated. Never rely on index tables for authoritative data.
- Metadata resolution precedence is deterministic and audited. Adding mixins or parent mappings can silently override columns—review resolved mappings before applying DDL.
- Re-indexing is the supported upgrade path for exposing new properties on historical data; implement changes against the blob store workflow not by mutating blobs.
- Performance: CaseDataWorker is designed for high-throughput with virtual threads and batch DB writes; avoid introducing blocking synchronous calls that will bottleneck re-index operations.