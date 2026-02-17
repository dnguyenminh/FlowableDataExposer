package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class CaseDataWriterTest {

    @Test
    void can_instantiate_writer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CaseDataWriter writer = new CaseDataWriter(jdbc);
        assertThat(writer).isNotNull();
    }
}
