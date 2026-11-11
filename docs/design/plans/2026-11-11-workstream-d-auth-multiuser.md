# 工作流 D：登录与多用户 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 注册/登录 + 图形验证码 + 会话安全，全部业务数据按用户隔离，首个注册用户成为管理员并继承存量数据。

**Architecture:** Spring Security 7（Boot 4.1 管理版本）会话 Cookie 认证 + `csrf.spa()`；AppUserDetailsService + BCrypt；业务表加 user_id，项目级归属校验实现隔离；@Async 生成不依赖 SecurityContext（D 阶段生成签名不变，A 阶段再透传 userId+配置）。

**Tech Stack:** Java 21 · Spring Boot 4.1.0 · Spring Security 7 · JPA/H2 · React 19 + antd 6（D6）

**Spec:** `docs/design/specs/2026-11-11-multiuser-settings-navigation-refactor-design.md` §五

## Global Constraints

- 新增依赖仅 `spring-boot-starter-security` 与 `spring-security-test`（test scope），版本由 Boot BOM 管理。
- Security 7 API 要点（已查官方 7.0 文档核实）：
  - CSRF 用 `.csrf(csrf -> csrf.spa())`（7.0 新增，自带 cookie 仓库 + BREACH 防护 + 登录后刷新）；**不要**手写 SpaCsrfTokenRequestHandler。
  - `new DaoAuthenticationProvider(userDetailsService)` 构造器传参（无参构造已废弃）。
  - SessionRegistry 注册用 **username 字符串**作 principal：`registerNewSession(sessionId, username)`，查询 `getAllSessions(username, false)`，避免 UserDetails equals 陷阱。
- 错误体一律 `{"detail": "中文消息"}`（含 401/403/会话过期，走 Security 的 entryPoint/deniedHandler/expiredStrategy，不经 GlobalExceptionHandler）。
- 密码 8–72 字节（UTF-8）；用户名 `^[A-Za-z0-9_]{3,32}$`。
- 跨用户访问返回 404（不暴露存在性）。
- 现有测试的**业务断言不变**；D 阶段允许为认证做适配（seed 用户 + `.with(user(...))` + `.with(csrf())` + user_id 装配）。
- 平台 Windows + Git Bash；Maven 在 backend/ 用 `./mvnw.cmd`。
- 提交：Conventional Commits 中文主题，scope backend/frontend；只暂存本任务文件。

## 文件结构

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `backend/pom.xml` | 修改 | 加 security 依赖 |
| `backend/.../auth/AppUser.java` | 新建 | 用户实体（Role 枚举内嵌） |
| `backend/.../auth/AppUserRepository.java` | 新建 | findByUsername / count |
| `backend/.../auth/AppUserDetailsService.java` | 新建 | UserDetailsService 实现 |
| `backend/.../auth/CurrentUser.java` | 新建 | SecurityContext → AppUser |
| `backend/.../auth/CaptchaService.java` | 新建 | 验证码生成/校验（Session 存储） |
| `backend/.../auth/AuthService.java` | 新建 | 注册/登录/登出/改密/首用户回填 |
| `backend/.../auth/AuthController.java` | 新建 | /api/auth/* |
| `backend/.../auth/AuthRateLimiter.java` | 新建 | 按 IP 滑窗限流 |
| `backend/.../auth/SessionInvalidator.java` | 新建 | 按用户名失效会话 |
| `backend/.../auth/AdminController.java` | 新建 | /api/admin/users |
| `backend/.../config/SecurityConfig.java` | 新建 | 过滤链/会话/CSRF/编码器/AuthenticationManager |
| `backend/.../project/{Project,GenTask,KnowledgeCard,ResumeEntry}.java` | 修改 | 加 userId 列 |
| `backend/.../project/*Repository.java` | 修改 | 项目级作用域查询 + 回填 @Modifying |
| `backend/.../project/{ProjectService,CardService,ResumeService}.java` | 修改 | 方法加 userId 参数 |
| `backend/.../project/ProjectController.java` | 修改 | 注入 CurrentUser 透传 |
| `backend/.../ai/GenerationWriter.java` | 修改 | 新卡片/简历 stamped userId（取自 project） |
| `backend/src/test/resources/application-test.yml` | 修改 | 限流测试阈值调高 |
| `frontend/src/auth/AuthContext.tsx` 等 | 新建 | D6 前端认证 |

（`backend/...` = `backend/src/main/java/com/codeoffer/backend`）

---

### Task D1: 安全地基（依赖 + AppUser + SecurityConfig + 现有测试适配）

**Files:**
- Modify: `backend/pom.xml`
- Create: `auth/AppUser.java`、`auth/AppUserRepository.java`、`auth/AppUserDetailsService.java`、`auth/CurrentUser.java`、`config/SecurityConfig.java`
- Modify: `project/Project.java`、`project/GenTask.java`、`project/KnowledgeCard.java`、`project/ResumeEntry.java`（加 userId 列，本任务不参与查询）
- Modify: 现有 4 个 MockMvc 测试类（认证适配，断言不变）
- Test: `backend/src/test/java/com/codeoffer/backend/SecurityConfigTest.java`

**Interfaces:**
- Produces: `AppUser`（getUsername/getPasswordHash/getRole/isEnabled/getId；`AppUser.Role.ADMIN|USER`）；`AppUserRepository.findByUsername(String) → Optional<AppUser>`；`CurrentUser.require() → AppUser`（未登录抛 ApiException 401）；Security 过滤链与 `PasswordEncoder`/`AuthenticationManager`/`SessionRegistry` Bean。

- [ ] **Step 1: pom 加依赖**

`<dependencies>` 内追加：

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: AppUser 实体 + Repository**

```java
package com.codeoffer.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    public enum Role { ADMIN, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

```java
package com.codeoffer.backend.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}
```

- [ ] **Step 3: AppUserDetailsService + CurrentUser**

```java
package com.codeoffer.backend.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        return User.builder()
                .username(u.getUsername())
                .password(u.getPasswordHash())
                .roles(u.getRole().name())
                .disabled(!u.isEnabled())
                .build();
    }
}
```

```java
package com.codeoffer.backend.auth;

import com.codeoffer.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 请求线程内解析当前登录用户;@Async 线程禁止调用(无 SecurityContext)。 */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return users.findByUsername(auth.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }
}
```

- [ ] **Step 4: SecurityConfig**

```java
package com.codeoffer.backend.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/captcha", "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf.spa())
            .sessionManagement(sm -> sm
                .maximumSessions(Integer.MAX_VALUE)
                .sessionRegistry(sessionRegistry)
                .expiredSessionStrategy(event -> writeJson(event.getResponse(),
                        HttpStatus.UNAUTHORIZED, "会话已失效,请重新登录")))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, resp, e) -> writeJson(resp,
                        HttpStatus.UNAUTHORIZED, "未登录或会话已过期"))
                .accessDeniedHandler((req, resp, e) -> writeJson(resp,
                        HttpStatus.FORBIDDEN, "无权访问")))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable());
        return http.build();
    }

    private static void writeJson(HttpServletResponse resp, HttpStatus status, String detail)
            throws java.io.IOException {
        resp.setStatus(status.value());
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"detail\":\"" + detail + "\"}");
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
```

- [ ] **Step 5: 四个业务实体加 userId 列**

Project / GenTask / KnowledgeCard / ResumeEntry 各加（放在已有字段区，getter/setter 随该类风格）：

```java
    /** 归属用户;存量数据为 null,首个注册用户注册时回填。 */
    @Column(name = "user_id")
    private Long userId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
```

- [ ] **Step 6: 写 SecurityConfigTest（失败）**

```java
package com.codeoffer.backend;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seedUser() {
        if (users.findByUsername("sectest").isEmpty()) {
            AppUser u = new AppUser();
            u.setUsername("sectest");
            u.setPasswordHash(encoder.encode("pass12345"));
            users.save(u);
        }
    }

    @Test
    void unauthenticatedApi_returns401WithDetail() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void authenticatedApi_works() throws Exception {
        mvc.perform(get("/api/projects").with(user("sectest")))
                .andExpect(status().isOk());
    }

    @Test
    void writeWithoutCsrf_returns403() throws Exception {
        mvc.perform(post("/api/projects").with(user("sectest"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithCsrf_passesSecurityLayer() throws Exception {
        // 通过安全层后进入业务校验:空 URL → 400(证明 CSRF 放行)
        mvc.perform(post("/api/projects").with(user("sectest")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("repoUrl 不能为空"));
    }
}
```

- [ ] **Step 7: 运行确认失败**

Run: `cd "D:/Internship Program/CodeOffer/backend" && ./mvnw.cmd test -Dtest=SecurityConfigTest -q`
Expected: 编译失败（auth 包不存在）或 401 断言失败（若先加了依赖未加配置）。

- [ ] **Step 8: 适配现有 4 个 MockMvc 测试类（断言不变）**

对 ProjectControllerTest / ErrorContractTest / HealthReportIntegrationTest（此类无 MockMvc 请求则跳过）：
1. 加注入：`@Autowired AppUserRepository users; @Autowired PasswordEncoder encoder;`
2. 加 `@BeforeEach void seedTestUser()`：不存在则创建 username=`testuser`、passwordHash=encoder.encode("pass12345") 的 AppUser。
3. 所有 `mvc.perform(...)` 追加 `.with(user("testuser"))`；所有写请求（post/put/delete）再追加 `.with(csrf())`。
4. import：`org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user/csrf`、`org.junit.jupiter.api.BeforeEach`。

GenerateFlowIntegrationTest 与 BackendApplicationTests **不改**（直接调 service/上下文加载，不受过滤链影响）。

- [ ] **Step 9: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS（现有 25 + SecurityConfigTest 5）。

- [ ] **Step 10: 提交**

```bash
git add backend/pom.xml backend/src/main/java/com/codeoffer/backend/auth/ backend/src/main/java/com/codeoffer/backend/config/SecurityConfig.java backend/src/main/java/com/codeoffer/backend/project/Project.java backend/src/main/java/com/codeoffer/backend/project/GenTask.java backend/src/main/java/com/codeoffer/backend/project/KnowledgeCard.java backend/src/main/java/com/codeoffer/backend/project/ResumeEntry.java backend/src/test/java/com/codeoffer/backend/SecurityConfigTest.java backend/src/test/java/com/codeoffer/backend/ProjectControllerTest.java backend/src/test/java/com/codeoffer/backend/ErrorContractTest.java
git commit -m "feat(backend): Spring Security 会话认证地基与用户实体,业务表预留 user_id"
```

---

### Task D2: 图形验证码

**Files:**
- Create: `auth/CaptchaService.java`、`auth/AuthController.java`（仅 captcha 端点）
- Test: `backend/src/test/java/com/codeoffer/backend/auth/CaptchaServiceTest.java`

**Interfaces:**
- Produces: `CaptchaService.generate(HttpSession) → byte[]`（PNG，答案存 session 属性 `CAPTCHA_CODE`/`CAPTCHA_EXPIRES_AT`）；`CaptchaService.validate(HttpSession, String) → boolean`（忽略大小写、一次性、5 分钟过期）；`GET /api/auth/captcha → image/png`。D3 消费 validate。

- [ ] **Step 1: 写失败单测**

```java
package com.codeoffer.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {

    private final CaptchaService service = new CaptchaService();

    @Test
    void generate_storesCodeAndReturnsPng() {
        MockHttpSession session = new MockHttpSession();
        byte[] png = service.generate(session);
        assertNotNull(png);
        assertTrue(png.length > 100);
        assertNotNull(session.getAttribute("CAPTCHA_CODE"));
    }

    @Test
    void validate_caseInsensitiveAndOneShot() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        assertTrue(service.validate(session, code.toLowerCase()));
        assertFalse(service.validate(session, code)); // 一次性:第二次必败
    }

    @Test
    void validate_wrongCode_failsAndConsumes() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        assertFalse(service.validate(session, "!!!!"));
        assertFalse(service.validate(session, code)); // 错误尝试也消费
    }

    @Test
    void validate_expired_fails() {
        MockHttpSession session = new MockHttpSession();
        service.generate(session);
        String code = (String) session.getAttribute("CAPTCHA_CODE");
        session.setAttribute("CAPTCHA_EXPIRES_AT", System.currentTimeMillis() - 1);
        assertFalse(service.validate(session, code));
    }

    @Test
    void validate_noCaptcha_fails() {
        assertFalse(service.validate(new MockHttpSession(), "ABCD"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw.cmd test -Dtest=CaptchaServiceTest -q`
Expected: 编译失败（CaptchaService 不存在）。

- [ ] **Step 3: 实现 CaptchaService**

```java
package com.codeoffer.backend.auth;

import jakarta.servlet.http.HttpSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/** 图形验证码:Java2D 自绘,答案存 Session,一次性、5 分钟过期。 */
@Service
public class CaptchaService {

    public static final String CODE_ATTR = "CAPTCHA_CODE";
    public static final String EXPIRES_ATTR = "CAPTCHA_EXPIRES_AT";
    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public byte[] generate(HttpSession session) {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        session.setAttribute(CODE_ATTR, code.toString());
        session.setAttribute(EXPIRES_ATTR, System.currentTimeMillis() + TTL_MILLIS);
        return render(code.toString());
    }

    /** 校验并立即消费(无论成败);忽略大小写;过期视为失败。 */
    public boolean validate(HttpSession session, String input) {
        Object code = session.getAttribute(CODE_ATTR);
        Object expires = session.getAttribute(EXPIRES_ATTR);
        session.removeAttribute(CODE_ATTR);
        session.removeAttribute(EXPIRES_ATTR);
        if (!(code instanceof String expected) || !(expires instanceof Long exp)) {
            return false;
        }
        if (System.currentTimeMillis() > exp || input == null) {
            return false;
        }
        return expected.equalsIgnoreCase(input.trim());
    }

    private byte[] render(String code) {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 246, 250));
        g.fillRect(0, 0, 120, 40);
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(RANDOM.nextInt(200), RANDOM.nextInt(200), RANDOM.nextInt(200)));
            g.drawLine(RANDOM.nextInt(120), RANDOM.nextInt(40), RANDOM.nextInt(120), RANDOM.nextInt(40));
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(RANDOM.nextInt(120), RANDOM.nextInt(120), RANDOM.nextInt(120)));
            double theta = (RANDOM.nextDouble() - 0.5) * 0.5;
            g.rotate(theta, 20 + i * 26, 28);
            g.drawString(String.valueOf(code.charAt(i)), 12 + i * 26, 30);
            g.rotate(-theta, 20 + i * 26, 28);
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("验证码渲染失败", e);
        }
    }
}
```

- [ ] **Step 4: AuthController（captcha 端点）**

```java
package com.codeoffer.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CaptchaService captcha;

    public AuthController(CaptchaService captcha) {
        this.captcha = captcha;
    }

    @GetMapping("/captcha")
    public ResponseEntity<byte[]> captcha(HttpServletRequest request) {
        byte[] png = captcha.generate(request.getSession(true));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }
}
```

- [ ] **Step 5: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS（含 CaptchaServiceTest 5 个）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/auth/CaptchaService.java backend/src/main/java/com/codeoffer/backend/auth/AuthController.java backend/src/test/java/com/codeoffer/backend/auth/CaptchaServiceTest.java
git commit -m "feat(backend): 图形验证码生成与一次性校验"
```

---

### Task D3: 注册/登录/登出/me + 限流 + 首用户回填

**Files:**
- Create: `auth/AuthService.java`、`auth/AuthRateLimiter.java`
- Modify: `auth/AuthController.java`（register/login/logout/me）
- Modify: 四个业务 Repository（回填 @Modifying）
- Modify: `backend/src/test/resources/application-test.yml`（限流阈值）
- Test: `backend/src/test/java/com/codeoffer/backend/AuthFlowTest.java`、`backend/src/test/java/com/codeoffer/backend/AuthRateLimitTest.java`

**Interfaces:**
- Consumes: D1 的 AuthenticationManager/PasswordEncoder/SessionRegistry/CurrentUser；D2 的 CaptchaService.validate。
- Produces: `POST /api/auth/register {username,password,captcha} → {id,username,role}`（自动登录）；`POST /api/auth/login` 同请求 → 同响应；`POST /api/auth/logout → 204`；`GET /api/auth/me → {id,username,role}`；`AuthRateLimiter.tryAcquire(String ip) → boolean`（`auth.rate-limit.per-minute` 默认 30）；Repository `backfillUserId(Long uid) → int`（四表）。

- [ ] **Step 1: 四个 Repository 加回填方法**

ProjectRepository / GenTaskRepository / KnowledgeCardRepository / ResumeEntryRepository 各加（实体名对应替换）：

```java
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Project p set p.userId = :uid where p.userId is null")
    int backfillUserId(@org.springframework.data.repository.query.Param("uid") Long uid);
```

（GenTask→`GenTask t`、KnowledgeCard→`KnowledgeCard c`、ResumeEntry→`ResumeEntry r`。）

- [ ] **Step 2: AuthRateLimiter**

```java
package com.codeoffer.backend.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 认证端点按 IP 滑窗限流(单实例内存版;多实例需换 Redis,见部署清单)。 */
@Component
public class AuthRateLimiter {

    private final int limitPerMinute;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public AuthRateLimiter(@Value("${auth.rate-limit.per-minute:30}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
```

- [ ] **Step 3: AuthService**

```java
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
            ResumeEntryRepository resumes) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.sessionRegistry = sessionRegistry;
        this.captcha = captcha;
        this.projects = projects;
        this.tasks = tasks;
        this.cards = cards;
        this.resumes = resumes;
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
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
                .logout(request, response, auth);
    }

    public AuthView me(AppUser user) {
        return new AuthView(user.getId(), user.getUsername(), user.getRole().name());
    }

    private void requireCaptcha(AuthRequest req, HttpServletRequest request) {
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
```

注意：`establishSession` 中 `authManager.authenticate` 对不存在用户也抛 BadCredentials（DaoAuthenticationProvider 默认隐藏 UsernameNotFound），统一 401。

- [ ] **Step 4: AuthController 补端点 + 限流**

AuthController 构造器追加注入 `AuthService`、`AuthRateLimiter`、`CurrentUser`，新增：

```java
    @PostMapping("/register")
    public AuthService.AuthView register(
            @RequestBody AuthService.AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        rateLimit(request);
        return authService.register(req, request, response);
    }

    @PostMapping("/login")
    public AuthService.AuthView login(
            @RequestBody AuthService.AuthRequest req, HttpServletRequest request, HttpServletResponse response) {
        rateLimit(request);
        return authService.login(req, request, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
    }

    @GetMapping("/me")
    public AuthService.AuthView me() {
        return authService.me(currentUser.require());
    }

    private void rateLimit(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            throw new com.codeoffer.backend.common.ApiException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁,请稍后再试");
        }
    }
```

captcha 端点也加 `rateLimit(request);` 首行。import 补齐 PostMapping/RequestBody/ResponseStatus/HttpServletRequest/HttpServletResponse。

- [ ] **Step 5: application-test.yml 调高限流阈值**

追加：

```yaml
auth:
  rate-limit:
    per-minute: 10000
```

- [ ] **Step 6: 写 AuthFlowTest（失败）**

```java
package com.codeoffer.backend;

import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void firstUser_isAdmin_andInheritsLegacyData() throws Exception {
        // 注意:本测试依赖「库中尚无用户」;H2 共享上下文中其他测试可能已建用户,
        // 故用独立上下文(@DirtiesContext 前后)保证首用户语义。
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
```

**首用户语义说明**：`firstUser_isAdmin_andInheritsLegacyData` 在共享 H2 上下文中可能不是真正第一个用户。处理：给该测试方法加 `@org.springframework.test.annotation.DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)` 重建上下文（H2 create-drop 清空），保证首用户语义。实现时若上下文重建导致其他测试数据依赖问题，改为断言「role ∈ {ADMIN,USER} 且 legacy.userId 非 null 当且仅当首用户」——**优先用 DirtiesContext 方案**。

- [ ] **Step 7: AuthRateLimitTest（独立上下文低阈值）**

```java
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
```

- [ ] **Step 8: 运行确认失败 → 实现 → 全绿**

Run: `./mvnw.cmd test -q`（先跑确认新测试失败，实现后全绿）
Expected: 全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/auth/ backend/src/main/java/com/codeoffer/backend/project/ProjectRepository.java backend/src/main/java/com/codeoffer/backend/project/GenTaskRepository.java backend/src/main/java/com/codeoffer/backend/project/KnowledgeCardRepository.java backend/src/main/java/com/codeoffer/backend/project/ResumeEntryRepository.java backend/src/test/resources/application-test.yml backend/src/test/java/com/codeoffer/backend/AuthFlowTest.java backend/src/test/java/com/codeoffer/backend/AuthRateLimitTest.java
git commit -m "feat(backend): 注册/登录/登出与会话管理,验证码前置+IP限流,首用户继承存量数据"
```

---

### Task D4: 数据隔离（项目级作用域 + userId 透传）

**Files:**
- Modify: `project/ProjectRepository.java`（作用域查询）
- Modify: `project/ProjectService.java`、`project/CardService.java`、`project/ResumeService.java`（方法加 userId）
- Modify: `project/ProjectController.java`（注入 CurrentUser）
- Modify: `ai/GenerationWriter.java`（新行 stamped userId）
- Modify: 现有测试的**数据装配**（直接 save 的 Project 补 userId；断言不变）
- Test: `backend/src/test/java/com/codeoffer/backend/DataIsolationTest.java`

**Interfaces:**
- Consumes: D1 CurrentUser.require()。
- Produces: `ProjectRepository.findByIdAndUserId(Long,Long)`、`findByRepoUrlAndUserId(String,Long)`、`findAllByUserId(Long)`；三个 service 全部公开方法首参 `Long userId`；隔离语义：跨用户 404、列表仅见自己。卡片/简历/任务经「已校验归属的项目」间接隔离（查询仍按 projectId，归属链保证安全），新行 stamped userId 供完整性与未来直查。

- [ ] **Step 1: ProjectRepository 作用域方法**

```java
    java.util.Optional<Project> findByIdAndUserId(Long id, Long userId);
    java.util.Optional<Project> findByRepoUrlAndUserId(String repoUrl, Long userId);
    java.util.List<Project> findAllByUserId(Long userId);
```

（保留原 findByRepoUrl 供 D3 回填无关路径；若无其他引用可删。）

- [ ] **Step 2: ProjectService 加 userId**

- `requireProject(Long userId, Long id)` → `projects.findByIdAndUserId(id, userId)`，404 消息不变。
- `create(Long userId, CreateRequest)`：查重改 `findByRepoUrlAndUserId`；`project.setUserId(userId)`。
- `list(Long userId)` → `findAllByUserId`。
- `detail/latestTask/cards/resume/confirm/delete/regenerate` 首参 userId，内部 requireProject(userId, id)。
- CardService/ResumeService：所有公开方法首参 userId；`requireOwnedCard(userId, id, cardId)` 内 `projectService.requireProject(userId, id)`；reviewQueue 同。

- [ ] **Step 3: ProjectController 注入 CurrentUser**

构造器加 `CurrentUser currentUser`；每个端点方法体首行取 `Long uid = currentUser.require().getId();` 并透传，例如：

```java
    @GetMapping
    public List<ProjectView> list() {
        return projectService.list(currentUser.require().getId());
    }
```

- [ ] **Step 4: GenerationWriter stamped userId**

replaceCards/saveFlowCard/saveResumeEntry 中新建实体处：从已加载的 Project 取 userId 设置（writer 内已有 projectId → 查 project 或改签名接收 userId；**选择改签名**：三个方法追加 `Long userId` 参数，GenerateService 调用处传 `project.getUserId()`）。

- [ ] **Step 5: 现有测试装配适配（断言不变）**

- ProjectControllerTest / ErrorContractTest 中直接 `projects.save(p)` 处：`p.setUserId(testUserId)`（testUserId 为 seedTestUser 保存的用户 id，@BeforeEach 中记录到字段）。
- GenerateFlowIntegrationTest：newProject 里 `p.setUserId(...)` 需要一个用户——加 @BeforeEach seed（同模式），newProject 设置其 id。**断言不动**。
- HealthReportIntegrationTest：runGeneration 的 Project 同样补 userId（seed 用户）。

- [ ] **Step 6: DataIsolationTest（失败→实现→绿）**

```java
package com.codeoffer.backend;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DataIsolationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        for (String name : new String[]{"iso_a", "iso_b"}) {
            if (users.findByUsername(name).isEmpty()) {
                AppUser u = new AppUser();
                u.setUsername(name);
                u.setPasswordHash(encoder.encode("pass12345"));
                users.save(u);
            }
        }
    }

    @Test
    void userB_cannotSeeUserA_project() throws Exception {
        String url = "https://github.com/octocat/iso-" + System.nanoTime();
        String body = mvc.perform(post("/api/projects").with(user("iso_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("projectId").asLong();

        // B 直接访问 A 的项目 → 404
        mvc.perform(get("/api/projects/{id}", id).with(user("iso_b")))
                .andExpect(status().isNotFound());
        // B 的列表不含 A 的项目
        mvc.perform(get("/api/projects").with(user("iso_b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
        // A 自己可见
        mvc.perform(get("/api/projects/{id}", id).with(user("iso_a")))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateUrl_differentUsers_bothAllowed() throws Exception {
        String url = "https://github.com/octocat/iso-dup-" + System.nanoTime();
        mvc.perform(post("/api/projects").with(user("iso_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects").with(user("iso_b")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 7: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/project/ backend/src/main/java/com/codeoffer/backend/ai/GenerationWriter.java backend/src/main/java/com/codeoffer/backend/ai/GenerateService.java backend/src/test/java/com/codeoffer/backend/
git commit -m "feat(backend): 业务数据按用户隔离,项目级归属校验+新行 userId 落库"
```

---

### Task D5: 改密 + 管理员用户管理 + 会话失效

**Files:**
- Create: `auth/SessionInvalidator.java`、`auth/AdminController.java`
- Modify: `auth/AuthService.java`（changePassword）、`auth/AuthController.java`（password 端点）
- Test: `backend/src/test/java/com/codeoffer/backend/AccountSecurityTest.java`

**Interfaces:**
- Consumes: D3 SessionRegistry（username 字符串 principal）、CurrentUser。
- Produces: `PUT /api/auth/password {oldPassword,newPassword} → 204`（其它会话失效）；`GET /api/admin/users → [{id,username,role,enabled,createdAt}]`；`PUT /api/admin/users/{id}/status {enabled} → 204`（禁自己 400；被禁用者会话失效、登录 403）；`SessionInvalidator.invalidateAll(String)` / `invalidateOthers(String, String currentSessionId)`。

- [ ] **Step 1: SessionInvalidator**

```java
package com.codeoffer.backend.auth;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/** 按用户名失效会话(expireNow 后,ConcurrentSessionFilter 在下次请求时拦截并返回 401)。 */
@Component
public class SessionInvalidator {

    private final SessionRegistry registry;

    public SessionInvalidator(SessionRegistry registry) {
        this.registry = registry;
    }

    public void invalidateAll(String username) {
        for (SessionInformation si : registry.getAllSessions(username, false)) {
            si.expireNow();
        }
    }

    public void invalidateOthers(String username, String currentSessionId) {
        for (SessionInformation si : registry.getAllSessions(username, false)) {
            if (!si.getSessionId().equals(currentSessionId)) {
                si.expireNow();
            }
        }
    }
}
```

- [ ] **Step 2: AuthService.changePassword**

```java
    public void changePassword(AppUser user, String oldPassword, String newPassword, String currentSessionId) {
        if (!encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "旧密码不正确");
        }
        requirePasswordStrength(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());
        users.save(user);
        sessionInvalidator.invalidateOthers(user.getUsername(), currentSessionId);
    }
```

构造器追加注入 SessionInvalidator。AuthController 加：

```java
    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    @PutMapping("/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        authService.changePassword(currentUser.require(), req.oldPassword(), req.newPassword(),
                request.getSession().getId());
    }
```

- [ ] **Step 3: AdminController**

```java
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
```

- [ ] **Step 4: AccountSecurityTest（失败→实现→绿）**

```java
package com.codeoffer.backend;

import com.codeoffer.backend.auth.AppUser;
import com.codeoffer.backend.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountSecurityTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

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
        // 旧密码登录失败,新密码成功
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
        // 构造管理员:直接改库提升一个已注册用户
        String adminName = "adm_" + System.nanoTime() % 100000000;
        MockHttpSession admin = register(adminName);
        AppUser adminUser = users.findByUsername(adminName).orElseThrow();
        adminUser.setRole(AppUser.Role.ADMIN);
        users.save(adminUser);
        // 重新登录使会话携带 ADMIN 权限
        admin = login(adminName, "pass12345");

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
```

注意：register 产生的会话角色是注册时的 USER；提升 ADMIN 后必须重新 login 才携带新权限（测试已如此处理）。

- [ ] **Step 5: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/auth/ backend/src/test/java/com/codeoffer/backend/AccountSecurityTest.java
git commit -m "feat(backend): 修改密码与管理员用户管理,改密/禁用即时失效其它会话"
```

---

### Task D6: 前端登录页 + 路由守卫 + 全局导航壳

**Files:**
- Create: `frontend/src/auth/AuthContext.tsx`、`frontend/src/pages/LoginPage.tsx`、`frontend/src/components/AppLayout.tsx`、`frontend/src/components/RequireAuth.tsx`
- Modify: `frontend/src/App.tsx`、`frontend/src/api/client.ts`、`frontend/src/types.ts`

**Interfaces:**
- Consumes: D3/D5 后端 `/api/auth/*`。
- Produces: `AuthContext {user: UserView|null, loading, refresh(), logout()}`；`api.register/login/logout/me`；client.ts 写请求自动带 `X-XSRF-TOKEN`、401 统一跳 `/login`；AppLayout 顶栏（品牌 + 项目菜单 + 用户下拉）。A 阶段在此基础上加设置入口。

- [ ] **Step 1: types.ts 加 UserView**

```typescript
export interface UserView {
  id: number
  username: string
  role: 'ADMIN' | 'USER'
}
```

- [ ] **Step 2: client.ts 改造**

request() 内：
1. 非 GET/HEAD 请求从 cookie 读 `XSRF-TOKEN` 并设 `X-XSRF-TOKEN` 头：

```typescript
function xsrfHeader(): Record<string, string> {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {}
}
```

headers 合并 `{ 'Content-Type': 'application/json', ...xsrfHeader() }`（仅写请求需要，GET 带上也无害，统一加）。
2. `!resp.ok` 分支：若 `resp.status === 401` 且 path 不以 `/api/auth/` 开头 → `window.location.assign('/login')`，仍 throw Error(detail)。
3. api 对象追加：

```typescript
  me: () => request<UserView>('/api/auth/me'),
  login: (username: string, password: string, captcha: string) =>
    request<UserView>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, captcha }),
    }),
  register: (username: string, password: string, captcha: string) =>
    request<UserView>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, captcha }),
    }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
```

import UserView。

- [ ] **Step 3: AuthContext**

```tsx
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import type { UserView } from '../types'

interface AuthState {
  user: UserView | null
  loading: boolean
  refresh: () => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState>({
  user: null,
  loading: true,
  refresh: async () => {},
  logout: async () => {},
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserView | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setUser(await api.me())
    } catch {
      setUser(null)
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } finally {
      setUser(null)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return <AuthContext value={{ user, loading, refresh, logout }}>{children}</AuthContext>
}

export function useAuth() {
  return useContext(AuthContext)
}
```

（React 19：`<AuthContext value=...>` 直接作为 provider。）

- [ ] **Step 4: RequireAuth + AppLayout**

```tsx
import { Navigate, useLocation } from 'react-router-dom'
import { Spin } from 'antd'
import { useAuth } from '../auth/AuthContext'
import type { ReactNode } from 'react'

export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) {
    return <div style={{ textAlign: 'center', marginTop: 120 }}><Spin size="large" /></div>
  }
  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  return <>{children}</>
}
```

```tsx
import { Layout, Menu, Dropdown, Button, Tag, Space } from 'antd'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useAuth } from '../auth/AuthContext'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const onLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
        <Link to="/" style={{ color: '#fff', fontSize: 18, fontWeight: 600, whiteSpace: 'nowrap' }}>
          CodeOffer
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[location.pathname === '/' ? 'home' : 'other']}
          items={[{ key: 'home', label: <Link to="/">项目</Link> }]}
          style={{ flex: 1, minWidth: 0 }}
        />
        <Dropdown
          menu={{
            items: [
              { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
            ],
          }}
        >
          <Button type="text" style={{ color: '#fff' }}>
            <Space>
              <UserOutlined />
              {user?.username}
              {user?.role === 'ADMIN' && <Tag color="gold">管理员</Tag>}
            </Space>
          </Button>
        </Dropdown>
      </Layout.Header>
      <Layout.Content>
        <Outlet />
      </Layout.Content>
    </Layout>
  )
}
```

- [ ] **Step 5: LoginPage**

```tsx
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Tabs, Typography, message } from 'antd'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [captchaKey, setCaptchaKey] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { refresh } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  const submit = async (values: { username: string; password: string; captcha: string; confirm?: string }) => {
    setError(null)
    if (tab === 'register' && values.password !== values.confirm) {
      setError('两次输入的密码不一致')
      return
    }
    setBusy(true)
    try {
      if (tab === 'login') {
        await api.login(values.username, values.password, values.captcha)
      } else {
        await api.register(values.username, values.password, values.captcha)
      }
      await refresh()
      message.success(tab === 'login' ? '登录成功' : '注册成功,已自动登录')
      navigate(from, { replace: true })
    } catch (e) {
      setError((e as Error).message)
      setCaptchaKey((k) => k + 1) // 验证码一次性,失败后刷新
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>CodeOffer</Typography.Title>
        <Tabs
          activeKey={tab}
          onChange={(k) => { setTab(k as 'login' | 'register'); setError(null); setCaptchaKey((v) => v + 1) }}
          centered
          items={[
            { key: 'login', label: '登录' },
            { key: 'register', label: '注册' },
          ]}
        />
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={submit} key={tab}>
          <Form.Item name="username" label="用户名" rules={[
            { required: true, message: '请输入用户名' },
            { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线' },
          ]}>
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码至少 8 位' },
          ]}>
            <Input.Password autoComplete={tab === 'login' ? 'current-password' : 'new-password'} />
          </Form.Item>
          {tab === 'register' && (
            <Form.Item name="confirm" label="确认密码" rules={[{ required: true, message: '请再次输入密码' }]}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          )}
          <Form.Item label="验证码" required>
            <div style={{ display: 'flex', gap: 8 }}>
              <Form.Item name="captcha" noStyle rules={[{ required: true, message: '请输入验证码' }]}>
                <Input placeholder="不区分大小写" style={{ flex: 1 }} />
              </Form.Item>
              <img
                src={`/api/auth/captcha?k=${captchaKey}`}
                alt="验证码,点击刷新"
                title="点击刷新"
                style={{ height: 40, borderRadius: 6, cursor: 'pointer', border: '1px solid #d9d9d9' }}
                onClick={() => setCaptchaKey((k) => k + 1)}
              />
            </div>
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={busy}>
            {tab === 'login' ? '登录' : '注册'}
          </Button>
        </Form>
      </Card>
    </div>
  )
}
```

- [ ] **Step 6: App.tsx 组装**

```tsx
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css'
import { AuthProvider } from './auth/AuthContext'
import AppLayout from './components/AppLayout'
import RequireAuth from './components/RequireAuth'
import HomePage from './pages/HomePage'
import ProjectPage from './pages/ProjectPage'
import LoginPage from './pages/LoginPage'
import './index.css'

export default function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
              <Route path="/" element={<HomePage />} />
              <Route path="/projects/:id" element={<ProjectPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ConfigProvider>
  )
}
```

注意：HomePage/ProjectPage 原有外层 margin 容器保留；AppLayout.Content 提供整体框架。HomePage 若有自己的顶部标题栏与新壳重复，去掉重复部分（实现时检查）。

- [ ] **Step 7: 类型与 lint 检查**

Run: `cd "D:/Internship Program/CodeOffer/frontend" && npm run build && npm run lint`
Expected: 均通过。

- [ ] **Step 8: 浏览器实测（dev-up.ps1 起三服务）**

Run: `powershell -ExecutionPolicy Bypass -File "D:/Internship Program/CodeOffer/scripts/dev-up.ps1"`
验证清单（用浏览器工具逐项执行并截图留证）：
1. 未登录访问 `/` → 重定向 `/login`；
2. 注册新用户（验证码点击刷新可用）→ 自动登录进首页；
3. 录入一个真实仓库 → 生成成功（env 的 DeepSeek 配置仍生效）→ 卡片可见；
4. 退出登录 → 回登录页；再访问 `/projects/1` → 重定向登录；
5. 错误验证码登录 → 显示中文错误（detail 契约）；
6. 注册第二个用户 → 看不到第一个用户的项目（隔离）；
7. 直接访问 `/api/projects`（未登录）→ 401 JSON。

- [ ] **Step 9: 提交**

```bash
git add frontend/src/auth/ frontend/src/pages/LoginPage.tsx frontend/src/components/AppLayout.tsx frontend/src/components/RequireAuth.tsx frontend/src/App.tsx frontend/src/api/client.ts frontend/src/types.ts
git commit -m "feat(frontend): 登录/注册页、路由守卫与全局导航壳,CSRF 与 401 统一处理"
```

---

## 验收（对照 spec §八 D 条）

- [ ] 未登录访问业务 API 全部 401（SecurityConfigTest）
- [ ] 注册/登录/验证码浏览器可用（D6 实测截图）
- [ ] 双用户互不可见（DataIsolationTest + 浏览器）
- [ ] 首用户 ADMIN 继承存量（AuthFlowTest）
- [ ] 改密/禁用会话失效（AccountSecurityTest）
- [ ] 限流 429（AuthRateLimitTest）
- [ ] 后端全量测试绿