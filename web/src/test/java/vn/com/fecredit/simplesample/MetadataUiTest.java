package vn.com.fecredit.simplesample;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class MetadataUiTest {

    @Test
    void metadata_ui_static_file_is_present_on_classpath() throws Exception {
        try (InputStream is = MetadataUiTest.class.getResourceAsStream("/static/admin/metadata-ui.html")) {
            assertThat(is).withFailMessage("expected static/admin/metadata-ui.html on classpath").isNotNull();
            String txt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(txt).contains("Metadata Field‑Check").contains("/api/metadata/field-check");
        }
    }
}
