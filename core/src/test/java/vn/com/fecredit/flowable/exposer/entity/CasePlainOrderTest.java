package vn.com.fecredit.flowable.exposer.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CasePlainOrder} - basic POJO / mapping expectations.
 */
class CasePlainOrderTest {

    @Test
    void shouldHaveNonNullTimestampsByDefault() {
        CasePlainOrder p = new CasePlainOrder();
        assertThat(p.getCreatedAt()).isNotNull();
        assertThat(p.getUpdatedAt()).isNotNull();
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(Duration.between(p.getCreatedAt(), now).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void shouldSerializeToJson_andPreserveFields() throws Exception {
        CasePlainOrder p = new CasePlainOrder();
        p.setCaseInstanceId("case-xyz");
        p.setOrderTotal(1234.56);
        p.setCustomerId("CUST1");
        p.setOrderPriority("HIGH");
        p.setPlainPayload("{\"orderId\":\"O-1\"}");

        String json = new ObjectMapper().writeValueAsString(p);
        assertThat(json).contains("case-xyz");
        assertThat(json).contains("1234.56");
        assertThat(json).contains("CUST1");
    }

    @Test
    void shouldAcceptLargePlainPayload() {
        CasePlainOrder p = new CasePlainOrder();
        String big = "x".repeat(20_000);
        p.setPlainPayload(big);
        assertThat(p.getPlainPayload()).hasSize(20_000);
        assertThat(p.getPlainPayload()).startsWith("xxx");
    }
}
