package vn.com.fecredit.flowable.exposer.flowable;

import org.awaitility.Awaitility;
import org.flowable.task.api.Task;
import org.flowable.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Disabled("canonical integration test present in repo-level src/test/java — see that copy")
@SpringBootTest
class CaseLifecycleIntegrationTest {

    // Disabled duplicate; canonical test lives under the repo-level test tree (to ensure Boot config is discovered).
    // Keeping this placeholder avoids duplicate-work during migration and keeps core's incremental build stable.

}

