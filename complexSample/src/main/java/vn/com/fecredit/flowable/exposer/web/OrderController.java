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
import org.flowable.task.service.TaskService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

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
            List<Map<String, String>> out = new ArrayList<>();

            TaskService taskService = null;
            try {
                if (appCtx.containsBean("taskService")) taskService = appCtx.getBean(TaskService.class);
                else {
                    for (String n : appCtx.getBeanDefinitionNames()) {
                        if (n.toLowerCase().contains("taskservice")) { taskService = appCtx.getBean(n, TaskService.class); break; }
                    }
                }
            } catch (Exception e) { /* ignore */ }

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

                    if (query != null) {
                        java.lang.reflect.Method scopeIdM = query.getClass().getMethod("scopeId", String.class);
                        Object q2 = scopeIdM.invoke(query, caseInstanceId);
                        java.lang.reflect.Method listM = q2.getClass().getMethod("list");
                        Object res = listM.invoke(q2);
                        if (res instanceof List) tasks = (List) res;
                    }
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
            Map<String, Object> err = new HashMap<>();
            err.put("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return ResponseEntity.status(500).body(err);
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
