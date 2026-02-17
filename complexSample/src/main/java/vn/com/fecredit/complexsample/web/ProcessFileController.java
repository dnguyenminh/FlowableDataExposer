package vn.com.fecredit.complexsample.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RestController
public class ProcessFileController {

    @GetMapping("/processes/{filename:.+}")
    public ResponseEntity<byte[]> serveProcessFile(@PathVariable String filename) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("processes/" + filename)) {
            if (is == null) return ResponseEntity.notFound().build();
            byte[] b = is.readAllBytes();
            String contentType = (filename.endsWith(".bpmn") || filename.endsWith(".cmmn"))
                    ? "application/xml" : "application/octet-stream";
            return ResponseEntity.ok()
                    .header("Cache-Control","no-cache")
                    .contentType(org.springframework.http.MediaType.valueOf(contentType))
                    .body(b);
        } catch (IOException e) {
            byte[] msg = (e.getMessage() == null ? e.toString() : e.getMessage()).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(msg);
        }
    }
}
