package com.codeoffer.backend;

import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired private MockMvc mvc;
    @Autowired private ProjectRepository projects;

    private String captchaCode(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/auth/captcha").session(session)).andExpect(status().isOk());
        return (String) session.getAttribute("CAPTCHA_CODE");
    }

    private MockHttpSession register(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
        return session;
    }

    @Test
    void register_thenMe_works() throws Exception {
        String name = "flow_" + System.nanoTime() % 100000000;
        MockHttpSession session = register(name);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(name));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void firstUser_isAdmin_andInheritsLegacyData() throws Exception {
        // 重建上下文保证 H2 清空,本测试的注册用户确为第一个
        Project legacy = new Project();
        legacy.setRepoUrl("https://github.com/octocat/legacy-" + System.nanoTime());
        legacy = projects.save(legacy);
        assertNotNull(legacy.getId());

        String name = "first_" + System.nanoTime() % 100000000;
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        Project updated = projects.findById(legacy.getId()).orElseThrow();
        assertNotNull(updated.getUserId());
    }

    @Test
    void duplicateUsername_conflict() throws Exception {
        String name = "dup_" + System.nanoTime() % 100000000;
        register(name);
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("用户名已被注册"));
    }

    @Test
    void weakPassword_rejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"weakuser\",\"password\":\"short\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongCaptcha_rejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        captchaCode(session);
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"badcap\",\"password\":\"pass12345\",\"captcha\":\"!!!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("验证码错误或已过期"));
    }

    @Test
    void login_logout_flow() throws Exception {
        String name = "login_" + System.nanoTime() % 100000000;
        register(name);
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWrongPassword_401() throws Exception {
        String name = "wrongpw_" + System.nanoTime() % 100000000;
        register(name);
        MockHttpSession session = new MockHttpSession();
        String code = captchaCode(session);
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"wrongpass1\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"));
    }
}