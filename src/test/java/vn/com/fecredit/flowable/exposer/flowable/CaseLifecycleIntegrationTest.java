package vn.com.fecredit.flowable.exposer.flowable;

import org.awaitility.Awaitility;
import org.flowable.task.api.Task;
import org.flowable.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Disabled("integration test moved to web module; see web/src/test/java/...")
@SpringBootTest
@org.springframework.test.context.ContextConfiguration(classes = CaseLifecycleIntegrationTest.TestConfig.class)
class CaseLifecycleIntegrationTest {

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.ComponentScan(basePackages = "vn.com.fecredit.flowable.exposer")
    static class TestConfig {}

    @Autowired
    private GlobalFlowableEventListener globalListener;

    @Autowired
    private SysExposeRequestRepository reqRepo;

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    @Test
    void synthetic_task_event_saved_by_global_listener() {
        String caseId = "case-int-synth-1";

        // minimal synthetic event that mimics Flowable's task event shape
        Object event = new Object() {
            public String getType() { return "TASK_CREATED"; }
            public Object getEntity() {
                return new Object() {
                    public String getProcessInstanceId() { return caseId; }
                    public String getProcessDefinitionId() { return "orderProcess:1:synthetic"; }
                    public String getAssignee() { return "synthetic-user"; }
                };
            }
        };

        globalListener.handleEvent(event);

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            assertThat(pending).anySatisfy(r -> {
                assertThat(r.getCaseInstanceId()).isEqualTo(caseId);
                assertThat(r.getEntityType()).isEqualTo("orderProcess");
                assertThat(r.getRequestedBy()).isEqualTo("synthetic-user");
            });
        });
    }

    @Test
    void when_taskService_available_engine_emits_events_and_request_is_created() throws Exception {
        // This test exercises the full engine -> dispatcher path **if** TaskService is present.
        Object maybeTaskService = null;
        try {
            maybeTaskService = ctx.getBean("taskService");
        } catch (Exception ignored) { /* bean absent on constrained classpaths */ }

        org.junit.jupiter.api.Assumptions.assumeTrue(maybeTaskService != null, "TaskService not available; skipping engine dispatch test");

        // Use reflection so the test compiles even when Flowable task API is not on the compile classpath
        Object taskService = maybeTaskService;
        Object task = taskService.getClass().getMethod("newTask").invoke(taskService);
        task.getClass().getMethod("setName", String.class).invoke(task, "integration-reflect-task");
        task.getClass().getMethod("setProcessInstanceId", String.class).invoke(task, "case-int-2");
        task.getClass().getMethod("setProcessDefinitionId", String.class).invoke(task, "orderProcess:1:reflect");
        task.getClass().getMethod("setAssignee", String.class).invoke(task, "reflect-user");
        taskService.getClass().getMethod("saveTask", task.getClass().getInterfaces().length > 0 ? task.getClass().getInterfaces()[0] : task.getClass()).invoke(taskService, task);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            assertThat(pending).anySatisfy(r -> {
                assertThat(r.getCaseInstanceId()).isEqualTo("case-int-2");
                assertThat(r.getEntityType()).isEqualTo("orderProcess");
                assertThat(r.getRequestedBy()).isEqualTo("reflect-user");
            });
        });

        // try best-effort cleanup
        try {
            taskService.getClass().getMethod("deleteTask", String.class, boolean.class).invoke(taskService, task.getClass().getMethod("getId").invoke(task).toString(), true);
        } catch (Exception ignored) {}
    }
}
