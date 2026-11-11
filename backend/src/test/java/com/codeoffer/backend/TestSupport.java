package com.codeoffer.backend;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.settings.CryptoService;
import com.codeoffer.backend.settings.LlmProfile;
import com.codeoffer.backend.settings.LlmProfileRepository;
import com.codeoffer.backend.settings.SettingsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 测试装配:seed 用户与激活 LLM 档案(BYOK 后创建项目的前置条件)。 */
public final class TestSupport {

    private TestSupport() {
    }

    public static AppUser seedUser(AppUserRepository users, PasswordEncoder encoder, String username) {
        return users.findByUsername(username).orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername(username);
            n.setPasswordHash(encoder.encode("pass12345"));
            return users.save(n);
        });
    }

    /** seed 用户 + 激活档案,返回 userId。 */
    public static Long seedWithProfile(
            AppUserRepository users,
            PasswordEncoder encoder,
            LlmProfileRepository profiles,
            SettingsService settings,
            CryptoService crypto,
            String username,
            String baseUrl,
            String apiKey,
            String model) {
        AppUser u = seedUser(users, encoder, username);
        LlmProfile p = new LlmProfile();
        p.setUserId(u.getId());
        p.setName("test-profile");
        p.setProvider("custom");
        p.setBaseUrl(baseUrl);
        p.setApiKeyEnc(crypto.encrypt(apiKey));
        p.setModel(model);
        p.setVerifySsl(true);
        p = profiles.save(p);
        settings.update(u.getId(), p.getId(), null, null);
        return u.getId();
    }
}