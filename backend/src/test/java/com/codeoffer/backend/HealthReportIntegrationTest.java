package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.project.GenTask;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import com.codeoffer.backend.project.GenTaskRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ProjectStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 体检报告接线:成功写入 Markdown;失败降级不阻断主流程。 */
@SpringBootTest
@ActiveProfiles("test")
class HealthReportIntegrationTest {

    @Autowired private ProjectRepository projects;
    @Autowired private GenTaskRepository tasks;
    @Autowired private GenerateService generate;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private AiServiceClient ai;

    private Long testUserId;

    @BeforeEach
    void seedTestUser() {
        AppUser u = users.findByUsername("testuser").orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername("testuser");
            n.setPasswordHash(encoder.encode("pass12345"));
            return users.save(n);
        });
        testUserId = u.getId();
    }

    private void stubAi() {
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake/repo");
        when(ai.analyze(anyString())).thenReturn(
                Map.of("name", "fake", "languages", List.of(), "core_files", List.of(),
                        "stats", Map.of("file_count", 1), "tree", List.of()));
        when(ai.knowledgeCards(any(), anyList())).thenReturn(Map.of("cards", List.of(
                Map.of("type", "architecture", "title", "架构", "content", "内容1"))));
        when(ai.flowDiagram(any())).thenReturn(Map.of("title", "flow", "description", "d", "mermaid", "graph LR"));
        when(ai.resume(any())).thenReturn(
                Map.of("star", Map.of("situation", "s", "task", "t", "action", "a", "result", "r", "summary", "s")));
        when(ai.interviewQuestions(any())).thenReturn(
                Map.of("questions", List.of(Map.of("level", "base", "question", "q", "answer", "a"))));
    }

    private long runGeneration() throws InterruptedException {
        Project p = new Project();
        p.setRepoUrl("https://github.com/octocat/health-" + System.nanoTime());
        p.setName("health");
        p.setUserId(testUserId);
        p = projects.save(p);
        GenTask task = new GenTask();
        task.setProjectId(p.getId());
        task = tasks.save(task);
        generate.runFullGeneration(p.getId(), task.getId());
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(p.getId()).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }
        return p.getId();
    }

    @Test
    void healthReport_storedAsMarkdown() throws Exception {
        stubAi();
        when(ai.healthReport(anyString(), any())).thenReturn(Map.of(
                "verdict", "值得学", "score", 8,
                "reasons", List.of("文档齐全"), "learning_path", List.of("读 README"), "risk", "依赖较旧"));

        long projectId = runGeneration();

        Project updated = projects.findById(projectId).orElseThrow();
        assertEquals(ProjectStatus.REVIEW, updated.getStatus());
        assertTrue(updated.getHealthReport().startsWith("# 项目体检报告"));
        assertTrue(updated.getHealthReport().contains("值得学"));
    }

    @Test
    void healthReportFailure_doesNotBlockGeneration() throws Exception {
        stubAi();
        when(ai.healthReport(anyString(), any()))
                .thenThrow(new IllegalStateException("AI 服务调用失败(HTTP 502)"));

        long projectId = runGeneration();

        Project updated = projects.findById(projectId).orElseThrow();
        assertEquals(ProjectStatus.REVIEW, updated.getStatus());
        assertNull(updated.getHealthReport());
    }
}