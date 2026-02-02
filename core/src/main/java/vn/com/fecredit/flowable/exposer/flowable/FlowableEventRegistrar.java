package vn.com.fecredit.flowable.exposer.flowable;

import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

/**
 * Registers a global Flowable event listener on application startup.
 *
 * <p>This class performs a best-effort, reflective registration against the
 * engine's event dispatcher so the module does not need to compile against
 * a specific Flowable distribution. Registration failures are non-fatal and
 * must not prevent application start.</p>
 *
 * Responsibilities:
 * - locate or instantiate an event-dispatcher implementation (reflective)
 * - adapt the configured dispatcher API using a dynamic proxy when needed
 * - register {@code GlobalFlowableEventListener} without throwing on error
 */
@Component
public class FlowableEventRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private GlobalFlowableEventListener globalListener;

    /**
     * Application startup hook — lightweight orchestration only. Keep this
     * method short: heavy logic is delegated to private helpers to preserve
     * testability and the repository style rule (max ~20 lines per method).
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
            Object dispatcher = cfg.getEventDispatcher();

            if (dispatcher == null) {
                dispatcher = tryCreateDispatcherImplementation(cfg);
            }

            if (dispatcher != null) {
                registerListenerSafely(dispatcher);
            }
        } catch (Exception ex) {
            System.err.println("FlowableEventRegistrar failed: " + ex.getMessage());
        }
    }

    /**
     * Try to instantiate a known dispatcher implementation and set it on the
     * engine configuration. Returns the dispatcher instance or {@code null}.
     */
    private Object tryCreateDispatcherImplementation(ProcessEngineConfigurationImpl cfg) {
        try {
            try {
                Class<?> cls = Class.forName("org.flowable.common.engine.impl.delegate.event.DefaultFlowableEventDispatcher");
                Object dispatcher = cls.getDeclaredConstructor().newInstance();
                setDispatcherIfPossible(cfg, dispatcher);
                return dispatcher;
            } catch (ClassNotFoundException cnf1) {
                Class<?> cls = Class.forName("org.flowable.common.engine.impl.event.DefaultFlowableEventDispatcher");
                Object dispatcher = cls.getDeclaredConstructor().newInstance();
                setDispatcherIfPossible(cfg, dispatcher);
                return dispatcher;
            }
        } catch (Throwable ignored) {
            return null; // best-effort: do not fail startup
        }
    }

    /**
     * Reflectively invoke a setter on the engine configuration if available.
     */
    private void setDispatcherIfPossible(ProcessEngineConfigurationImpl cfg, Object dispatcher) {
        try {
            var param = dispatcher.getClass().getInterfaces().length > 0 ? dispatcher.getClass().getInterfaces()[0] : dispatcher.getClass();
            var m = cfg.getClass().getMethod("setEventDispatcher", param);
            m.invoke(cfg, dispatcher);
        } catch (NoSuchMethodException ns) {
            try {
                var m2 = cfg.getClass().getMethod("setEventDispatcher", Object.class);
                m2.invoke(cfg, dispatcher);
            } catch (Exception ignored) {
                // ignore — best-effort
            }
        } catch (Throwable ignored) {
            // ignore — best-effort
        }
    }

    /**
     * Register {@code globalListener} on the given dispatcher using the
     * dispatcher's public {@code addEventListener} method. If the dispatcher
     * expects a specific listener interface a dynamic proxy is created that
     * delegates to {@code GlobalFlowableEventListener#handleEvent(Object)}.
     */
    private void registerListenerSafely(Object dispatcher) {
        try {
            java.lang.reflect.Method addMethod = null;
            for (var m : dispatcher.getClass().getMethods()) {
                if ("addEventListener".equals(m.getName()) && m.getParameterCount() == 1) { addMethod = m; break; }
            }
            if (addMethod == null) return;

            Class<?> paramType = addMethod.getParameterTypes()[0];
            Object toRegister = null;

            if (paramType.isInstance(globalListener) || paramType.isAssignableFrom(Object.class)) {
                toRegister = globalListener;
            } else if (paramType.isInterface()) {
                toRegister = java.lang.reflect.Proxy.newProxyInstance(
                        dispatcher.getClass().getClassLoader(),
                        new Class<?>[]{paramType},
                        (proxy, method, args) -> {
                            try {
                                Object evt = (args != null && args.length > 0) ? args[0] : null;
                                globalListener.handleEvent(evt);
                            } catch (Throwable t) {
                                // swallow — listener must not break dispatcher
                            }
                            return null;
                        }
                );
            }

            if (toRegister != null) {
                addMethod.invoke(dispatcher, toRegister);
            }
        } catch (Throwable ignored) {
            // best-effort: do not fail app startup if we cannot register the global listener
        }
    }
}

