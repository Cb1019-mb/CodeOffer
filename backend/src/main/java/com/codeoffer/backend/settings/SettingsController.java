package com.codeoffer.backend.settings;

import com.codeoffer.backend.auth.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 设置 API:全部当前用户作用域。 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record UpdateSettingsRequest(
            Long activeProfileId,
            SettingsService.ReviewParams review,
            SettingsService.GenerationPrefs generation) {
    }

    private final SettingsService settings;
    private final ProfileService profileService;
    private final BackupService backupService;
    private final com.codeoffer.backend.ai.AiServiceClient ai;
    private final CurrentUser currentUser;

    public SettingsController(
            SettingsService settings,
            ProfileService profileService,
            BackupService backupService,
            com.codeoffer.backend.ai.AiServiceClient ai,
            CurrentUser currentUser) {
        this.settings = settings;
        this.profileService = profileService;
        this.backupService = backupService;
        this.ai = ai;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SettingsService.SettingsView get() {
        return settings.get(currentUser.require().getId());
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@RequestBody UpdateSettingsRequest req) {
        Long uid = currentUser.require().getId();
        if (req.activeProfileId() != null) {
            profileService.requireProfile(uid, req.activeProfileId());
        }
        settings.update(uid, req.activeProfileId(), req.review(), req.generation());
    }

    @GetMapping("/llm-profiles")
    public List<ProfileService.ProfileView> profiles() {
        return profileService.list(currentUser.require().getId());
    }

    @PostMapping("/llm-profiles")
    public ProfileService.ProfileView createProfile(@RequestBody ProfileService.ProfileRequest req) {
        return profileService.create(currentUser.require().getId(), req);
    }

    @PutMapping("/llm-profiles/{id}")
    public ProfileService.ProfileView updateProfile(@PathVariable Long id, @RequestBody ProfileService.ProfileRequest req) {
        return profileService.update(currentUser.require().getId(), id, req);
    }

    @DeleteMapping("/llm-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable Long id) {
        profileService.delete(currentUser.require().getId(), id);
    }

    @PostMapping("/llm-profiles/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable Long id) {
        profileService.activate(currentUser.require().getId(), id);
    }

    @PostMapping("/llm-profiles/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        return profileService.test(currentUser.require().getId(), id);
    }

    @PostMapping("/llm-profiles/import-env")
    public ProfileService.ProfileView importEnv() {
        return profileService.importEnv(currentUser.require().getId());
    }

    @GetMapping("/backup")
    public BackupService.Backup backup() {
        return backupService.export(currentUser.require());
    }

    @GetMapping("/storage-info")
    public Map<String, Object> storageInfo() {
        requireAdmin();
        return ai.cacheInfo();
    }

    @PostMapping("/clear-repo-cache")
    public Map<String, Object> clearRepoCache() {
        requireAdmin();
        return ai.clearCache();
    }

    private void requireAdmin() {
        if (currentUser.require().getRole() != com.codeoffer.backend.auth.AppUser.Role.ADMIN) {
            throw new com.codeoffer.backend.common.ApiException(HttpStatus.FORBIDDEN, "无权访问");
        }
    }
}