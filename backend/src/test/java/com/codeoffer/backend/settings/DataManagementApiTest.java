package com.codeoffer.backend.settings;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 备份仅含本人数据;缓存管理仅 ADMIN。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DataManagementApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private ProjectRepository projects;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private AiServiceClient ai;

    private Long myProjectId;

    @BeforeEach
    void seed() {
        AppUser me = users.findByUsername("data_user").orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername("data_user");
            n.setPasswordHash(encoder.encode("pass12345"));
            return users.save(n);
        });
        AppUser other = users.findByUsername("data_other").orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername("data_other");
            n.setPasswordHash(encoder.encode("pass12345"));
            return users.save(n);
        });
        AppUser admin = users.findByUsername("data_admin").orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername("data_admin");
            n.setPasswordHash(encoder.encode("pass12345"));
            n.setRole(AppUser.Role.ADMIN);
            return users.save(n);
        });
        if (projects.findAllByUserId(me.getId()).isEmpty()) {
            Project p = new Project();
            p.setRepoUrl("https://github.com/octocat/data-" + System.nanoTime());
            p.setName("mine");
            p.setUserId(me.getId());
            myProjectId = projects.save(p).getId();
        } else {
            myProjectId = projects.findAllByUserId(me.getId()).get(0).getId();
        }
        if (projects.findAllByUserId(other.getId()).isEmpty()) {
            Project p = new Project();
            p.setRepoUrl("https://github.com/octocat/other-" + System.nanoTime());
            p.setName("theirs");
            p.setUserId(other.getId());
            projects.save(p);
        }
        when(ai.cacheInfo()).thenReturn(Map.of("sizeBytes", 1234L, "repoCount", 2L));
        when(ai.clearCache()).thenReturn(Map.of("ok", true, "removed", 2L));
    }

    @Test
    void backup_containsOnlyOwnProjects() throws Exception {
        mvc.perform(get("/api/settings/backup").with(user("data_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("data_user"))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].id").value(myProjectId))
                .andExpect(jsonPath("$.projects[0].name").value("mine"));
    }

    @Test
    void storageInfo_adminOnly() throws Exception {
        mvc.perform(get("/api/settings/storage-info").with(user("data_user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("无权访问"));
        mvc.perform(get("/api/settings/storage-info").with(user("data_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(1234))
                .andExpect(jsonPath("$.repoCount").value(2));
    }

    @Test
    void clearCache_adminOnly() throws Exception {
        mvc.perform(post("/api/settings/clear-repo-cache").with(user("data_user")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/clear-repo-cache").with(user("data_admin")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.removed").value(2));
    }
}