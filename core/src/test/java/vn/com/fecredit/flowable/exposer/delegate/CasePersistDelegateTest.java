package vn.com.fecredit.flowable.exposer.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.fecredit.flowable.exposer.service.MetadataAnnotator;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CasePersistDelegateTest {

    @InjectMocks
    private CasePersistDelegate delegate;

    @Mock
    private ObjectMapper om;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private MetadataAnnotator annotator;

    @Mock
    private DelegateExecution execution;

    @Captor
    ArgumentCaptor<Map> mapCaptor;

    @Test
    void execute_callsJdbcWithJson_andInvokesAnnotator() throws Exception {
        doReturn("case-1").when(execution).getProcessInstanceId();

        Map<String, Object> vars = new HashMap<>();
        vars.put("orderId", 12345);
        Map<String, Object> customer = new HashMap<>();
        customer.put("id", "CUST01");
        vars.put("customer", customer);

        doReturn(vars).when(execution).getVariables();
        doReturn("{\"orderId\":12345}").when(om).writeValueAsString(org.mockito.Mockito.any());

        delegate.execute(execution);

        verify(annotator).annotate(mapCaptor.capture(), eq("Order"));
        Map captured = mapCaptor.getValue();
        assertThat(captured).containsEntry("orderId", 12345);
        assertThat(captured).containsKey("@class");

        verify(jdbc).update(org.mockito.Mockito.startsWith("INSERT INTO sys_case_data_store"), eq("case-1"), eq("Order"), eq("{\"orderId\":12345}"));
    }

    @Test
    void execute_fallsBackToToStringWhenObjectMapperFails() throws Exception {
        doReturn("case-2").when(execution).getProcessInstanceId();
        Map<String, Object> vars = new HashMap<>();
        vars.put("x", "y");
        doReturn(vars).when(execution).getVariables();

        doThrow(new RuntimeException("boom")).when(om).writeValueAsString(org.mockito.Mockito.any());

        delegate.execute(execution);

        verify(jdbc).update(org.mockito.Mockito.startsWith("INSERT INTO sys_case_data_store"), org.mockito.Mockito.eq("case-2"), org.mockito.Mockito.eq("Order"), org.mockito.Mockito.contains("x=y"));
    }
}
