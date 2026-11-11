package com.codeoffer.backend;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 改密/禁用/权限:会话失效与管理员边界。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountSecurityTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;

    private MockHttpSession register(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/auth/captcha").session(session));
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        mvc.perform(post("/api/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/auth/captcha").session(session));
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    void changePassword_invalidatesOtherSessions() throws Exception {
        String name = "pw_" + System.nanoTime() % 100000000;
        register(name);
        MockHttpSession other = login(name, "pass12345");
        MockHttpSession current = login(name, "pass12345");

        mvc.perform(put("/api/auth/password").session(current).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"pass12345\",\"newPassword\":\"newpass678\"}"))
                .andExpect(status().isNoContent());

        // 当前会话仍可用
        mvc.perform(get("/api/auth/me").session(current)).andExpect(status().isOk());
        // 其它会话下次请求被拦截
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
        // 旧密码登录失败
        MockHttpSession s = new MockHttpSession();
        mvc.perform(get("/api/auth/captcha").session(s));
        String code = (String) s.getAttribute("CAPTCHA_CODE");
        mvc.perform(post("/api/auth/login").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_wrongOld_400() throws Exception {
        String name = "pwbad_" + System.nanoTime() % 100000000;
        MockHttpSession session = register(name);
        mvc.perform(put("/api/auth/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrongold1\",\"newPassword\":\"newpass678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("旧密码不正确"));
    }

    @Test
    void adminCanDisableUser_disabledLogin403_andSessionExpired() throws Exception {
        // 构造管理员:直接改库提升一个已注册用户,再重新登录携带 ADMIN 权限
        String adminName = "adm_" + System.nanoTime() % 100000000;
        register(adminName);
        AppUser adminUser = users.findByUsername(adminName).orElseThrow();
        adminUser.setRole(AppUser.Role.ADMIN);
        users.save(adminUser);
        MockHttpSession admin = login(adminName, "pass12345");

        String victimName = "vic_" + System.nanoTime() % 100000000;
        MockHttpSession victim = register(victimName);
        long victimId = users.findByUsername(victimName).orElseThrow().getId();

        mvc.perform(put("/api/admin/users/{id}/status", victimId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());

        // 受害者会话失效
        mvc.perform(get("/api/auth/me").session(victim)).andExpect(status().isUnauthorized());
        // 受害者重新登录被拒
        MockHttpSession s = new MockHttpSession();
        mvc.perform(get("/api/auth/captcha").session(s));
        String code = (String) s.getAttribute("CAPTCHA_CODE");
        mvc.perform(post("/api/auth/login").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + victimName + "\",\"password\":\"pass12345\",\"captcha\":\"" + code + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("账号已被禁用"));
    }

    @Test
    void adminCannotDisableSelf() throws Exception {
        String name = "self_" + System.nanoTime() % 100000000;
        register(name);
        AppUser u = users.findByUsername(name).orElseThrow();
        u.setRole(AppUser.Role.ADMIN);
        users.save(u);
        MockHttpSession session = login(name, "pass12345");
        mvc.perform(put("/api/admin/users/{id}/status", u.getId()).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("不能禁用自己的账号"));
    }

    @Test
    void nonAdmin_cannotAccessAdminApi() throws Exception {
        String name = "plain_" + System.nanoTime() % 100000000;
        MockHttpSession session = register(name);
        mvc.perform(get("/api/admin/users").session(session))
                .andExpect(status().isForbidden());
    }
}