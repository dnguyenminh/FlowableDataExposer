Automated Refactor Plan

Goal: Reduce file length to <=200 lines and method length to <=20 lines for violations listed in reports/violations_report.md. Implement minimally-invasive, behavior-preserving refactors: extract helper methods and helper classes where required, keep public APIs unchanged, run full test suite, commit and push to branch refactor/split-long-files-methods.

Files to modify (proposed edits):

1. core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelValidatorRenderer.java
   - Split into:
     - core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelValidatorRenderer.java (public façade, ~200 lines)
     - core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelConverterHelpers.java (helper conversion methods)
     - core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelImageHelpers.java (image generation helpers)
   - Move large private static methods into helper classes and replace with thin delegations.

2. core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java
   - Extract DB/file parsing helpers into MetadataResourceLoader and MetadataMergeService classes
   - Reduce loadFileMetadata and resolveAndFlatten by delegating to these services

3. complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java
   - Extract diagram generation and process-start helpers into ProcessFileController / OrderService classes

4. core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java
   - Extract populateFlowableMetadata, ensureClassAnnotations, copyVariables into small helper classes/methods (already mostly split) to ensure each method <=20 lines

5. core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java
   - Split reindexByCaseInstanceId into: fetchRows, annotateAndUpdateRow, batchUpsert helpers

6. web/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java
   - Same as (3) for web module copy

7. Other long methods listed in report (CaseDataPersistService.persistSysCaseData, TaskExposeHandler.handle/extractCaseInstanceId, MetadataAnnotator.annotateByClass, GlobalFlowableEventListener.onEvent, CaseDataWorker.pollAndProcess, MetadataResolver.loadFileMetadata, DmnDecisionDelegate.java:javaFallback, FlowableEventDelegator.handleProcessStarted/enrichMinimalAnnotations)
   - For each, extract logical sections into private helper methods or new small classes in same package to keep class files <200 lines and methods <=20 lines.

Automation steps to apply:

- For each target file:
  - Create new helper class file(s) under the same package with focused responsibilities.
  - Replace moved code in original file with delegating calls to new helpers.
  - Ensure imports and constructors use constructor injection where appropriate.
  - Keep method signatures unchanged for public methods.

- Run: ./gradlew test
  - If tests fail, collect failing tests and attempt targeted fixes (restore behavior) and re-run.

- Commit changes with messages: "refactor(core): split <File> into helpers to satisfy size rules"
- Push to origin/refactor/split-long-files-methods

Risks & mitigation:
- Behavior regression: mitigate by running full test suite. If failures appear, revert risky changes for failing areas and escalate for manual review.
- Formatting / style: preserve existing formatting; run project's Gradle build if available.

Estimated duration: automated edits + tests: depends on test runtime. I will stop and report if tests fail and ask for permission to proceed with targeted fixes.

Next step: confirm to apply the automated refactor now (I will run changes, tests, commit, push).