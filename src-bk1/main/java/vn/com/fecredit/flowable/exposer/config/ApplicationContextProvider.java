package vn.com.fecredit.flowable.exposer.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Simple holder to expose the Spring ApplicationContext to non-managed classes (used by plain CMMN delegates).
 * Keep usage minimal and only for bridge purposes in tests/integration scenarios.
 */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {

    private static ApplicationContext CONTEXT;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        CONTEXT = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        return CONTEXT;
    }
}
