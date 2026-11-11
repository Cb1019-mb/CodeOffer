package com.codeoffer.backend.settings;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProfileApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        for (String name : new String[]{"prof_a", "prof_b"}) {
            if (users.findByUsername(name).isEmpty()) {
                AppUser u = new AppUser();
                u.setUsername(name);
                u.setPasswordHash(encoder.encode("pass12345"));
                users.save(u);
            }
        }
    }

    private String createProfile(String user, String name) throws Exception {
        return mvc.perform(post("/api/settings/llm-profiles").with(user(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"provider\":\"custom\",\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"sk-secret-123456\",\"model\":\"m1\",\"verifySsl\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked").value("sk-****3456"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_masksKey_andListShowsActive() throws Exception {
        String body = createProfile("prof_a", "p1");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(post("/api/settings/llm-profiles/{id}/activate", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/settings/llm-profiles").with(user("prof_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].active", org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].apiKeyMasked", org.hamcrest.Matchers.hasItem("sk-****3456")));
        // 完整 key 绝不出现在任何响应
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("sk-secret-123456"));
    }

    @Test
    void update_emptyKey_keepsOld() throws Exception {
        String body = createProfile("prof_a", "p2");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(put("/api/settings/llm-profiles/{id}", id).with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p2-renamed\",\"provider\":\"custom\",\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"\",\"model\":\"m2\",\"verifySsl\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("p2-renamed"))
                .andExpect(jsonPath("$.apiKeyMasked").value("sk-****3456"));
    }

    @Test
    void deleteActive_conflict() throws Exception {
        String body = createProfile("prof_a", "p3");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(post("/api/settings/llm-profiles/{id}/activate", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/settings/llm-profiles/{id}", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先切换激活档案再删除"));
    }

    @Test
    void crossUser_profileInvisible() throws Exception {
        String body = createProfile("prof_a", "p4");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(put("/api/settings/llm-profiles/{id}", id).with(user("prof_b")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hack\",\"provider\":\"custom\",\"baseUrl\":\"https://x.test/v1\",\"apiKey\":\"\",\"model\":\"m\",\"verifySsl\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/settings/llm-profiles").with(user("prof_b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void settingsAggregate_defaultsAndUpdate() throws Exception {
        mvc.perform(get("/api/settings").with(user("prof_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.intervalDays.length()").value(6))
                .andExpect(jsonPath("$.generation.language").value("zh"));
        mvc.perform(put("/api/settings").with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"generation\":{\"language\":\"en\",\"verbosity\":\"concise\"}}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/settings").with(user("prof_a")))
                .andExpect(jsonPath("$.generation.language").value("en"));
    }

    @Test
    void invalidProfile_rejected() throws Exception {
        mvc.perform(post("/api/settings/llm-profiles").with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"provider\":\"custom\",\"baseUrl\":\"ftp://x\",\"apiKey\":\"k\",\"model\":\"\",\"verifySsl\":true}"))
                .andExpect(status().isBadRequest());
    }
}