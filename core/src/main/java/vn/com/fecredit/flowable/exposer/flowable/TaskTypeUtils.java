package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.task.api.Task;

/**
 * Utility to determine whether a Task should be exposed. Extracted to
 * reduce size of the event listener and follow single-responsibility.
 */
public final class TaskTypeUtils {

    private TaskTypeUtils() { /* utility */ }

    public static boolean isAcceptedTaskType(Task task) {
        if (task == null) return false;
        try {
            if (task.getAssignee() != null) return true;

            try {
                java.lang.reflect.Method mc = task.getClass().getMethod("getCategory");
                Object cat = mc.invoke(task);
                if (cat instanceof String) {
                    String s = ((String) cat).toLowerCase();
                    if (s.contains("user") || s.contains("wait")) return true;
                }
            } catch (NoSuchMethodException ignored) {}

            try {
                java.lang.reflect.Method mk = task.getClass().getMethod("getTaskDefinitionKey");
                Object key = mk.invoke(task);
                if (key instanceof String) {
                    String k = ((String) key).toLowerCase();
                    if (k.contains("user") || k.contains("wait") || k.contains("payment") || k.contains("approve")) return true;
                }
            } catch (NoSuchMethodException ignored) {}

            String cls = task.getClass().getSimpleName().toLowerCase();
            if (cls.contains("usertask") || cls.contains("taskentity") || cls.contains("wait")) return true;
        } catch (Throwable t) {
            try { System.err.println("Error while determining task type for task " + task.getId()); } catch (Throwable ignored) {}
        }
        return false;
    }
}
