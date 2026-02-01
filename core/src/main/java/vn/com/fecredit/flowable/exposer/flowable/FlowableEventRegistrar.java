package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

@Component
public class FlowableEventRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private GlobalFlowableEventListener globalListener;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
            Object dispatcher = cfg.getEventDispatcher();
            if (dispatcher == null) {
                // try to instantiate a default dispatcher implementation reflectively (best-effort)
                try {
                    Class<?> cls = Class.forName("org.flowable.common.engine.impl.delegate.event.DefaultFlowableEventDispatcher");
                    dispatcher = cls.getDeclaredConstructor().newInstance();
                } catch (ClassNotFoundException cnf1) {
                    try {
                        Class<?> cls = Class.forName("org.flowable.common.engine.impl.event.DefaultFlowableEventDispatcher");
                        dispatcher = cls.getDeclaredConstructor().newInstance();
                    } catch (ClassNotFoundException cnf2) {
                        // give up creating a dispatcher; rely on existing engine dispatcher
                        dispatcher = null;
                    }
                }
                if (dispatcher != null) {
                    try {
                        var m = cfg.getClass().getMethod("setEventDispatcher", dispatcher.getClass().getInterfaces().length > 0 ? dispatcher.getClass().getInterfaces()[0] : dispatcher.getClass());
                        m.invoke(cfg, dispatcher);
                    } catch (NoSuchMethodException ns) {
                        // fallback: try generic setter by name
                        try {
                            var m2 = cfg.getClass().getMethod("setEventDispatcher", Object.class);
                            m2.invoke(cfg, dispatcher);
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (dispatcher != null) {
                // addEventListener via reflection to avoid tight coupling
                try {
                    var addMethod = dispatcher.getClass().getMethod("addEventListener", org.flowable.common.engine.api.delegate.event.FlowableEventListener.class);
                    addMethod.invoke(dispatcher, globalListener);
                } catch (NoSuchMethodException ns) {
                    // try a more permissive signature
                    try {
                        var addMethod2 = dispatcher.getClass().getMethod("addEventListener", Object.class);
                        addMethod2.invoke(dispatcher, globalListener);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            System.err.println("FlowableEventRegistrar failed: " + ex.getMessage());
        }
    }
}
