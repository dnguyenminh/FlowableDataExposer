package vn.com.fecredit.flowable.exposer.flowable;

import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("canonical integration test present in repo-level src/test/java — see that copy")
@SpringBootTest(classes = vn.com.fecredit.flowable.exposer.FlowableExposerTestApplicationFinal.class)
class CaseLifecycleIntegrationTest {

    // Disabled duplicate; canonical test lives under the repo-level test tree (to ensure Boot config is discovered).
    // Keeping this placeholder avoids duplicate-work during migration and keeps core's incremental build stable.

}

