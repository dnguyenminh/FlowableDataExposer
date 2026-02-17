package vn.com.fecredit.simplesample.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrderControllerHelpers {
    private static final Logger log = LoggerFactory.getLogger(OrderControllerHelpers.class);

    private OrderControllerHelpers() {}

    public static List<Map<String, String>> getCaseSteps(ApplicationContext appCtx, String caseInstanceId) {
        List<Map<String, String>> out = new ArrayList<>();
        org.flowable.engine.TaskService taskService = null;
        try {
            if (appCtx.containsBean("taskService")) { taskService = appCtx.getBean(org.flowable.engine.TaskService.class); }
            else { for (String n : appCtx.getBeanDefinitionNames()) { if (n.toLowerCase().contains("taskservice")) { taskService = appCtx.getBean(n, org.flowable.engine.TaskService.class); break; } } }
        } catch (Exception e) { }
        if (taskService != null) {
            List<org.flowable.task.api.Task> tasks = new ArrayList<>();
            try {
                Object query = taskService.getClass().getMethod("createTaskQuery").invoke(taskService);
                Method scopeIdM = query.getClass().getMethod("scopeId", String.class);
                Object q2 = scopeIdM.invoke(query, caseInstanceId);
                Method listM = q2.getClass().getMethod("list");
                Object res = listM.invoke(q2);
                if (res instanceof List) tasks = (List) res;
            } catch (Throwable ignored) {}
            for (var t : tasks) {
                Map<String, String> m = new HashMap<>();
                String key = null; try { key = t.getTaskDefinitionKey(); } catch (Throwable ignore) {}
                if (key == null || key.isBlank()) key = t.getId();
                m.put("id", t.getId()); m.put("name", t.getName()); m.put("taskDefinitionKey", key);
                m.put("image", "/steps/" + sanitize(key) + ".svg"); out.add(m);
            }
        }
        return out;
    }

    private static String sanitize(String key) { if (key == null) return "default"; return key.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(); }

    public static Object findCaseDataWorkerBean(ApplicationContext appCtx) { if (appCtx.containsBean("caseDataWorker")) return appCtx.getBean("caseDataWorker"); for (String n : appCtx.getBeanDefinitionNames()) { if (n.toLowerCase().contains("casedataworker")) return appCtx.getBean(n); } throw new IllegalStateException("CaseDataWorker bean not available"); }

    public static Method findReindexMethod(Object bean) { String[] candidates = new String[]{"reindexByCaseInstanceId", "reindexOne", "reindex", "reindexCase", "processCase"}; for (String c : candidates) { try { return bean.getClass().getMethod(c, String.class); } catch (NoSuchMethodException ignored) {} } return null; }
}
