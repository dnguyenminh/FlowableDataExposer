package vn.com.fecredit.flowable.exposer.config;

import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.com.fecredit.flowable.exposer.flowable.GlobalFlowableEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration to register GlobalFlowableEventListener with Flowable engines.
 *
 * Uses setTypedEventListeners() instead of setEventListeners() because
 * Flowable requires listeners to be registered with specific event types.
 */
@Configuration
public class FlowableEventListenerConfiguration {

    /**
     * Register listener with BPMN Process Engine for all event types
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> bpmnEventListenerConfigurer(
            GlobalFlowableEventListener globalFlowableEventListener) {
        return engineConfiguration -> {
            // Register as a typed event listener for all events
            Map<String, java.util.List<FlowableEventListener>> typedListeners =
                engineConfiguration.getTypedEventListeners();
            
            if (typedListeners == null) {
                typedListeners = new HashMap<>();
                engineConfiguration.setTypedEventListeners(typedListeners);
            }
            
            // Register for all event types from FlowableEngineEventType enum
            for (FlowableEngineEventType eventType : FlowableEngineEventType.values()) {
                java.util.List<FlowableEventListener> listeners =
                    typedListeners.computeIfAbsent(eventType.name(), k -> new java.util.ArrayList<>());
                if (!listeners.contains(globalFlowableEventListener)) {
                    listeners.add(globalFlowableEventListener);
                }
            }
            
            System.out.println("✓ Registered GlobalFlowableEventListener with BPMN Process Engine for all event types");
        };
    }

    /**
     * Register listener with CMMN Engine for all event types
     */
    @Bean
    public EngineConfigurationConfigurer<org.flowable.cmmn.spring.SpringCmmnEngineConfiguration> cmmnEventListenerConfigurer(
            GlobalFlowableEventListener globalFlowableEventListener) {
        return engineConfiguration -> {
            // Register as a typed event listener for all events
            Map<String, java.util.List<FlowableEventListener>> typedListeners =
                engineConfiguration.getTypedEventListeners();
            
            if (typedListeners == null) {
                typedListeners = new HashMap<>();
                engineConfiguration.setTypedEventListeners(typedListeners);
            }
            
            // Register for all event types from FlowableEngineEventType enum
            for (FlowableEngineEventType eventType : FlowableEngineEventType.values()) {
                java.util.List<FlowableEventListener> listeners =
                    typedListeners.computeIfAbsent(eventType.name(), k -> new java.util.ArrayList<>());
                if (!listeners.contains(globalFlowableEventListener)) {
                    listeners.add(globalFlowableEventListener);
                }
            }
            
            System.out.println("✓ Registered GlobalFlowableEventListener with CMMN Engine for all event types");
        };
    }
}
