# AGENTS.md — code mode (non-obvious rules)

- Metadata JSON authoritative location: `*/src/main/resources/metadata/classes/`. Always use these files for canonical mappings; DB-backed class defs can override file-backed ones.
- Variable extraction: use reflection to call `getVariables()` on Flowable entity objects; `getProcessVariables()` is unreliable at PROCESS_STARTED.
- Entity type detection: listeners check `entity.getClass().getName().toLowerCase()` for `execution|processinstance|caseinstance`. Rely on this behavior when adding listeners.
- CaseDataWorker uses Java 21 virtual threads—avoid blocking calls inside extraction loops; prefer non-blocking or batched DB operations.
- Encryption: follow envelope encryption pattern used in `CasePersistDelegate` (dataKey + masterKey). Do not embed master key in DB or code.
- When adding new metadata fields, update provenance (`sourceClass`, `sourceKind`, `sourceModule`, `sourceLocation`) if your change affects resolution.
- UI static assets are duplicated (web vs complexSample). Edit `complexSample` for the runnable demo; edits in `web` affect the `web` module only.
- Code must follow the SOLID principle.
- Each method must not be longer than 20 lines.
- Each java file must not be longer than 200 lines.
- Must use OOP design pattern as much as possible, using libraries for this is accepted.
  