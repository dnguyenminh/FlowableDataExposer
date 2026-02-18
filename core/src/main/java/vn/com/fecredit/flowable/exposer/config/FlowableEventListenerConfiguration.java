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
     * Register listener with CMMN Engine (use reflection so this class compiles when CMMN
     * engine classes are not present on the classpath).
     */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public EngineConfigurationConfigurer<Object> cmmnEventListenerConfigurer(
            GlobalFlowableEventListener globalFlowableEventListener) {
        return engineConfiguration -> {
            try {
                // Use reflection to call getEventListeners()/setEventListeners() so we don't
                // require compile-time dependency on CMMN configuration classes.
                java.lang.reflect.Method getMethod = engineConfiguration.getClass().getMethod("getEventListeners");
                java.util.List<FlowableEventListener> eventListeners = (java.util.List<FlowableEventListener>) getMethod.invoke(engineConfiguration);
                if (eventListeners == null) {
                    eventListeners = new ArrayList<>();
                    java.lang.reflect.Method setMethod = engineConfiguration.getClass().getMethod("setEventListeners", java.util.List.class);
                    setMethod.invoke(engineConfiguration, eventListeners);
                }

                if (!eventListeners.contains(globalFlowableEventListener)) {
                    eventListeners.add(globalFlowableEventListener);
                }
            } catch (NoSuchMethodException ignored) {
                // Engine configuration doesn't expose event listener hooks; nothing to do
            } catch (Exception e) {
                // Non-fatal: avoid breaking application startup
            }
        };
    }
}
