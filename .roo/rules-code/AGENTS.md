# AGENTS.md — code mode (non-obvious rules)

- Use `MetadataResourceLoader` to load metadata; do not rely on manual classpath resource discovery — filenames determine className resolution as loaded by the loader ([`MetadataResourceLoader.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResourceLoader.java:1)).
- Use `MetadataDdlGenerator` to compose SQL for schema changes; migrations belong in `migrations/` and must be forward-only.
- Persist index/backfill jobs using `SysExposeIndexJob` and `SysExposeRequest` entities; do not create transient ad-hoc migration tables.
- Use `CaseDataWriter`/repository patterns for writes (UPSERT semantics). Avoid raw JDBC unless adding a utility that mirrors `CaseDataWriter` behavior.
- Checkstyle and Javadoc are enforced in CI; follow `config/checkstyle/*.xml` for rule exceptions (some public APIs require Javadoc).
