package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.service.metadata.MetadataDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MetadataDdlGeneratorEdgeCasesTest {

    @Test
    void not_null_flag_generates_not_null() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "col1";
        fm.plainColumn = "col1";
        fm.type = "string";
        fm.nullable = false;

        String sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("NOT NULL");
    }

    @Test
    void unknown_type_falls_back_to_varchar() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "col2";
        fm.plainColumn = "col2";
        fm.type = "omg-unknown";

        String sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("VARCHAR(255)");
    }

    @Test
    void plainColumn_omitted_uses_column_name() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "col3";
        fm.type = "string";

        String sql = MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm);
        assertThat(sql).contains("col3");
    }

    @Test
    void invalid_identifiers_are_rejected() {
        MetadataDefinition.FieldMapping fm = new MetadataDefinition.FieldMapping();
        fm.column = "weird;name";
        fm.plainColumn = "weird;name";
        fm.type = "string";

        assertThatThrownBy(() -> MetadataDdlGenerator.generateAddColumnIfNotExists("case_plain_order", fm))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MetadataDdlGenerator.generateAddColumnIfNotExists("bad table", fm))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generate_for_multiple_mappings_produces_multiple_idempotent_statements() {
        MetadataDefinition.FieldMapping a = new MetadataDefinition.FieldMapping();
        a.column = "a_col"; a.plainColumn = "a_col"; a.type = "string"; a.exportToPlain = true;
        MetadataDefinition.FieldMapping b = new MetadataDefinition.FieldMapping();
        b.column = "b_col"; b.plainColumn = "b_col"; b.type = "decimal"; b.exportToPlain = true;

        var sqls = MetadataDdlGenerator.generateAddColumnsForMappings("case_plain_order", java.util.List.of(a,b));
        assertThat(sqls).hasSize(2);
        assertThat(sqls.get(0)).contains("ADD COLUMN IF NOT EXISTS a_col");
        assertThat(sqls.get(1)).contains("ADD COLUMN IF NOT EXISTS b_col");
    }
}