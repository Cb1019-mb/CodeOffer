package com.codeoffer.backend.auth;

import com.codeoffer.backend.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 用户管理:仅 ADMIN(过滤链 hasRole 保护)。 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    public record UserView(Long id, String username, String role, boolean enabled, Instant createdAt) {
    }

    public record StatusRequest(boolean enabled) {
    }

    private final AppUserRepository users;
    private final CurrentUser currentUser;
    private final SessionInvalidator sessionInvalidator;

    public AdminController(AppUserRepository users, CurrentUser currentUser, SessionInvalidator sessionInvalidator) {
        this.users = users;
        this.currentUser = currentUser;
        this.sessionInvalidator = sessionInvalidator;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream()
                .map(u -> new UserView(u.getId(), u.getUsername(), u.getRole().name(), u.isEnabled(), u.getCreatedAt()))
                .toList();
    }

    @PutMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        AppUser me = currentUser.require();
        if (me.getId().equals(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能禁用自己的账号");
        }
        AppUser target = users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在: " + id));
        target.setEnabled(req.enabled());
        target.setUpdatedAt(Instant.now());
        users.save(target);
        if (!req.enabled()) {
            sessionInvalidator.invalidateAll(target.getUsername());
        }
    }
}