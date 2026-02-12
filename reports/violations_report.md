# Code Size Violations Report

Generated: 2026-02-12T03:23:00+07:00

## Files longer than 200 lines

- [`core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelValidatorRenderer.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/util/ModelValidatorRenderer.java:1) — 1184 lines
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java:1) — 418 lines
- [`complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java`](complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java:1) — 403 lines
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java:1) — 266 lines
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java:1) — 265 lines
- [`web/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java`](web/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java:1) — 258 lines

## Methods longer than 20 lines

- [`core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java:76) — reindexByCaseInstanceId (189 lines)
- [`complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java`](complexSample/src/main/java/vn/com/fecredit/flowable/exposer/web/OrderController.java:277) — getCaseDiagram (74 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/service/CaseDataPersistService.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/CaseDataPersistService.java:38) — persistSysCaseData (50 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java:188) — populateFlowableMetadata (40 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java:84) — resolveCaseInstanceId (37 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java:50) — execute (33 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java:93) — extractCaseInstanceId (33 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/CasePersistDelegate.java:148) — ensureClassAnnotations (32 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java:28) — handle (32 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataAnnotator.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataAnnotator.java:113) — annotateByClass (32 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/GlobalFlowableEventListener.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/GlobalFlowableEventListener.java:27) — onEvent (31 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/TaskExposeHandler.java:61) — persistRequest (31 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java:48) — pollAndProcess (26 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/MetadataResolver.java:59) — loadFileMetadata (26 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/DmnDecisionDelegate.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/delegate/DmnDecisionDelegate.java:121) — javaFallback (24 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java:52) — handleProcessStarted (23 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/service/CaseDataPersistService.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/CaseDataPersistService.java:95) — reindexExistingBlobs (23 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java:130) — enrichMinimalAnnotations (22 lines)
- [`core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/flowable/FlowableEventDelegator.java:30) — handle (21 lines)

## Next-action priorities (recommended)

1. Split the very large utility `ModelValidatorRenderer.java` into smaller classes (image/DMN/DMN-table helpers). This file is the top offender.
2. Reduce `MetadataResolver.java` by moving metadata parsing and DB access into helper services.
3. Refactor long methods in `CaseDataWorker`, `CasePersistDelegate`, and `CaseDataPersistService` into small helpers/services.
4. Update/extend unit tests for the new classes.

---

Report saved to [`reports/violations_report.md`](reports/violations_report.md:1).
