package vn.com.fecredit.flowable.exposer.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SysExposeClassDefTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void defaults_and_jsonRoundtrip_work() throws Exception {
        SysExposeClassDef ent = new SysExposeClassDef();
        ent.setClassName("Order");
        ent.setEntityType("Order");
        ent.setVersion(2);
        ent.setJsonDefinition("{\"class\":\"Order\"}");

        assertThat(ent.getEnabled()).isTrue();
        assertThat(ent.getCreatedAt()).isInstanceOf(OffsetDateTime.class);

        String json = om.writeValueAsString(ent);
        SysExposeClassDef r = om.readValue(json, SysExposeClassDef.class);
        assertThat(r.getClassName()).isEqualTo("Order");
        assertThat(r.getVersion()).isEqualTo(2);
    }
}
