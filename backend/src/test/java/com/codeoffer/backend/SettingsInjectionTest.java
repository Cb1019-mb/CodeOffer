package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.LlmRuntime;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.settings.CryptoService;
import com.codeoffer.backend.settings.LlmProfileRepository;
import com.codeoffer.backend.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运行时注入:生成请求携带用户档案;无档案 409。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SettingsInjectionTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private LlmProfileRepository profiles;
    @Autowired private SettingsService settings;
    @Autowired private CryptoService crypto;
    @MockitoBean private AiServiceClient ai;

    @Test
    void noProfile_create409() throws Exception {
        TestSupport.seedUser(users, encoder, "inject_noprofile");
        mvc.perform(post("/api/projects").with(user("inject_noprofile")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/inject-" + System.nanoTime() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先在设置中配置 AI 提供商"));
    }

    @Test
    void generation_carriesUserProfile() throws Exception {
        TestSupport.seedWithProfile(users, encoder, profiles, settings, crypto,
                "inject_user", "https://profile.test/v1", "profile-key", "profile-model");
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake");
        when(ai.analyze(anyString())).thenReturn(Map.of("name", "x"));
        when(ai.healthReport(anyString(), any(), any())).thenReturn(null);
        when(ai.knowledgeCards(any(), anyList(), any())).thenReturn(Map.of("cards", List.of()));
        when(ai.flowDiagram(any(), any())).thenReturn(Map.of("title", "t", "description", "d", "mermaid", "m"));
        when(ai.resume(any(), any())).thenReturn(Map.of("star", Map.of()));
        when(ai.interviewQuestions(any(), any())).thenReturn(Map.of("questions", List.of()));

        mvc.perform(post("/api/projects").with(user("inject_user")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/inject-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk());
        Thread.sleep(1500);

        ArgumentCaptor<LlmRuntime> cap = ArgumentCaptor.forClass(LlmRuntime.class);
        verify(ai).knowledgeCards(any(), anyList(), cap.capture());
        assertEquals("https://profile.test/v1", cap.getValue().baseUrl());
        assertEquals("profile-key", cap.getValue().apiKey());
        assertEquals("profile-model", cap.getValue().model());
    }
}