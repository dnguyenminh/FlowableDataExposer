package vn.com.fecredit.flowable.exposer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrdersUiTest {
    @Test
    void orders_ui_contains_api_path_literal() throws Exception {
        try (java.io.InputStream is = OrdersUiTest.class.getResourceAsStream("/static/admin/orders-ui.html")) {
            String s = new String(is.readAllBytes());
            assertTrue(s.contains('/' + "api/orders"));
        }
    }
}
