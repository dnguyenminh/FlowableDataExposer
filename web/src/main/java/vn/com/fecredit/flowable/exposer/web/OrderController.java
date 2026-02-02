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
        Object cmmn = null;
        if (appCtx.containsBean("cmmnRuntimeService")) {
            cmmn = appCtx.getBean("cmmnRuntimeService");
        } else {
            for (String n : appCtx.getBeanDefinitionNames()) {
                if (n.toLowerCase().contains("cmmnruntimeservice")) { cmmn = appCtx.getBean(n); break; }
            }
        }
        if (cmmn == null) throw new IllegalStateException("CMMN runtime not available");
        Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
        builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderCase");
        builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
        Object ci = builder.getClass().getMethod("start").invoke(builder);
        return (String) ci.getClass().getMethod("getId").invoke(ci);
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
