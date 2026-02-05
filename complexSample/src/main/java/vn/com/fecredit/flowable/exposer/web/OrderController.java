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
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
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
        // Ensure boolean variables referenced by CMMN expressions exist
        if (vars == null) vars = new HashMap<>();
        vars.putIfAbsent("isPaid", Boolean.FALSE);
        vars.putIfAbsent("isShipped", Boolean.FALSE);
            try {
                Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
                // CMMN case id in resources is 'orderLifeCycleCase'
                builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderLifeCycleCase");
                builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
                Object ci = builder.getClass().getMethod("start").invoke(builder);
                return (String) ci.getClass().getMethod("getId").invoke(ci);
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
