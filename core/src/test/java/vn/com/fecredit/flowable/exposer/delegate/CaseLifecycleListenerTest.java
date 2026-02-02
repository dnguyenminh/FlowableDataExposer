package vn.com.fecredit.flowable.exposer.delegate;

import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseLifecycleListenerTest {

    @InjectMocks
    private CaseLifecycleListener listener;

    @Mock
    private SysExposeRequestRepository reqRepo;

    @Mock
    private DelegateTask task;

    @Captor
    ArgumentCaptor<SysExposeRequest> reqCaptor;

    @Test
    void notify_nullDelegate_isNoop() {
        // should not throw
        listener.notify(null);
        verifyNoInteractions(reqRepo);
    }

    @Test
    void notify_missingCaseInstanceId_isNoop() {
        when(task.getProcessInstanceId()).thenReturn(null);
        listener.notify(task);
        verifyNoInteractions(reqRepo);
    }

    @Test
    void notify_malformedProcessDefinition_savesWithNullEntityType() {
        when(task.getProcessInstanceId()).thenReturn("case-1");
        when(task.getProcessDefinitionId()).thenReturn("no-colon-format");
        when(task.getAssignee()).thenReturn("joe");

        listener.notify(task);

        verify(reqRepo).save(reqCaptor.capture());
        SysExposeRequest saved = reqCaptor.getValue();
        assertThat(saved.getCaseInstanceId()).isEqualTo("case-1");
        assertThat(saved.getEntityType()).isNull();
        assertThat(saved.getRequestedBy()).isEqualTo("joe");
    }

    @Test
    void notify_validProcessDefinition_savesEntityTypeAndAssignee() {
        when(task.getProcessInstanceId()).thenReturn("case-2");
        when(task.getProcessDefinitionId()).thenReturn("orderProcess:1:abcd");
        when(task.getAssignee()).thenReturn("anna");

        listener.notify(task);

        verify(reqRepo).save(reqCaptor.capture());
        SysExposeRequest saved = reqCaptor.getValue();
        assertThat(saved.getCaseInstanceId()).isEqualTo("case-2");
        assertThat(saved.getEntityType()).isEqualTo("orderProcess");
        assertThat(saved.getRequestedBy()).isEqualTo("anna");
    }

    @Test
    void notify_repositoryThrows_isSwallowed() {
        when(task.getProcessInstanceId()).thenReturn("case-3");
        when(task.getProcessDefinitionId()).thenReturn("orderProcess:1:abcd");
        doThrow(new RuntimeException("db down")).when(reqRepo).save(any());

        // must not propagate
        listener.notify(task);

        verify(reqRepo).save(any());
    }
}
