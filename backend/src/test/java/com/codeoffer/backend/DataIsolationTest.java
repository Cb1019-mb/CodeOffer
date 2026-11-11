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

/** 多用户数据隔离:跨用户访问 404,列表仅见自己,同 URL 不同用户互不冲突。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DataIsolationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        for (String name : new String[]{"iso_a", "iso_b"}) {
            if (users.findByUsername(name).isEmpty()) {
                AppUser u = new AppUser();
                u.setUsername(name);
                u.setPasswordHash(encoder.encode("pass12345"));
                users.save(u);
            }
        }
    }

    @Test
    void userB_cannotSeeUserA_project() throws Exception {
        String url = "https://github.com/octocat/iso-" + System.nanoTime();
        String body = mvc.perform(post("/api/projects").with(user("iso_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("projectId").asLong();

        // B 直接访问 A 的项目 → 404
        mvc.perform(get("/api/projects/{id}", id).with(user("iso_b")))
                .andExpect(status().isNotFound());
        // B 的列表不含 A 的项目
        mvc.perform(get("/api/projects").with(user("iso_b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
        // A 自己可见
        mvc.perform(get("/api/projects/{id}", id).with(user("iso_a")))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateUrl_differentUsers_bothAllowed() throws Exception {
        String url = "https://github.com/octocat/iso-dup-" + System.nanoTime();
        mvc.perform(post("/api/projects").with(user("iso_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects").with(user("iso_b")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk());
    }
}