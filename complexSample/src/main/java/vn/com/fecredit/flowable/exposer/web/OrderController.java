package vn.com.fecredit.flowable.exposer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;
import vn.com.fecredit.flowable.exposer.repository.CasePlainOrderRepository;

import java.util.*;
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeService runtimeService;
    private final CasePlainOrderRepository plainRepo;
    private final org.springframework.context.ApplicationContext appCtx;

    public OrderController(RuntimeService runtimeService,
                           CasePlainOrderRepository plainRepo,
                           org.springframework.context.ApplicationContext appCtx) {
        this.runtimeService = runtimeService;
        this.plainRepo = plainRepo;
        this.appCtx = appCtx;
    }

    @PostMapping
    public ResponseEntity<?> startOrder(@RequestBody JsonNode body, @RequestParam(required = false) String type) {
        try {
            Map<String, Object> vars = extractVars(body);
            boolean isCmmn = "cmmn".equalsIgnoreCase(type);
            String id = isCmmn ? startCmmnCase(vars) : startBpmnProcess(vars);
            String kind = isCmmn ? "case" : "process";
            if (id == null) {
                return ResponseEntity.status(500).body(Map.of("error", "started instance id is null"));
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("id", id);
            resp.put("kind", kind);
            return ResponseEntity.status(201).body(resp);
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    private Map<String, Object> extractVars(JsonNode body) {
        JsonNode payload = body.path("payload").isMissingNode() ? body : body.path("payload");
        Map<String, Object> vars = mapper.convertValue(payload, Map.class);
        if (vars == null) vars = new HashMap<>();
        // Ensure initiator exists for expressions that reference ${initiator}
        vars.putIfAbsent("initiator", "system");
        return vars;
    }

    private String startBpmnProcess(Map<String, Object> vars) {
        if (vars == null) vars = new HashMap<>();
        vars.putIfAbsent("initiator", "system");
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("onlineOrderProcess", vars);
        return pi.getId();
    }

    private String startCmmnCase(Map<String, Object> vars) throws ReflectiveOperationException {
        Object cmmn = null;
        if (appCtx.containsBean("cmmnRuntimeService")) {
            cmmn = appCtx.getBean("cmmnRuntimeService");
        } else {
            for (String n : appCtx.getBeanDefinitionNames()) {
                if (n.toLowerCase().contains("cmmnruntimeservice")) { cmmn = appCtx.getBean(n); break; }
            }
        }
        if (cmmn == null) throw new IllegalStateException("CMMN runtime not available");
        // Ensure required variables referenced by CMMN expressions exist
        if (vars == null) vars = new HashMap<>();
        vars.putIfAbsent("initiator", "system");
        vars.putIfAbsent("isPaid", Boolean.FALSE);
        vars.putIfAbsent("isShipped", Boolean.FALSE);
            try {
                Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
                // CMMN case id in resources is 'orderLifeCycleCase'
                builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderLifeCycleCase");
                builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
                Object ci = builder.getClass().getMethod("start").invoke(builder);
                String id = (String) ci.getClass().getMethod("getId").invoke(ci);
                
                // Compensating update: map recent sys_case_data_store rows to the CMMN case id
                try {
                    vn.com.fecredit.flowable.exposer.service.CaseDataPersistService persistService =
                        appCtx.getBean(vn.com.fecredit.flowable.exposer.service.CaseDataPersistService.class);
                    persistService.updateCaseInstanceIdForRecent(id, java.time.Duration.ofSeconds(5));
                } catch (Exception e) {
                    // Log but don't fail - this is a compensating fix
                }
                
                return id;
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getTargetException();
                throw new RuntimeException("CMMN case start failed: " + (cause == null ? ite.getMessage() : cause.getMessage()), cause == null ? ite : cause);
            }
    }

    @GetMapping("/{caseInstanceId}")
    public ResponseEntity<?> getOrderPlain(@PathVariable String caseInstanceId) {
        return plainRepo.findByCaseInstanceId(caseInstanceId)
                .map(p -> {
                    Map<String, Object> out = new HashMap<>();
                    out.put("caseInstanceId", p.getCaseInstanceId());
                    out.put("orderTotal", p.getOrderTotal());
                    out.put("orderPriority", p.getOrderPriority());
                    out.put("approvalStatus", p.getApprovalStatus());
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<?> listPlainOrders(@RequestParam(required = false) String customerId) {
        List<CasePlainOrder> list = plainRepo.findAll();
        if (customerId != null) {
            list.removeIf(p -> p.getCustomerId() == null || !customerId.equals(p.getCustomerId()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : list) {
                Map<String, Object> m = new HashMap<>();
                m.put("caseInstanceId", p.getCaseInstanceId());
                m.put("orderTotal", p.getOrderTotal());
                m.put("orderPriority", p.getOrderPriority());
                m.put("approvalStatus", p.getApprovalStatus());
                out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{caseInstanceId}/steps")
    public ResponseEntity<?> getCaseSteps(@PathVariable String caseInstanceId) {
        try {
            log.info("getCaseSteps start caseId={}", caseInstanceId);
            List<Map<String, String>> out = new ArrayList<>();

            Object taskService = null;
            try {
                if (appCtx.containsBean("taskService")) taskService = appCtx.getBean("taskService");
                else {
                    for (String n : appCtx.getBeanDefinitionNames()) {
                        if (n.toLowerCase().contains("taskservice")) { taskService = appCtx.getBean(n); break; }
                    }
                }
            } catch (Exception e) {
                log.warn("failed to obtain TaskService from context: {}", e.toString());
            }

            log.debug("taskService present: {}", taskService != null);

            if (taskService != null) {
                List<org.flowable.task.api.Task> tasks = new ArrayList<>();
                try {
                    // Use reflection because TaskService.createTaskQuery() signature varies between Flowable versions
                    Object query = null;
                    try {
                        java.lang.reflect.Method m = taskService.getClass().getMethod("createTaskQuery");
                        query = m.invoke(taskService);
                    } catch (NoSuchMethodException nsme) {
                        // Try any overloaded createTaskQuery and pass nulls for parameters
                        for (java.lang.reflect.Method mm : taskService.getClass().getMethods()) {
                            if ("createTaskQuery".equals(mm.getName())) {
                                Class<?>[] params = mm.getParameterTypes();
                                Object[] args = new Object[params.length];
                                for (int i = 0; i < args.length; i++) args[i] = null;
                                query = mm.invoke(taskService, args);
                                break;
                            }
                        }
                    }

                    if (query != null) log.debug("TaskQuery class: {}", query.getClass().getName());

                    if (query != null) {
                        // Try multiple query filters in order until one returns results
                        String[] candidateFilters = new String[]{"scopeId", "processInstanceId", "caseInstanceId", "executionId"};
                        for (String filter : candidateFilters) {
                            try {
                                boolean methodAvailable = false;
                                for (java.lang.reflect.Method mm : query.getClass().getMethods()) if (mm.getName().equals(filter)) { methodAvailable = true; break; }
                                log.debug("filter method '{}' available: {}", filter, methodAvailable);
                                if (!methodAvailable) continue;
                                java.lang.reflect.Method f = query.getClass().getMethod(filter, String.class);
                                Object q2 = f.invoke(query, caseInstanceId);
                                java.lang.reflect.Method listM = q2.getClass().getMethod("list");
                                Object res = listM.invoke(q2);
                                if (res instanceof List) {
                                    List tmp = (List) res;
                                    log.info("filter {} returned {} tasks", filter, tmp.size());
                                    if (!tmp.isEmpty()) { tasks = tmp; break; }
                                }
                            } catch (NoSuchMethodException nsf) {
                                // filter not available on this Flowable version; try next
                                log.debug("filter {} not present on TaskQuery", filter);
                            } catch (Throwable t) {
                                log.warn("error invoking filter {}: {}", filter, t.toString());
                            }
                        }

                        // If still empty, attempt plain list() as a last resort and then filter client-side
                        if (tasks.isEmpty()) {
                            try {
                                java.lang.reflect.Method listAll = query.getClass().getMethod("list");
                                Object resAll = listAll.invoke(query);
                                if (resAll instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<org.flowable.task.api.Task> all = (List) resAll;
                                    log.info("listAll returned {} tasks; performing client-side matching", all.size());
                                    for (var t : all) {
                                        boolean match = false;
                                        try { if (caseInstanceId.equals(t.getProcessInstanceId())) match = true; } catch (Throwable ignore) {}
                                        try { if (caseInstanceId.equals(t.getScopeId())) match = true; } catch (Throwable ignore) {}
                                        try {
                                            java.lang.reflect.Method sr = t.getClass().getMethod("getScopeReference");
                                            Object srVal = sr.invoke(t);
                                            if (caseInstanceId.equals(srVal)) match = true;
                                        } catch (Throwable ignore) {}
                                        if (match) {
                                            log.debug("matched task id={} name={} procId={} scopeId={}", t.getId(), t.getName(), t.getProcessInstanceId(), t.getScopeId());
                                            tasks.add(t);
                                        }
                                    }
                                    log.info("client-side matched {} tasks", tasks.size());
                                }
                            } catch (NoSuchMethodException nsme2) {
                                // cannot list — ignore
                                log.debug("TaskQuery.list() not available");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Best-effort: if reflection fails, just return empty steps
                    log.warn("error while reflecting TaskService/TaskQuery: {}", ignored.toString());
                }

                for (var t : tasks) {
                    Map<String, String> m = new HashMap<>();
                    String key = null;
                    try { key = t.getTaskDefinitionKey(); } catch (Throwable ignore) {}
                    if (key == null || key.isBlank()) key = t.getId();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("taskDefinitionKey", key);
                    m.put("image", "/steps/" + sanitize(key) + ".svg");
                    out.add(m);
                }
            }

            // Ensure UI gets a model fallback when no tasks were discoverable
            if (out.isEmpty()) {
                Map<String, String> fallback = new HashMap<>();
                fallback.put("id", "model");
                fallback.put("name", "Model Diagram");
                fallback.put("taskDefinitionKey", "model");
                fallback.put("image", "/steps/default.svg");
                out.add(fallback);
            }

            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @GetMapping("/{caseInstanceId}/diagram")
    public ResponseEntity<byte[]> getCaseDiagram(@PathVariable String caseInstanceId) {
        try {
            // Try BPMN candidates first, then CMMN
            String[] bpmnCandidates = new String[]{"processes/online-order-process.bpmn", "processes/orderProcess.bpmn", "processes/online-order-process.bpmn"};
            String[] cmmnCandidates = new String[]{"cases/order-lifecycle-advanced.cmmn", "cases/orderCase.cmmn", "cases/order-lifecycle-advanced.cmmn"};

            ClassLoader cl = getClass().getClassLoader();
            java.nio.file.Path xmlFile = null;
            boolean foundBpmn = false;

            for (String p : bpmnCandidates) {
                try (java.io.InputStream is = cl.getResourceAsStream(p)) {
                    if (is != null) {
                        xmlFile = java.nio.file.Files.createTempFile("model-", ".bpmn");
                        java.nio.file.Files.copy(is, xmlFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        foundBpmn = true;
                        break;
                    }
                } catch (Exception ignore) { }
            }

            if (xmlFile == null) {
                for (String p : cmmnCandidates) {
                    try (java.io.InputStream is = cl.getResourceAsStream(p)) {
                        if (is != null) {
                            xmlFile = java.nio.file.Files.createTempFile("model-", ".cmmn");
                            java.nio.file.Files.copy(is, xmlFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            break;
                        }
                    } catch (Exception ignore) { }
                }
            }

            if (xmlFile == null) {
                // fallback to default svg
                try (java.io.InputStream r = cl.getResourceAsStream("static/steps/default.svg")) {
                    if (r != null) {
                        byte[] svg = r.readAllBytes();
                        return ResponseEntity.ok().header("Cache-Control","no-cache").contentType(org.springframework.http.MediaType.valueOf("image/svg+xml")).body(svg);
                    }
                }
                return ResponseEntity.notFound().build();
            }

            java.nio.file.Path out = java.nio.file.Files.createTempFile("diagram-", ".png");
            try {
                // Use core utility to render PNG
                vn.com.fecredit.flowable.exposer.util.ModelValidatorRenderer.renderToPng(xmlFile, out);
                byte[] png = java.nio.file.Files.readAllBytes(out);
                return ResponseEntity.ok().header("Cache-Control","no-cache").contentType(org.springframework.http.MediaType.IMAGE_PNG).body(png);
            } catch (Throwable e) {
                // rendering failed — return svg fallback if available
                try (java.io.InputStream r = cl.getResourceAsStream("static/steps/default.svg")) {
                    if (r != null) {
                        byte[] svg = r.readAllBytes();
                        return ResponseEntity.ok().header("Cache-Control","no-cache").contentType(org.springframework.http.MediaType.valueOf("image/svg+xml")).body(svg);
                    }
                } catch (Exception ignore) { }
                return ResponseEntity.status(500).body((e.getMessage() == null ? e.toString() : e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                try { java.nio.file.Files.deleteIfExists(xmlFile); } catch (Exception ignore) {}
                try { java.nio.file.Files.deleteIfExists(out); } catch (Exception ignore) {}
            }
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            try {
                byte[] b = (err.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(b);
            } catch (Exception ignore) {
                return ResponseEntity.status(500).build();
            }
        }
    }

    @GetMapping("/processes/{filename:.+}")
    public ResponseEntity<byte[]> serveProcessFile(@PathVariable String filename) {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("processes/" + filename)) {
            if (is == null) return ResponseEntity.notFound().build();
            byte[] b = is.readAllBytes();
            String contentType = filename.endsWith(".bpmn") || filename.endsWith(".cmmn") ? "application/xml" : "application/octet-stream";
            return ResponseEntity.ok().header("Cache-Control","no-cache").contentType(org.springframework.http.MediaType.valueOf(contentType)).body(b);
        } catch (java.io.IOException e) {
            byte[] msg = (e.getMessage() == null ? e.toString() : e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(msg);
        }
    }

    private String sanitize(String key) {
        if (key == null) return "default";
        return key.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    @PostMapping("/{caseInstanceId}/reindex")
    public ResponseEntity<?> reindexCase(@PathVariable String caseInstanceId) {
        try {
            Object worker = findCaseDataWorkerBean();
            java.lang.reflect.Method m = findReindexMethod(worker);
            if (m != null) {
                m.invoke(worker, caseInstanceId);
                return ResponseEntity.accepted().build();
            }
            worker.getClass().getMethod("reindexAll", String.class).invoke(worker, "Order");
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    private Object findCaseDataWorkerBean() {
        if (appCtx.containsBean("caseDataWorker")) return appCtx.getBean("caseDataWorker");
        for (String n : appCtx.getBeanDefinitionNames()) {
            if (n.toLowerCase().contains("casedataworker")) return appCtx.getBean(n);
        }
        throw new IllegalStateException("CaseDataWorker bean not available");
    }

    private java.lang.reflect.Method findReindexMethod(Object bean) {
        String[] candidates = new String[]{"reindexByCaseInstanceId", "reindexOne", "reindex", "reindexCase", "processCase"};
        for (String c : candidates) {
            try { return bean.getClass().getMethod(c, String.class); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
