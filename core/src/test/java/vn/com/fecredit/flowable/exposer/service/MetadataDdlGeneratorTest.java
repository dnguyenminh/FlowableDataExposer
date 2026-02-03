package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataDdlGeneratorTest {

    @Test
    void maps_metadata_types_to_sql_and_generates_idempotent_ddl() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "shared_col";
        fm.plainColumn = "shared_col";
        fm.type = "string";
        fm.nullable = true;

        String sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("ALTER TABLE case_plain_order ADD COLUMN IF NOT EXISTS shared_col VARCHAR(255)");

        fm.type = "decimal";
        sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("NUMERIC(19,4)");

        fm.type = "timestamp";
        sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("TIMESTAMP");
    }
}