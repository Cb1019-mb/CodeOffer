package com.codeoffer.backend;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seedUser() {
        if (users.findByUsername("sectest").isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername("sectest");
            u.setPasswordHash(encoder.encode("pass12345"));
            users.save(u);
        }
    }

    @Test
    void unauthenticatedApi_returns401WithDetail() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void authenticatedApi_works() throws Exception {
        mvc.perform(get("/api/projects").with(user("sectest")))
                .andExpect(status().isOk());
    }

    @Test
    void writeWithoutCsrf_returns403() throws Exception {
        mvc.perform(post("/api/projects").with(user("sectest"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithCsrf_passesSecurityLayer() throws Exception {
        // 通过安全层后进入业务校验:空 URL → 400(证明 CSRF 放行)
        mvc.perform(post("/api/projects").with(user("sectest")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("repoUrl 不能为空"));
    }
}