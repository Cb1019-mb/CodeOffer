package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.project.GenTask;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import com.codeoffer.backend.project.GenTaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 API 集成测试:建项目 → 轮询任务 → 查卡片/简历 → 确认入库。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProjectControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private GenTaskRepository tasks;

    @MockitoBean
    private AiServiceClient ai;

    private void stubAi() {
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake/repo");
        when(ai.analyze(anyString())).thenReturn(
                Map.of("name", "fake", "languages", List.of(), "core_files", List.of(),
                        "stats", Map.of("file_count", 1), "tree", List.of()));
        when(ai.knowledgeCards(any(), anyList())).thenReturn(Map.of("cards", List.of(
                Map.of("type", "architecture", "title", "架构", "content", "内容1"),
                Map.of("type", "takeaway", "title", "收获", "content", "内容4"))));
        when(ai.flowDiagram(any())).thenReturn(Map.of("title", "flow", "description", "d", "mermaid", "graph LR"));
        when(ai.resume(any())).thenReturn(
                Map.of("star", Map.of("situation", "s", "task", "t", "action", "a", "result", "r", "summary", "s")));
        when(ai.interviewQuestions(any())).thenReturn(
                Map.of("questions", List.of(Map.of("level", "base", "question", "q", "answer", "a"))));
    }

    @Test
    void createAndQueryProject_worksEndToEnd() throws Exception {
        stubAi();

        MvcResult created = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/Hello-World\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").exists())
                .andReturn();

        long projectId = new tools.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .get("projectId").asLong();

        // 轮询任务直至终态
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(projectId).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }

        mvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"))
                .andExpect(jsonPath("$.cards.length()").value(3)); // 2 卡 + 1 流程图

        mvc.perform(get("/api/projects/{id}/cards", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").exists());

        mvc.perform(get("/api/projects/{id}/resume", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.starContent").exists());

        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cardCount").value(3));

        mvc.perform(post("/api/projects/{id}/confirm", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void createWithBlankUrl_rejected() throws Exception {
        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
