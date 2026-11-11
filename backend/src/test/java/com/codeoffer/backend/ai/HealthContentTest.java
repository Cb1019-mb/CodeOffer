package com.codeoffer.backend.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthContentTest {

    @Test
    void nullResponse_returnsNull() {
        assertNull(GenerateService.buildHealthContent(null));
    }

    @Test
    void fullResponse_rendersAllSections() {
        String md = GenerateService.buildHealthContent(Map.of(
                "verdict", "值得学",
                "score", 8,
                "reasons", List.of("文档齐全", "架构清晰"),
                "learning_path", List.of("读 README", "跑通示例"),
                "risk", "依赖较旧"));
        assertTrue(md.startsWith("# 项目体检报告"));
        assertTrue(md.contains("**结论**：值得学（评分 8/10）"));
        assertTrue(md.contains("- 文档齐全"));
        assertTrue(md.contains("1. 读 README"));
        assertTrue(md.contains("依赖较旧"));
    }

    @Test
    void missingFields_degradesWithoutError() {
        String md = GenerateService.buildHealthContent(Map.of("verdict", "值得浅尝"));
        assertTrue(md.contains("**结论**：值得浅尝"));
        assertTrue(md.contains("## 评估理由"));
    }
}