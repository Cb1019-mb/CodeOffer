package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 错误契约:所有错误响应体必须含中文 detail 字段(前端 client.ts 依赖)。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ErrorContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private ProjectRepository projects;
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

    @Test
    void duplicateCreate_returns409WithChineseDetail() throws Exception {
        Project p = new Project();
        p.setRepoUrl("https://github.com/octocat/error-contract-dup");
        p.setUserId(testUserId);
        projects.save(p);

        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/error-contract-dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("该仓库已录入过,请勿重复提交"));
    }

    @Test
    void unknownProject_returns404WithDetail() throws Exception {
        mvc.perform(get("/api/projects/{id}", 999999L).with(user("testuser")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("项目不存在: 999999"));
    }

    @Test
    void blankUrl_returns400WithDetail() throws Exception {
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("repoUrl 不能为空"));
    }

    @Test
    void unknownApiPath_returns404WithDetail() throws Exception {
        mvc.perform(get("/api/definitely-not-exists").with(user("testuser")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void malformedBody_returns400WithDetail() throws Exception {
        mvc.perform(post("/api/projects")
                        .with(user("testuser")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}