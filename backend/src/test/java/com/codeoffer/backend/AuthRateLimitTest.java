package com.codeoffer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.rate-limit.per-minute=3")
class AuthRateLimitTest {

    @Autowired private MockMvc mvc;

    @Test
    void fourthAuthRequest_rateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/auth/captcha")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("请求过于频繁,请稍后再试"));
    }
}