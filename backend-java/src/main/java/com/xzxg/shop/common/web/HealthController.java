package com.xzxg.shop.common.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provisional health endpoint. The response shape must be confirmed against
 * {@code handleHealth} in the Go baseline before clients rely on extra fields.
 */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
