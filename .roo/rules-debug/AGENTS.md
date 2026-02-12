# AGENTS.md — debug mode (non-obvious tips)

- Port conflicts: the sample app uses 8080 by default; `bootRun` will fail if another process holds the port. Check `complexSample_run.log` and `logback-spring.xml` for boot diagnostics.
- Useful log locations: `complexSample/src/main/resources/logback-spring.xml` and `core` module logs; enable DEBUG on `vn.com.fecredit.flowable.exposer` to trace metadata resolution and CaseDataWorker.
- Troubleshooting UI fetches: the UI fetches `/processes/{filename}` — if 404, confirm the file exists in `complexSample/src/main/resources/processes/` and `ProcessFileController` mapping.
- Steps API empty array: investigate `OrderController` history/task queries and ensure proper entity id mapping (caseInstanceId vs processInstanceId). Add log statements near reflective TaskService lookups.
- Model rendering failures: `ModelValidatorRenderer` uses reflective converters; missing optional libs produce fallbacks—check stack traces for the converter class name.