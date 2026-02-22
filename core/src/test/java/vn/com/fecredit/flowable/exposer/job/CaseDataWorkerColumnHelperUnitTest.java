package vn.com.fecredit.flowable.exposer.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDataWorkerColumnHelperUnitTest {

    private static CaseDataWorkerDialectHelper fakeDialect(boolean isH2) {
        // simple stub implementation that returns the provided flag
        return new CaseDataWorkerDialectHelper(null) {
            @Override
            public boolean isH2() {
                return isH2;
            }
        };
    }

    @Test
    void hintMappings_nonH2() {
        CaseDataWorkerColumnHelper helper = new CaseDataWorkerColumnHelper(fakeDialect(false));

        assertThat(helper.determineColumnType(null, "bigint")).isEqualToIgnoringCase("BIGINT");
        assertThat(helper.determineColumnType(null, "decimal")).isEqualToIgnoringCase("DECIMAL(19,4)");
        assertThat(helper.determineColumnType(null, "text")).isEqualToIgnoringCase("LONGTEXT");
        assertThat(helper.determineColumnType(null, "VARCHAR(20)")).isEqualTo("VARCHAR(20)");
    }

    @Test
    void hintMappings_h2() {
        CaseDataWorkerColumnHelper helper = new CaseDataWorkerColumnHelper(fakeDialect(true));

        assertThat(helper.determineColumnType(null, "text")).isEqualToIgnoringCase("CLOB");
    }

    @Test
    void valueBasedMappings() {
        CaseDataWorkerColumnHelper helper = new CaseDataWorkerColumnHelper(fakeDialect(false));

        assertThat(helper.determineColumnType(123, null)).isEqualToIgnoringCase("BIGINT");
        assertThat(helper.determineColumnType(1234.56, null)).isEqualToIgnoringCase("DECIMAL(19,4)");
        assertThat(helper.determineColumnType(true, null)).isEqualToIgnoringCase("BOOLEAN");
        assertThat(helper.determineColumnType("short", null)).isEqualToIgnoringCase("VARCHAR(255)");
        String longStr = new String(new char[2000]).replace('\0', 'x');
        assertThat(helper.determineColumnType(longStr, null)).isEqualToIgnoringCase("LONGTEXT");
    }
}
