package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MetadataDdlFromResolverTest {

    @Autowired
    MetadataResolver resolver;

    @Test
    void generate_ddl_for_order_export_mappings() {
        var mappings = resolver.mappingsMetadataFor("Order");
        var sqls = MetadataDdlGenerator.generateAddColumnsForMappings("case_plain_order", mappings.values());
        // Order.json in test fixtures requests exportToPlain for customer_id and order_total
        assertThat(sqls).anyMatch(s -> s.contains("order_total") && s.contains("NUMERIC"));
        assertThat(sqls).anyMatch(s -> s.contains("customer_id") && s.contains("VARCHAR"));
    }
}