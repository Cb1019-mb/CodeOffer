package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.project.GenTask;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import com.codeoffer.backend.project.GenTaskRepository;
import com.codeoffer.backend.project.KnowledgeCard;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ProjectStatus;
import com.codeoffer.backend.project.ResumeEntry;
import com.codeoffer.backend.project.ResumeEntryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 全链路生成编排集成测试(H2 内存库;AI 客户端全部 mock,不产生真实调用)。
 */
@SpringBootTest
@ActiveProfiles("test")
class GenerateFlowIntegrationTest {

    @Autowired
    private ProjectRepository projects;
    @Autowired
    private KnowledgeCardRepository cards;
    @Autowired
    private ResumeEntryRepository resumes;
    @Autowired
    private GenTaskRepository tasks;
    @Autowired
    private GenerateService generate;

    @MockitoBean
    private AiServiceClient ai;

    private void stubAiSuccess() {
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake/repo");
        when(ai.analyze(anyString())).thenReturn(
                Map.of("name", "fake", "languages", List.of(), "core_files", List.of(),
                        "stats", Map.of("file_count", 1), "tree", List.of()));
        when(ai.knowledgeCards(any(), anyList())).thenReturn(Map.of("cards", List.of(
                Map.of("type", "architecture", "title", "架构", "content", "内容1"),
                Map.of("type", "difficulty", "title", "难点", "content", "内容2"),
                Map.of("type", "highlight", "title", "亮点", "content", "内容3"),
                Map.of("type", "takeaway", "title", "收获", "content", "内容4"),
                Map.of("type", "run_guide", "title", "运行", "content", "内容5"))));
        when(ai.flowDiagram(any())).thenReturn(Map.of("title", "flow", "description", "d", "mermaid", "graph LR"));
        when(ai.resume(any())).thenReturn(
                Map.of("star", Map.of("situation", "s", "task", "t", "action", "a", "result", "r", "summary", "s")));
        when(ai.interviewQuestions(any())).thenReturn(
                Map.of("questions", List.of(Map.of("level", "base", "question", "q", "answer", "a"))));
    }

    private Project newProject(String url) {
        Project p = new Project();
        p.setRepoUrl(url);
        p.setName("proj");
        return projects.save(p);
    }

    private void awaitTerminal(long projectId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(projectId).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("生成任务超时未结束");
    }

    @Test
    void fullGeneration_reachesReviewWithProducts() throws Exception {
        stubAiSuccess();
        Project p = newProject("https://github.com/a/b");

        GenTask task = new GenTask();
        task.setProjectId(p.getId());
        task = tasks.save(task);

        generate.runFullGeneration(p.getId(), task.getId());

        awaitTerminal(p.getId());

        Project updated = projects.findById(p.getId()).orElseThrow();
        assertEquals(ProjectStatus.REVIEW, updated.getStatus());
        assertEquals(6, cards.findByProjectIdOrderByIdAsc(p.getId()).size());
        ResumeEntry entry = resumes.findByProjectId(p.getId()).orElseThrow();
        assertNotNull(entry.getStarContent());
        assertTrue(entry.getInterviewQas().contains("base"));
    }

    @Test
    void failure_setsProjectAndTaskFailed() throws Exception {
        when(ai.cloneRepo(anyString())).thenThrow(new RuntimeException("克隆失败:网络不可达"));
        Project p = newProject("https://github.com/a/b");

        GenTask task = new GenTask();
        task.setProjectId(p.getId());
        task = tasks.save(task);

        generate.runFullGeneration(p.getId(), task.getId());

        awaitTerminal(p.getId());

        assertEquals(ProjectStatus.FAILED, projects.findById(p.getId()).orElseThrow().getStatus());
        GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(p.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, t.getStatus());
        assertNotNull(t.getErrorMsg());
    }
}
