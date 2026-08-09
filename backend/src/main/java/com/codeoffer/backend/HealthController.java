package com.codeoffer.backend;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M0 健康检查端点:验证 Java 层存活,并作为前端/运维探活入口。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "backend",
                "version", "0.0.1-SNAPSHOT");
    }
}
