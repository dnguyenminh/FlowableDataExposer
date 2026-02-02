package vn.com.fecredit.flowable.exposer.delegate;

import org.flowable.dmn.api.ExecuteDecisionBuilder;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DmnDecisionDelegateTest {

    @Test
    void fallback_whenNoDmnDecisionService_shouldEvaluateInJava() {
        // provider that returns null -> triggers Java fallback
        @SuppressWarnings("unchecked")
        ObjectProvider<DmnDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        DmnDecisionDelegate delegate = new DmnDecisionDelegate(provider);

        DelegateExecution exec = mock(DelegateExecution.class);
        when(exec.getVariable("total")).thenReturn(2_000_000);
        Map<String,Object> params = new HashMap<>();
        params.put("region", "HCM");
        when(exec.getVariable("params")).thenReturn(params);

        delegate.execute(exec);

        verify(exec).setVariable(eq("orderRules"), any(Map.class));
        verify(exec).setVariable("discount", 0.1);
        verify(exec).setVariable("shippingFee", 0.0);
        verify(exec).setVariable("approvalDecision", Map.of());
    }

    @Test
    void engine_path_shouldUseDmnDecisionService_builder_and_expose_outputs() {
        DmnDecisionService dmnService = mock(DmnDecisionService.class);
        ExecuteDecisionBuilder builder = mock(ExecuteDecisionBuilder.class);

        // prepare engine result
        Map<String,Object> r = new HashMap<>();
        r.put("discount", 0.2);
        r.put("shippingFee", 12345.0);

        when(dmnService.createExecuteDecisionBuilder()).thenReturn(builder);
        when(builder.decisionKey(eq("orderRulesTable"))).thenReturn(builder);
        when(builder.decisionKey(eq("orderRules"))).thenReturn(builder);
        when(builder.variables(anyMap())).thenReturn(builder);
        when(builder.execute()).thenReturn(List.of(r));

        @SuppressWarnings("unchecked")
        ObjectProvider<DmnDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dmnService);

        DmnDecisionDelegate delegate = new DmnDecisionDelegate(provider);

        DelegateExecution exec = mock(DelegateExecution.class);
        Map<String,Object> vars = new HashMap<>();
        vars.put("total", 50);
        when(exec.getVariables()).thenReturn(vars);

        delegate.execute(exec);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(exec).setVariable(eq("orderRules"), captor.capture());
        Map<String,Object> captured = captor.getValue();
        assertThat(captured).containsEntry("discount", 0.2d).containsEntry("shippingFee", 12345.0d);

        verify(exec).setVariable("discount", 0.2d);
        verify(exec).setVariable("shippingFee", 12345.0d);
        verify(exec).setVariable("approvalDecision", Map.of());
    }

    @Test
    void engine_path_shouldHandleLegacyDecisionId() {
        DmnDecisionService dmnService = mock(DmnDecisionService.class);
        ExecuteDecisionBuilder builder = mock(ExecuteDecisionBuilder.class);

        Map<String,Object> r = new HashMap<>();
        r.put("discount", 0.15);
        r.put("shippingFee", 22222.0);

        when(dmnService.createExecuteDecisionBuilder()).thenReturn(builder);
        when(builder.decisionKey(eq("orderRulesTable"))).thenReturn(builder);
        when(builder.decisionKey(eq("orderRules"))).thenReturn(builder);
        when(builder.variables(anyMap())).thenReturn(builder);
        when(builder.execute()).thenReturn(List.of(r));

        @SuppressWarnings("unchecked")
        ObjectProvider<DmnDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dmnService);

        DmnDecisionDelegate delegate = new DmnDecisionDelegate(provider);

        DelegateExecution exec = mock(DelegateExecution.class);
        when(exec.getVariables()).thenReturn(Map.of("total", 10));

        delegate.execute(exec);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(exec).setVariable(eq("orderRules"), captor.capture());
        Map<String,Object> captured = captor.getValue();
        assertThat(captured).containsEntry("discount", 0.15d).containsEntry("shippingFee", 22222.0d);

        verify(exec).setVariable("discount", 0.15d);
        verify(exec).setVariable("shippingFee", 22222.0d);
        verify(exec).setVariable("approvalDecision", Map.of());
    }

    @Test
    void engine_path_handlesMultiResult_setsListWithoutTopLevelOutputs() {
        DmnDecisionService dmnService = mock(DmnDecisionService.class);
        ExecuteDecisionBuilder builder = mock(ExecuteDecisionBuilder.class);

        Map<String,Object> r1 = new HashMap<>();
        r1.put("discount", 0.1);
        Map<String,Object> r2 = new HashMap<>();
        r2.put("shippingFee", 11111.0);

        when(dmnService.createExecuteDecisionBuilder()).thenReturn(builder);
        when(builder.decisionKey(eq("orderRulesTable"))).thenReturn(builder);
        when(builder.decisionKey(eq("orderRules"))).thenReturn(builder);
        when(builder.variables(anyMap())).thenReturn(builder);
        when(builder.execute()).thenReturn(List.of(r1, r2));

        @SuppressWarnings("unchecked")
        ObjectProvider<DmnDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dmnService);

        DmnDecisionDelegate delegate = new DmnDecisionDelegate(provider);

        DelegateExecution exec = mock(DelegateExecution.class);
        when(exec.getVariables()).thenReturn(Map.of("total", 10));

        delegate.execute(exec);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(exec).setVariable(eq("orderRules"), captor.capture());
        List<Map<String,Object>> captured = captor.getValue();
        assertThat(captured).hasSize(2);

        verify(exec, never()).setVariable(eq("discount"), any());
        verify(exec, never()).setVariable(eq("shippingFee"), any());
        verify(exec).setVariable("approvalDecision", Map.of());
    }

    @Test
    void engine_path_emptyResult_setsEmptyMaps() {
        DmnDecisionService dmnService = mock(DmnDecisionService.class);
        ExecuteDecisionBuilder builder = mock(ExecuteDecisionBuilder.class);

        when(dmnService.createExecuteDecisionBuilder()).thenReturn(builder);
        when(builder.decisionKey(eq("orderRulesTable"))).thenReturn(builder);
        when(builder.decisionKey(eq("orderRules"))).thenReturn(builder);
        when(builder.variables(anyMap())).thenReturn(builder);
        when(builder.execute()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<DmnDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dmnService);

        DmnDecisionDelegate delegate = new DmnDecisionDelegate(provider);

        DelegateExecution exec = mock(DelegateExecution.class);
        when(exec.getVariables()).thenReturn(Map.of());

        delegate.execute(exec);

        verify(exec).setVariable("orderRules", Collections.emptyMap());
        verify(exec).setVariable("approvalDecision", Map.of());
    }
}

