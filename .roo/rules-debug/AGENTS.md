# AGENTS.md — debug mode (non-obvious tips)

- Boot failures often caused by port 8080 in use. Check `complexSample_run.log` and `complexSample_run.err` first.
- Enable DEBUG on package `vn.com.fecredit.flowable.exposer` to trace metadata resolution and `CaseDataWorker` progress (use logback config in module resources).
- UI 404 for `/processes/{filename}` usually means the process file is missing under `complexSample/src/main/resources/processes/` or `ProcessFileController` mapping changed.
- Steps API returning empty arrays: inspect `OrderController` history/task queries and ensure correct entity id mapping (caseInstanceId vs processInstanceId).
- Model rendering issues: `ModelValidatorRenderer` uses reflective converters; missing optional libs show up in stack traces as converter class names — add the missing converter dependency rather than suppressing the error.
