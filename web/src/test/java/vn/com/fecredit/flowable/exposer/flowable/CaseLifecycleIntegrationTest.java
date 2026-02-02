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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaseLifecycleIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private SysExposeRequestRepository reqRepo;

    @Autowired
    private GlobalFlowableEventListener globalListener;

    @Test
    void synthetic_event_creates_request_via_listener() {
        String caseId = "case-web-synth-1";
        Object event = new Object() {
            public String getType() { return "TASK_CREATED"; }
            public Object getEntity() {
                return new Object() {
                    public String getProcessInstanceId() { return caseId; }
                    public String getProcessDefinitionId() { return "orderProcess:1:synthetic-web"; }
                    public String getAssignee() { return "s-web"; }
                };
            }
        };

        globalListener.handleEvent(event);

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            assertThat(pending).anySatisfy(r -> {
                assertThat(r.getCaseInstanceId()).isEqualTo(caseId);
                assertThat(r.getEntityType()).isEqualTo("orderProcess");
                assertThat(r.getRequestedBy()).isEqualTo("s-web");
            });
        });
    }

    @Test
    void taskService_save_triggers_event_dispatch_and_request_is_created() {
        String caseId = "case-web-1";

        Task t = taskService.newTask();
        t.setName("web-integ");
        t.setProcessInstanceId(caseId);
        t.setProcessDefinitionId("orderProcess:1:web");
        t.setAssignee("web-user");
        taskService.saveTask(t);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SysExposeRequest> pending = reqRepo.findByStatus("PENDING");
            assertThat(pending).anySatisfy(r -> {
                assertThat(r.getCaseInstanceId()).isEqualTo(caseId);
                assertThat(r.getEntityType()).isEqualTo("orderProcess");
                assertThat(r.getRequestedBy()).isEqualTo("web-user");
            });
        });

        taskService.deleteTask(t.getId(), true);
    }
}
