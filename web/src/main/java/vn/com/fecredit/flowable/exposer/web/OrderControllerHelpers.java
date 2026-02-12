package vn.com.fecredit.flowable.exposer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers extracted from {@link OrderController} to keep controller methods short.
 */
public final class OrderControllerHelpers {
    private static final Logger log = LoggerFactory.getLogger(OrderControllerHelpers.class);

    private OrderControllerHelpers() {}

    public static String startCmmnCase(ApplicationContext appCtx, Map<String, Object> vars) throws ReflectiveOperationException {
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

    public static List<Map<String, String>> getCaseSteps(ApplicationContext appCtx, String caseInstanceId) {
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
                Method scopeIdM = query.getClass().getMethod("scopeId", String.class);
                Object q2 = scopeIdM.invoke(query, caseInstanceId);
                Method listM = q2.getClass().getMethod("list");
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
        return out;
    }

    private static String sanitize(String key) {
        if (key == null) return "default";
        return key.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    public static Object findCaseDataWorkerBean(ApplicationContext appCtx) {
        if (appCtx.containsBean("caseDataWorker")) return appCtx.getBean("caseDataWorker");
        for (String n : appCtx.getBeanDefinitionNames()) {
            if (n.toLowerCase().contains("casedataworker")) return appCtx.getBean(n);
        }
        throw new IllegalStateException("CaseDataWorker bean not available");
    }

    public static Method findReindexMethod(Object bean) {
        String[] candidates = new String[]{"reindexByCaseInstanceId", "reindexOne", "reindex", "reindexCase", "processCase"};
        for (String c : candidates) {
            try { return bean.getClass().getMethod(c, String.class); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
