package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalFlowableEventListenerTest {

    @InjectMocks
    private GlobalFlowableEventListener listener;

    @Mock(lenient = true)
    private TaskExposeHandler taskExposeHandler;

    @Mock(lenient = true)
    private FlowableEntityEvent entityEvent;

    @Mock(lenient = true)
    private Task task;

    @Test
    void onEvent_null_isNoop() {
        // should not throw
        listener.onEvent(null);
        verifyNoInteractions(taskExposeHandler);
    }

    @Test
    void onEvent_nonEntityEvent_isNoop() {
        FlowableEvent ev = mock(FlowableEvent.class);
        when(ev.getType()).thenReturn(FlowableEngineEventType.ENTITY_CREATED);
        listener.onEvent(ev);
        verifyNoInteractions(taskExposeHandler);
    }

    @Test
    void onEvent_nonTaskEntity_isNoop() {
        when(entityEvent.getEntity()).thenReturn(new Object());
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.ENTITY_CREATED);
        listener.onEvent(entityEvent);
        verifyNoInteractions(taskExposeHandler);
    }

    @Test
    void onEvent_taskCompleted_withScope_callsHandler() {
        when(entityEvent.getEntity()).thenReturn(task);
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.TASK_COMPLETED);

        listener.onEvent(entityEvent);

        verify(taskExposeHandler).handle(task, FlowableEngineEventType.TASK_COMPLETED);
    }

    @Test
    void onEvent_taskCompleted_missingScope_callsHandler() {
        when(entityEvent.getEntity()).thenReturn(task);
        when(entityEvent.getType()).thenReturn(FlowableEngineEventType.TASK_COMPLETED);

        listener.onEvent(entityEvent);

        verify(taskExposeHandler).handle(task, FlowableEngineEventType.TASK_COMPLETED);
    }
}



