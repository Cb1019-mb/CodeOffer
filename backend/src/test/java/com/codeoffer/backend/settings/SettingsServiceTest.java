package com.codeoffer.backend.settings;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import com.codeoffer.backend.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class SettingsServiceTest {

    @Autowired private SettingsService settings;
    @Autowired private AppUserRepository users;
    @Autowired private AppSettingRepository settingRepo;
    @Autowired private PasswordEncoder encoder;

    private Long uid;

    @BeforeEach
    void seed() {
        AppUser u = users.findByUsername("settings_user").orElseGet(() -> {
            AppUser n = new AppUser();
            n.setUsername("settings_user");
            n.setPasswordHash(encoder.encode("pass12345"));
            return users.save(n);
        });
        uid = u.getId();
        settingRepo.deleteByUserId(uid);
    }

    @Test
    void defaults_whenUnset() {
        SettingsService.SettingsView v = settings.get(uid);
        assertEquals(List.of(0, 1, 3, 7, 15, 30), v.review().intervalDays());
        assertEquals(2, v.review().unknownPenalty());
        assertEquals(60, v.review().unknownDelaySeconds());
        assertEquals("zh", v.generation().language());
        assertEquals("standard", v.generation().verbosity());
        assertEquals("", v.network().proxyUrl());
    }

    @Test
    void update_partialAndReadBack() {
        settings.update(uid, null, new SettingsService.ReviewParams(List.of(0, 2, 4, 8, 16, 32), 3, 120), null, null);
        SettingsService.SettingsView v = settings.get(uid);
        assertEquals(List.of(0, 2, 4, 8, 16, 32), v.review().intervalDays());
        assertEquals(3, v.review().unknownPenalty());
        assertEquals("zh", v.generation().language()); // 未改保持默认
        settings.update(uid, null, null, new SettingsService.GenerationPrefs("en", "detailed"), null);
        assertEquals("en", settings.get(uid).generation().language());
    }

    @Test
    void invalidReview_rejected() {
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 1, 3), 2, 60), null, null));
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 1, 3, 7, 15, 400), 2, 60), null, null));
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 5, 3, 7, 15, 30), 2, 60), null, null));
    }

    @Test
    void invalidGeneration_rejected() {
        assertThrows(ApiException.class, () -> settings.update(uid, null, null,
                new SettingsService.GenerationPrefs("fr", "standard"), null));
        assertThrows(ApiException.class, () -> settings.update(uid, null, null,
                new SettingsService.GenerationPrefs("zh", "huge"), null));
    }

    @Test
    void network_updateAndReadBack() {
        settings.update(uid, null, null, null, new SettingsService.NetworkPrefs("http://127.0.0.1:7890"));
        assertEquals("http://127.0.0.1:7890", settings.get(uid).network().proxyUrl());
        settings.update(uid, null, null, null, new SettingsService.NetworkPrefs("socks5://127.0.0.1:1080"));
        assertEquals("socks5://127.0.0.1:1080", settings.get(uid).network().proxyUrl());
        settings.update(uid, null, null, null, new SettingsService.NetworkPrefs("  "));
        assertEquals("", settings.get(uid).network().proxyUrl());
        settings.update(uid, null, null, null, null); // null 不改
        assertEquals("", settings.get(uid).network().proxyUrl());
    }

    @Test
    void invalidNetwork_rejected() {
        for (String bad : List.of(
                "ftp://127.0.0.1:7890",
                "http://user:pass@127.0.0.1:7890",
                "http://127.0.0.1:99999",
                "http://127.0.0.1:7890/path",
                "http://127.0.0.1:7890?q=1",
                "127.0.0.1:7890",
                "http://:7890")) {
            assertThrows(ApiException.class, () -> settings.update(uid, null, null, null,
                    new SettingsService.NetworkPrefs(bad)));
        }
    }
}