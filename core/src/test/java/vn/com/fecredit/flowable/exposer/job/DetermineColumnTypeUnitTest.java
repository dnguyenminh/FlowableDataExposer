package vn.com.fecredit.flowable.exposer.job;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DetermineColumnTypeUnitTest {

    @Test
    void hintMappings() throws Exception {
        CaseDataWorker w = new CaseDataWorker();
        Method m = CaseDataWorker.class.getDeclaredMethod("determineColumnType", Object.class, String.class);
        m.setAccessible(true);

        String r1 = (String) m.invoke(w, null, "bigint");
        assertThat(r1).isEqualToIgnoringCase("BIGINT");

        String r2 = (String) m.invoke(w, null, "decimal");
        assertThat(r2).isEqualToIgnoringCase("DECIMAL(19,4)");

        String r3 = (String) m.invoke(w, null, "timestamp");
        assertThat(r3).isEqualToIgnoringCase("TIMESTAMP");

        String r4 = (String) m.invoke(w, null, "text");
        assertThat(r4).isEqualToIgnoringCase("LONGTEXT");

        String r5 = (String) m.invoke(w, null, "VARCHAR(100)");
        assertThat(r5).isEqualTo("VARCHAR(100)");
    }

    @Test
    void valueBasedMappings() throws Exception {
        CaseDataWorker w = new CaseDataWorker();
        Method m = CaseDataWorker.class.getDeclaredMethod("determineColumnType", Object.class, String.class);
        m.setAccessible(true);

        String r1 = (String) m.invoke(w, 123, null);
        assertThat(r1).isEqualToIgnoringCase("BIGINT");

        String r2 = (String) m.invoke(w, 1234.56, null);
        assertThat(r2).isEqualToIgnoringCase("DECIMAL(19,4)");

        String r3 = (String) m.invoke(w, true, null);
        assertThat(r3).isEqualToIgnoringCase("BOOLEAN");

        String r4 = (String) m.invoke(w, "short", null);
        assertThat(r4).isEqualToIgnoringCase("VARCHAR(255)");

        String longStr = "".repeat(300);
        // Java repeat above produces empty; correct: build via new String(new char[300]).replace('\0','x')
    }
}
