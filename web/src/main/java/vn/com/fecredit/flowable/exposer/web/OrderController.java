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

    @PostMapping
    public ResponseEntity<?> startOrder(@RequestBody JsonNode body, @RequestParam(required = false) String type) {
        try {
            JsonNode payload = body.path("payload").isMissingNode() ? body : body.path("payload");
            Map<String, Object> vars = mapper.convertValue(payload, Map.class);
            String id;
            String kind;
            if ("cmmn".equalsIgnoreCase(type)) {
                // locate CMMN runtime bean by name (keeps compile-time coupling low and avoids accidental Object injection)
                Object cmmn = null;
                if (appCtx.containsBean("cmmnRuntimeService")) cmmn = appCtx.getBean("cmmnRuntimeService");
                else {
                    for (String n : appCtx.getBeanDefinitionNames()) if (n.toLowerCase().contains("cmmnruntimeservice")) { cmmn = appCtx.getBean(n); break; }
                }
                if (cmmn == null) throw new IllegalStateException("CMMN runtime not available");
                // use reflection so web module doesn't need Flowable CMMN on its compile classpath
                Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
                builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderCase");
                builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
                Object ci = builder.getClass().getMethod("start").invoke(builder);
                id = (String) ci.getClass().getMethod("getId").invoke(ci);
                kind = "case";
            } else {
                ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
                id = pi.getId();
                kind = "process";
            }
            return ResponseEntity.status(201).body(Map.of("id", id, "kind", kind));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
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
            out.add(Map.of(
                    "caseInstanceId", p.getCaseInstanceId(),
                    "orderTotal", p.getOrderTotal(),
                    "orderPriority", p.getOrderPriority(),
                    "approvalStatus", p.getApprovalStatus()
            ));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{caseInstanceId}/reindex")
    public ResponseEntity<?> reindexCase(@PathVariable String caseInstanceId) {
        try {
            Object bean = null;
            // prefer direct bean by name
            if (appCtx.containsBean("caseDataWorker")) bean = appCtx.getBean("caseDataWorker");
            // fallback: locate any bean with casedataworker in its name (keeps compile-time coupling low)
            if (bean == null) {
                for (String n : appCtx.getBeanDefinitionNames()) {
                    if (n.toLowerCase().contains("casedataworker")) { bean = appCtx.getBean(n); break; }
                }
            }
             if (bean == null) throw new IllegalStateException("CaseDataWorker bean not available");

             // try several likely method names
             java.lang.reflect.Method m = null;
            String[] candidates = new String[]{"reindexByCaseInstanceId", "reindexOne", "reindex", "reindexCase", "processCase"};
            for (String c : candidates) {
                try { m = bean.getClass().getMethod(c, String.class); break; } catch (NoSuchMethodException ignored) {}
            }
            if (m != null) {
                m.invoke(bean, caseInstanceId);
                return ResponseEntity.accepted().build();
            }

            // fallback: trigger entity-wide reindex for Order
            java.lang.reflect.Method all = bean.getClass().getMethod("reindexAll", String.class);
            all.invoke(bean, "Order");
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }
}
