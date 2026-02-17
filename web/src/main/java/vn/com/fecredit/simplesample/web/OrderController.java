package vn.com.fecredit.simplesample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * OrderController - REST API for managing order-related BPMN processes and CMMN cases.
 * Note: Plain order data is now stored in dynamic work tables (case_plain_order, etc.)
 * created and managed by CaseDataWorker based on metadata definitions.
 * Use the reindex endpoint to trigger data extraction and storage.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeService runtimeService;
    private final org.springframework.context.ApplicationContext appCtx;

    public OrderController(RuntimeService runtimeService,
                           org.springframework.context.ApplicationContext appCtx) {
        this.runtimeService = runtimeService;
        this.appCtx = appCtx;
    }

    /**
     * Start a new order process or case.
     *
     * @param body JSON body with order data
     * @param type optional parameter: "cmmn" for case, otherwise BPMN process
     * @return process/case ID and kind
     */
    @PostMapping
    public ResponseEntity<?> startOrder(@RequestBody JsonNode body, @RequestParam(required = false) String type) {
        try {
            Map<String, Object> vars = extractVars(body);
            boolean isCmmn = "cmmn".equalsIgnoreCase(type);
            String id = isCmmn ? startCmmnCase(vars) : startBpmnProcess(vars);
            String kind = isCmmn ? "case" : "process";
            log.info("Started {} {} with id={}", kind, type != null ? type : "BPMN", id);
            return ResponseEntity.status(201).body(Map.of("id", id, "kind", kind));
        } catch (Exception ex) {
            log.error("Failed to start order: {}", ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Extract variables from request body.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractVars(JsonNode body) {
        JsonNode payload = body.path("payload").isMissingNode() ? body : body.path("payload");
        return mapper.convertValue(payload, Map.class);
    }

    /**
     * Start a BPMN process instance.
     */
    private String startBpmnProcess(Map<String, Object> vars) {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcess", vars);
        log.info("Started BPMN process orderProcess with id={}", pi.getId());
        return pi.getId();
    }

    /**
     * Start a CMMN case instance.
     */
    private String startCmmnCase(Map<String, Object> vars) throws ReflectiveOperationException {
        Object cmmn = null;
        String foundBean = null;
        if (appCtx.containsBean("cmmnRuntimeService")) {
            cmmn = appCtx.getBean("cmmnRuntimeService");
            foundBean = "cmmnRuntimeService";
        } else {
            for (String n : appCtx.getBeanDefinitionNames()) {
                if (n.toLowerCase().contains("cmmnruntimeservice")) {
                    cmmn = appCtx.getBean(n);
                    foundBean = n;
                    break;
                }
            }
        }

        if (cmmn == null) {
            throw new IllegalStateException("CMMN runtime not available");
        }

        log.info("Starting CMMN case via bean {} with vars keys={}", foundBean, vars == null ? 0 : vars.keySet());
        Object builder = cmmn.getClass().getMethod("createCaseInstanceBuilder").invoke(cmmn);
        builder.getClass().getMethod("caseDefinitionKey", String.class).invoke(builder, "orderCase");
        builder.getClass().getMethod("variables", Map.class).invoke(builder, vars);
        Object ci = builder.getClass().getMethod("start").invoke(builder);
        String id = (String) ci.getClass().getMethod("getId").invoke(ci);
        log.info("Started CMMN case id={} (caseDefinitionKey=orderCase)", id);

        // Try to trigger compensating update if CaseDataPersistService is available
        try {
            Class<?> persistServiceClass = Class.forName("vn.com.fecredit.flowable.exposer.service.CaseDataPersistService");
            Object persistService = appCtx.getBean(persistServiceClass);
            persistService.getClass().getMethod("updateCaseInstanceIdForRecent", java.time.Duration.class)
                    .invoke(persistService, java.time.Duration.ofSeconds(5));
            log.info("Compensating update completed for CMMN case id={}", id);
        } catch (Exception e) {
            log.debug("Compensating update not available for CMMN case id={}: {}", id, e.getMessage());
        }

        return id;
    }

    /**
     * Get case instance steps/history.
     *
     * @param caseInstanceId the case instance ID
     * @return list of steps with details
     */
    @GetMapping("/{caseInstanceId}/steps")
    public ResponseEntity<?> getCaseSteps(@PathVariable String caseInstanceId) {
        try {
            List<Map<String, String>> out = OrderControllerHelpers.getCaseSteps(appCtx, caseInstanceId);
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            log.error("Failed to get case steps for {}: {}", caseInstanceId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    /**
     * Trigger reindexing of case data.
     * This extracts data from the case instance and stores it in work tables based on metadata.
     *
     * @param caseInstanceId the case instance ID to reindex
     * @return accepted response
     */
    @PostMapping("/{caseInstanceId}/reindex")
    public ResponseEntity<?> reindexCase(@PathVariable String caseInstanceId) {
        try {
            Object worker = findCaseDataWorkerBean();
            java.lang.reflect.Method m = findReindexMethod(worker);

            if (m != null) {
                m.invoke(worker, caseInstanceId);
                log.info("Triggered reindex for case {}", caseInstanceId);
                return ResponseEntity.accepted().build();
            }

            // Try direct bean access
            try {
                Class<?> caseDataWorkerClass = Class.forName("vn.com.fecredit.flowable.exposer.job.CaseDataWorker");
                Object directWorker = appCtx.getBean(caseDataWorkerClass);
                directWorker.getClass().getMethod("reindexByCaseInstanceId", String.class).invoke(directWorker, caseInstanceId);
                log.info("Triggered reindex for case {} (direct bean)", caseInstanceId);
                return ResponseEntity.accepted().build();
            } catch (Exception ignored) {
                log.debug("Direct CaseDataWorker bean not available");
            }

            // Try reindexAll as fallback
            worker.getClass().getMethod("reindexAll", String.class).invoke(worker, "Order");
            log.info("Triggered reindex all for entity type Order");
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            log.error("Failed to reindex case {}: {}", caseInstanceId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Find CaseDataWorker bean from application context.
     */
    private Object findCaseDataWorkerBean() {
        return OrderControllerHelpers.findCaseDataWorkerBean(appCtx);
    }

    /**
     * Find reindex method in CaseDataWorker.
     */
    private java.lang.reflect.Method findReindexMethod(Object bean) {
        return OrderControllerHelpers.findReindexMethod(bean);
    }
}


