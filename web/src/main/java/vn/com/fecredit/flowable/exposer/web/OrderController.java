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

/**
 * REST controller exposing the Order surface.
 *
 * Responsibilities:
 * - start BPMN process or CMMN case for orders
 * - expose plain-order rows and single-row queries
 * - trigger reindexing via a CaseDataWorker discovered reflectively
 *
 * Implementation notes:
 * - Keeps compile-time coupling to Flowable CMMN/worker low by using
 *   ApplicationContext + reflection where appropriate.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeService runtimeService;
    // resolve CMMN runtime from the application context at runtime (keeps compile-time coupling low)
    private final CasePlainOrderRepository plainRepo;
    private final org.springframework.context.ApplicationContext appCtx;

    public OrderController(RuntimeService runtimeService,
                           CasePlainOrderRepository plainRepo,
                           org.springframework.context.ApplicationContext appCtx) {
        this.runtimeService = runtimeService;
        this.plainRepo = plainRepo;
        this.appCtx = appCtx;
    }

    /**
     * Start an Order (BPMN or CMMN) and return the instance id and kind.
     *
     * This method delegates to small helpers to keep each method short
     * and easier to review / unit-test.
     */
    @PostMapping
    public ResponseEntity<?> startOrder(@RequestBody JsonNode body, @RequestParam(required = false) String type) {
        try {
            Map<String, Object> vars = extractVars(body);
            boolean isCmmn = "cmmn".equalsIgnoreCase(type);
            String id = isCmmn ? startCmmnCase(vars) : startBpmnProcess(vars);
            String kind = isCmmn ? "case" : "process";
            return ResponseEntity.status(201).body(Map.of("id", id, "kind", kind));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Convert incoming JSON to a variables map. Keeps the public API concise.
     */
    private Map<String, Object> extractVars(JsonNode body) {
        JsonNode payload = body.path("payload").isMissingNode() ? body : body.path("payload");
        return mapper.convertValue(payload, Map.class);
    }

    /**
     * Start the BPMN `orderProcess` and return the process instance id.
     */
    private String startBpmnProcess(Map<String, Object> vars) {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
        return pi.getId();
    }

    /**
     * Start a CMMN case (reflection) and return the case instance id.
     */
    private String startCmmnCase(Map<String, Object> vars) throws ReflectiveOperationException {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderController.class);
        Object cmmn = null;
        String foundBean = null;
        if (appCtx.containsBean("cmmnRuntimeService")) {
            cmmn = appCtx.getBean("cmmnRuntimeService");
            foundBean = "cmmnRuntimeService";
        } else {
            for (String n : appCtx.getBeanDefinitionNames()) {
                if (n.toLowerCase().contains("cmmnruntimeservice")) { cmmn = appCtx.getBean(n); foundBean = n; break; }
            }
        }
        if (cmmn == null) throw new IllegalStateException("CMMN runtime not available");
        log.info("Starting CMMN case via bean {} with vars keys={}", foundBean, vars == null ? 0 : vars.keySet());
        Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
        builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderCase");
        builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
        Object ci = builder.getClass().getMethod("start").invoke(builder);
        String id = (String) ci.getClass().getMethod("getId").invoke(ci);
        log.info("Started CMMN case id={} (caseDefinitionKey=orderCase)", id);
        
        // Compensating update: map recent sys_case_data_store rows to the CMMN case id
        try {
            vn.com.fecredit.flowable.exposer.service.CaseDataPersistService persistService =
                appCtx.getBean(vn.com.fecredit.flowable.exposer.service.CaseDataPersistService.class);
            persistService.updateCaseInstanceIdForRecent(id, java.time.Duration.ofSeconds(5));
            log.info("Compensating update completed for CMMN case id={}", id);
        } catch (Exception e) {
            log.warn("Compensating update failed for CMMN case id={}: {}", id, e.getMessage());
        }
        
        return id;
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
            out.add(Map.of(
                    "caseInstanceId", p.getCaseInstanceId(),
                    "orderTotal", p.getOrderTotal(),
                    "orderPriority", p.getOrderPriority(),
                    "approvalStatus", p.getApprovalStatus()
            ));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Get active steps/tasks for a case instance. Returns a list of task metadata.
     */
    @GetMapping("/{caseInstanceId}/steps")
    public ResponseEntity<?> getCaseSteps(@PathVariable String caseInstanceId) {
        try {
            List<Map<String, String>> out = new ArrayList<>();
            org.flowable.engine.TaskService taskService = null;
            try {
                if (appCtx.containsBean("taskService")) {
                    taskService = appCtx.getBean(org.flowable.engine.TaskService.class);
                } else {
                    for (String n : appCtx.getBeanDefinitionNames()) {
                        if (n.toLowerCase().contains("taskservice")) {
                            taskService = appCtx.getBean(n, org.flowable.engine.TaskService.class);
                            break;
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }

            if (taskService != null) {
                List<org.flowable.task.api.Task> tasks = new ArrayList<>();
                try {
                    Object query = taskService.getClass().getMethod("createTaskQuery").invoke(taskService);
                    java.lang.reflect.Method scopeIdM = query.getClass().getMethod("scopeId", String.class);
                    Object q2 = scopeIdM.invoke(query, caseInstanceId);
                    java.lang.reflect.Method listM = q2.getClass().getMethod("list");
                    Object res = listM.invoke(q2);
                    if (res instanceof List) tasks = (List) res;
                } catch (Throwable ignored) {
                    // Best-effort: if reflection fails, just return empty steps
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

            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    private String sanitize(String key) {
        if (key == null) return "default";
        return key.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    /**
     * Trigger reindexing for a single case instance (or entity-wide fallback).
     *
     * Uses ApplicationContext + reflection to locate the runtime worker and
     * call one of several candidate methods. Keeps the public method short
     * and delegates discovery/invocation to small helpers.
     */
    @PostMapping("/{caseInstanceId}/reindex")
    public ResponseEntity<?> reindexCase(@PathVariable String caseInstanceId) {
        try {
            Object worker = findCaseDataWorkerBean();
            java.lang.reflect.Method m = findReindexMethod(worker);
            if (m != null) {
                m.invoke(worker, caseInstanceId);
                return ResponseEntity.accepted().build();
            }
            // fallback: try direct bean call by type (handles proxy/reflection edge-cases)
            try {
                vn.com.fecredit.flowable.exposer.job.CaseDataWorker direct = appCtx.getBean(vn.com.fecredit.flowable.exposer.job.CaseDataWorker.class);
                direct.reindexByCaseInstanceId(caseInstanceId);
                return ResponseEntity.accepted().build();
            } catch (Exception ignored) {
            }
            // fallback to entity-wide reindex
            worker.getClass().getMethod("reindexAll", String.class).invoke(worker, "Order");
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Locate the CaseDataWorker bean by known name or by scanning bean names.
     */
    private Object findCaseDataWorkerBean() {
        if (appCtx.containsBean("caseDataWorker")) return appCtx.getBean("caseDataWorker");
        for (String n : appCtx.getBeanDefinitionNames()) {
            if (n.toLowerCase().contains("casedataworker")) return appCtx.getBean(n);
        }
        throw new IllegalStateException("CaseDataWorker bean not available");
    }

    /**
     * Find a method on the worker that accepts a single String caseInstanceId.
     */
    private java.lang.reflect.Method findReindexMethod(Object bean) {
        String[] candidates = new String[]{"reindexByCaseInstanceId", "reindexOne", "reindex", "reindexCase", "processCase"};
        for (String c : candidates) {
            try { return bean.getClass().getMethod(c, String.class); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
