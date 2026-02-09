package vn.com.fecredit.flowable.exposer.config;

import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.com.fecredit.flowable.exposer.flowable.GlobalFlowableEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration to register GlobalFlowableEventListener with Flowable engines.
 *
 * The @Component annotation alone is not sufficient - we must explicitly register
 * the listener with Flowable's ProcessEngineConfiguration and CmmnEngineConfiguration.
 */
@Configuration
public class FlowableEventListenerConfiguration {

    /**
     * Register listener with BPMN Process Engine
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> bpmnEventListenerConfigurer(
            GlobalFlowableEventListener globalFlowableEventListener) {
        return engineConfiguration -> {
            List<FlowableEventListener> eventListeners = engineConfiguration.getEventListeners();
            if (eventListeners == null) {
                eventListeners = new ArrayList<>();
                engineConfiguration.setEventListeners(eventListeners);
            }
            
            // Add our global listener if not already present
            if (!eventListeners.contains(globalFlowableEventListener)) {
                eventListeners.add(globalFlowableEventListener);
            }
        };
    }

    /**
     * Register listener with CMMN Engine
     */
    @Bean
    public EngineConfigurationConfigurer<org.flowable.cmmn.spring.SpringCmmnEngineConfiguration> cmmnEventListenerConfigurer(
            GlobalFlowableEventListener globalFlowableEventListener) {
        return engineConfiguration -> {
            List<FlowableEventListener> eventListeners = engineConfiguration.getEventListeners();
            if (eventListeners == null) {
                eventListeners = new ArrayList<>();
                engineConfiguration.setEventListeners(eventListeners);
            }
            
            // Add our global listener if not already present
            if (!eventListeners.contains(globalFlowableEventListener)) {
                eventListeners.add(globalFlowableEventListener);
            }
        };
    }
}
