package com.codeoffer.backend.auth;

import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.GenTaskRepository;
import com.codeoffer.backend.project.KnowledgeCardRepository;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ResumeEntryRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册/登录/登出:验证码前置,首用户 ADMIN 并回填存量数据。 */
@Service
public class AuthService {

    public record AuthRequest(String username, String password, String captcha) {
    }

    public record AuthView(Long id, String username, String role) {
    }

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final SessionRegistry sessionRegistry;
    private final CaptchaService captcha;
    private final ProjectRepository projects;
    private final GenTaskRepository tasks;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final SessionInvalidator sessionInvalidator;
    private final boolean captchaEnabled;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthService(
            AppUserRepository users,
            PasswordEncoder encoder,
            AuthenticationManager authManager,
            SessionRegistry sessionRegistry,
            CaptchaService captcha,
            ProjectRepository projects,
            GenTaskRepository tasks,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            SessionInvalidator sessionInvalidator,
            @org.springframework.beans.factory.annotation.Value("${auth.captcha-enabled:true}") boolean captchaEnabled) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.sessionRegistry = sessionRegistry;
        this.captcha = captcha;
        this.projects = projects;
        this.tasks = tasks;
        this.cards = cards;
        this.resumes = resumes;
        this.sessionInvalidator = sessionInvalidator;
        this.captchaEnabled = captchaEnabled;
    }

    @Transactional
    public AuthView register(AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        requireCaptcha(req, request);
        String username = req.username() == null ? "" : req.username().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "用户名需为 3-32 位字母、数字或下划线");
        }
        requirePasswordStrength(req.password());
        if (users.findByUsername(username).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已被注册");
        }
        boolean first = users.count() == 0;
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setRole(first ? AppUser.Role.ADMIN : AppUser.Role.USER);
        user = users.save(user);
        if (first) {
            projects.backfillUserId(user.getId());
            tasks.backfillUserId(user.getId());
            cards.backfillUserId(user.getId());
            resumes.backfillUserId(user.getId());
        }
        establishSession(user.getUsername(), req.password(), request, response);
        return new AuthView(user.getId(), user.getUsername(), user.getRole().name());
    }

    public AuthView login(AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        requireCaptcha(req, request);
        Authentication auth = establishSession(req.username(), req.password(), request, response);
        AppUser user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        return new AuthView(user.getId(), user.getUsername(), user.getRole().name());
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
    }

    public AuthView me(AppUser user) {
        return new AuthView(user.getId(), user.getUsername(), user.getRole().name());
    }

    /** 修改密码:校验旧密码,成功后失效其它会话(当前会话保留)。 */
    public void changePassword(AppUser user, String oldPassword, String newPassword, String currentSessionId) {
        if (oldPassword == null || !encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "旧密码不正确");
        }
        requirePasswordStrength(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());
        users.save(user);
        sessionInvalidator.invalidateOthers(user.getUsername(), currentSessionId);
    }

    private void requireCaptcha(AuthRequest req, HttpServletRequest request) {
        // e2e 模式(--auth.captcha-enabled=false)跳过图形验证码;生产默认开启
        if (!captchaEnabled) {
            return;
        }
        if (!captcha.validate(request.getSession(true), req.captcha())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "验证码错误或已过期");
        }
    }

    private static void requirePasswordStrength(String password) {
        int bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码长度需为 8-72 字节");
        }
    }

    /** 认证并写入会话;会话固定攻击防护:登录后更换 sessionId。 */
    private Authentication establishSession(
            String username, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (DisabledException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
        } catch (BadCredentialsException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        sessionRegistry.registerNewSession(request.getSession().getId(), auth.getName());
        return auth;
    }
}