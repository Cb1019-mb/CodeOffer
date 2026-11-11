package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    @MockitoBean
    private AiServiceClient ai;

    private static int urlSeq = 0;

    @BeforeEach
    void seedTestUser() {
        if (users.findByUsername("testuser").isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername("testuser");
            u.setPasswordHash(encoder.encode("pass12345"));
            users.save(u);
        }
    }

    /** 每次创建使用唯一 URL,避免重复录入检测(409)干扰测试。 */
    private static String uniqueUrl() {
        return "https://github.com/octocat/Hello-World-" + (++urlSeq);
    }

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
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + uniqueUrl() + "\"}"))
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

        mvc.perform(get("/api/projects/{id}", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"))
                .andExpect(jsonPath("$.cards.length()").value(3)); // 2 卡 + 1 流程图

        mvc.perform(get("/api/projects/{id}/cards", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").exists());

        mvc.perform(get("/api/projects/{id}/resume", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.starContent").exists());

        mvc.perform(get("/api/projects").with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cardCount").value(3));

        mvc.perform(post("/api/projects/{id}/confirm", projectId).with(user("testuser")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void createWithBlankUrl_rejected() throws Exception {
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithUnsafeUrl_rejected() throws Exception {
        // 非 https/file、非受信任平台、携带凭证的 URL 均拒绝
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"http://169.254.169.254/latest/meta-data\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"ssh://git@github.com/octocat/Hello-World.git\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://user:token@github.com/octocat/Hello-World\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDuplicateUrl_conflict() throws Exception {
        stubAi();
        String url = uniqueUrl();
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmBeforeReview_conflict() throws Exception {
        // 手工构造「生成中」状态:项目 PENDING + 任务 RUNNING,confirm 应 409
        Project p = new Project();
        p.setRepoUrl(uniqueUrl());
        p.setStatus(ProjectStatus.PENDING);
        p = projects.save(p);
        GenTask t = new GenTask();
        t.setProjectId(p.getId());
        t.setStatus(TaskStatus.RUNNING);
        tasks.save(t);

        mvc.perform(post("/api/projects/{id}/confirm", p.getId()).with(user("testuser")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void regenerateWhileRunning_conflict() throws Exception {
        // 手工构造「生成中」状态:已有 RUNNING 任务时 regenerate 应 409
        Project p = new Project();
        p.setRepoUrl(uniqueUrl());
        p.setStatus(ProjectStatus.GENERATING);
        p = projects.save(p);
        GenTask t = new GenTask();
        t.setProjectId(p.getId());
        t.setStatus(TaskStatus.RUNNING);
        tasks.save(t);

        mvc.perform(post("/api/projects/{id}/regenerate", p.getId()).with(user("testuser")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void editCard_marksEditedAndPersists() throws Exception {
        stubAi();
        long projectId = createAndAwait();
        long cardId = firstCardId(projectId);

        // 编辑第一张卡片
        mvc.perform(put("/api/projects/{id}/cards/{cardId}/content", projectId, cardId)
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"用户手写内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.content").value("用户手写内容"));

        // 重新生成:已编辑内容不被覆盖
        mvc.perform(post("/api/projects/{id}/cards/{cardId}/regenerate", projectId, cardId).with(user("testuser")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.content").value("用户手写内容"))
                .andExpect(jsonPath("$.aiDraft").value("内容1"));
    }

    @Test
    void userInput_appendsOnRunGuideCard() throws Exception {
        stubAi();
        long projectId = createAndAwait();
        long cardId = firstCardId(projectId);

        mvc.perform(post("/api/projects/{id}/cards/{cardId}/user-input", projectId, cardId)
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"JDK 需 17+\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userInput").value("JDK 需 17+"))
                .andExpect(jsonPath("$.edited").value(true));
    }

    @Test
    void editResumeStar_persists() throws Exception {
        stubAi();
        long projectId = createAndAwait();

        mvc.perform(put("/api/projects/{id}/resume/star", projectId)
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starContent\": \"{\\\"summary\\\":\\\"改过的亮点\\\"}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.starContent").value("{\"summary\":\"改过的亮点\"}"));
    }

    @Test
    void reviewCard_updatesMasteryAndQueue() throws Exception {
        stubAi();
        long projectId = createAndAwait();
        long cardId = firstCardId(projectId);

        // 初始:全部卡片都在复习队列(mastery=0)
        mvc.perform(get("/api/projects/{id}/review-queue", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // 自评「掌握」:mastery 升为 1,nextReview 设为 1 天后
        mvc.perform(post("/api/projects/{id}/cards/{cardId}/review", projectId, cardId)
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"known\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value(1))
                .andExpect(jsonPath("$.nextReview").exists());

        // 该卡片(1 天后复习)不再出现在待复习队列
        mvc.perform(get("/api/projects/{id}/review-queue", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 自评「生疏」:mastery 回落,立即复习
        mvc.perform(post("/api/projects/{id}/cards/{cardId}/review", projectId, cardId)
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"known\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mastery").value(0));

        // 生疏后再次回到队列
        mvc.perform(get("/api/projects/{id}/review-queue", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void delete_removesProjectAndData() throws Exception {
        stubAi();
        long projectId = createAndAwait();

        mvc.perform(delete("/api/projects/{id}", projectId).with(user("testuser")).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/{id}", projectId).with(user("testuser")))
                .andExpect(status().isNotFound());
    }

    @Test
    void regenerate_restartsGeneration() throws Exception {
        stubAi();
        long projectId = createAndAwait();

        // 触发重新生成:新任务 RUNNING
        MvcResult regen = mvc.perform(post("/api/projects/{id}/regenerate", projectId).with(user("testuser")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andReturn();
        long newTaskId = new tools.jackson.databind.ObjectMapper()
                .readTree(regen.getResponse().getContentAsString())
                .get("taskId").asLong();

        // 轮询到终态
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(projectId).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }
        GenTask latest = tasks.findFirstByProjectIdOrderByIdDesc(projectId).orElseThrow();
        assertTrue(latest.getId() > newTaskId - 1); // 新任务在队列头部
        // 生成成功:卡片重建
        mvc.perform(get("/api/projects/{id}/cards", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    /** 返回项目第一张卡片的 id。 */
    private long firstCardId(long projectId) throws Exception {
        String json = mvc.perform(get("/api/projects/{id}/cards", projectId).with(user("testuser")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(json).get(0).get("id").asLong();
    }

    /** 创建项目并轮询到任务终态,返回 projectId。 */
    private long createAndAwait() throws Exception {        MvcResult created = mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + uniqueUrl() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long projectId = new tools.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .get("projectId").asLong();
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(projectId).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }
        return projectId;
    }
}
