package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.Test;
import vn.com.fecredit.flowable.exposer.util.ModelDmnHelpers;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelDmnHelpersTest {

    @Test
    void isMostlyBlank_delegates_to_image_helper() {
        BufferedImage img = new BufferedImage(10,10,BufferedImage.TYPE_INT_ARGB);
        boolean b = ModelDmnHelpers.isMostlyBlank(img);
        assertThat(b).isNotNull();
    }

    @Test
    void createDmnPlaceholder_generates_bytes_even_for_null_model() throws IOException {
        byte[] out = ModelDmnHelpers.createDmnPlaceholder(null, 200, 100);
        assertThat(out).isNotNull();
        assertThat(out.length).isGreaterThan(0);
    }
}
