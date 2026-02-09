package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.task.api.Task;
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
class GlobalFlowableEventListenerTest {

    @InjectMocks
    private GlobalFlowableEventListener listener;

    @Mock
    private SysExposeRequestRepository reqRepo;

    @Mock
    private FlowableEntityEvent entityEvent;

    @Mock
    private Task task;

    @Captor
    ArgumentCaptor<SysExposeRequest> reqCaptor;

    @Test
    void onEvent_null_isNoop() {
        // should not throw
        listener.onEvent(null);
        verifyNoInteractions(reqRepo);
    }

    @Test
    void onEvent_nonEntityEvent_isNoop() {
        FlowableEvent ev = mock(FlowableEvent.class);
        when(ev.getType()).thenReturn(FlowableEngineEventType.ENTITY_CREATED);
        listener.onEvent(ev);
        verifyNoInteractions(reqRepo);
    }

    @Test
    void onEvent_nonTaskEntity_isNoop() {
        when(entityEvent.getEntity()).thenReturn(new Object());
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.ENTITY_CREATED);
        listener.onEvent(entityEvent);
        verifyNoInteractions(reqRepo);
    }

    @Test
    void onEvent_taskCompleted_withScope_savesRequest() {
        when(entityEvent.getEntity()).thenReturn(task);
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.TASK_COMPLETED);
        when(task.getScopeId()).thenReturn("case-1");
        when(task.getScopeDefinitionId()).thenReturn("orderProcess:1:abcd");
        when(task.getAssignee()).thenReturn("joe");

        listener.onEvent(entityEvent);

        verify(reqRepo).save(reqCaptor.capture());
        SysExposeRequest saved = reqCaptor.getValue();
        assertThat(saved.getCaseInstanceId()).isEqualTo("case-1");
        assertThat(saved.getEntityType()).isEqualTo("orderProcess");
        assertThat(saved.getRequestedBy()).isEqualTo("joe");
    }

    @Test
    void onEvent_taskCompleted_missingScope_noSave() {
        when(entityEvent.getEntity()).thenReturn(task);
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.TASK_COMPLETED);
        when(task.getScopeId()).thenReturn(null);

        listener.onEvent(entityEvent);

        verifyNoInteractions(reqRepo);
    }
}
