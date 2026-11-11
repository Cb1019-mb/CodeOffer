# 工作流 A：设置子系统 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 多套 LLM 档案一键切换 + 连接测试、学习/复习参数、生成偏好、数据管理、账号安全、外观主题；设置存 MySQL 并按请求注入 AI 服务（BYOK）。

**Architecture:** Java 单一事实源：`llm_profile`（AES-GCM 加密 key）+ `app_setting`（KV）两表按用户隔离；生成开始快照 `LlmRuntime` 随每次 AI 请求注入 `llm`+`prefs`；AI 服务无状态覆盖 env 配置。废除 env 回退（多用户防 key 借用）。

**Tech Stack:** Java 21 · Spring Boot 4.1 · JPA · Jackson 3（tools.jackson）· FastAPI · React 19 + antd 6

**Spec:** `docs/design/specs/2026-11-11-multiuser-settings-navigation-refactor-design.md` §六

## Global Constraints

- 错误体 `{"detail": 中文}`；跨用户 404。
- API 永不返回完整 key；更新 key 传空 = 保留；脱敏 = 前3+****+后4（≤8 位全 ****）。
- `LLM_MASTER_KEY`（Base64 32 字节）缺失/非法 → 启动快速失败；测试 profile 用固定测试密钥。
- 复习参数校验：intervalDays 6 个 0–365 非递减整数；unknownPenalty 0–5；unknownDelaySeconds 0–86400；language ∈ {zh,en}；verbosity ∈ {concise,standard,detailed}。
- 无激活档案时创建/重生成端点 409「请先在设置中配置 AI 提供商」。
- 现有测试业务断言不变；A 阶段允许适配（AiServiceClient mock 签名、seed 档案）。
- Windows + Git Bash；Maven `./mvnw.cmd`；提交 Conventional Commits 中文；只暂存本任务文件。
- 主题存 localStorage `codeoffer-theme`（light|dark|system），不入数据库。

## 文件结构

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `backend/.../settings/CryptoService.java` | 新建 | AES-GCM 加解密 + 脱敏 |
| `backend/.../settings/LlmProfile.java` / `LlmProfileRepository.java` | 新建 | 档案实体/仓库 |
| `backend/.../settings/AppSetting.java` / `AppSettingRepository.java` | 新建 | KV 实体/仓库 |
| `backend/.../settings/SettingsService.java` | 新建 | KV 读写/校验/默认值/LlmRuntime 快照 |
| `backend/.../settings/ProfileService.java` | 新建 | 档案 CRUD/激活/测试/import-env |
| `backend/.../settings/SettingsController.java` | 新建 | /api/settings 全部端点 |
| `backend/.../ai/LlmRuntime.java` | 新建 | record(baseUrl,apiKey,model,verifySsl,language,verbosity) |
| `backend/.../ai/AiServiceClient.java` | 修改 | 生成方法加 LlmRuntime,请求体加 llm/prefs;testConnection/cacheInfo/clearCache |
| `backend/.../ai/GenerateService.java` | 修改 | runFullGeneration 加 userId;快照 runtime;regenerate* 加 userId |
| `backend/.../project/{ProjectService,CardService,ResumeService,ProjectController}.java` | 修改 | 预检 409 + userId 透传 + 复习参数读设置 |
| `ai-service/schemas.py` | 修改 | LlmOverride/GenerationPrefs + 五请求可选字段 + TestConnectionRequest |
| `ai-service/llm/gateway.py` | 修改 | from_values、空 key 跳头、_post attempts/max_tokens 参数、chat_test |
| `ai-service/prompts/__init__.py` | 修改 | 构建器加 prefs 注入 |
| `ai-service/main.py` | 修改 | 覆盖 cfg/prefs 传递 + test-connection/cache-info/clear-cache |
| `frontend/src/pages/SettingsPage.tsx` + `components/settings/*` | 新建 | 七分区设置页 |
| `frontend/src/theme/ThemeProvider.tsx` | 新建 | 主题 context + localStorage |
| `frontend/src/api/client.ts` / `types.ts` / `App.tsx` / `AppLayout.tsx` | 修改 | 设置 API + 菜单入口 + 主题接入 |

（`backend/...` = `backend/src/main/java/com/codeoffer/backend`）

---

### Task A1: 加密服务 + 设置数据模型 + 聚合读写

**Files:**
- Create: `settings/CryptoService.java`、`settings/LlmProfile.java`、`settings/LlmProfileRepository.java`、`settings/AppSetting.java`、`settings/AppSettingRepository.java`、`settings/SettingsService.java`
- Modify: `backend/src/test/resources/application-test.yml`（测试主密钥）
- Test: `backend/src/test/java/com/codeoffer/backend/settings/CryptoServiceTest.java`、`SettingsServiceTest.java`

**Interfaces:**
- Produces:
  - `CryptoService.encrypt(String)/decrypt(String)/static mask(String)`
  - `SettingsService.ReviewParams(List<Integer> intervalDays, int unknownPenalty, int unknownDelaySeconds)`
  - `SettingsService.GenerationPrefs(String language, String verbosity)`
  - `SettingsService.SettingsView(Long activeProfileId, ReviewParams review, GenerationPrefs generation)`
  - `SettingsService.get(Long userId) → SettingsView`；`update(Long userId, Long activeProfileId, ReviewParams review, GenerationPrefs generation)`（null 字段=不改）
  - `SettingsService.reviewFor(Long userId) → ReviewParams`
  - 默认值：intervalDays [0,1,3,7,15,30]、penalty 2、delay 60；language zh、verbosity standard
  - 实体：LlmProfile(id,userId,name,provider,baseUrl,apiKeyEnc,model,verifySsl,createdAt,updatedAt)；AppSetting(id,userId,settingKey,value,updatedAt) 唯一(user_id,setting_key)
  - KV 键：`active_profile_id`、`review_params`、`generation_prefs`

- [ ] **Step 1: CryptoServiceTest（失败）**

```java
package com.codeoffer.backend.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    // 32 字节测试密钥(Base64)
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final CryptoService crypto = new CryptoService(KEY);

    @Test
    void roundTrip() {
        String enc = crypto.encrypt("sk-secret-123456");
        assertTrue(enc.startsWith("v1:"));
        assertNotEquals("sk-secret-123456", enc);
        assertEquals("sk-secret-123456", crypto.decrypt(enc));
    }

    @Test
    void encrypt_isRandomized() {
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
    }

    @Test
    void mask_rules() {
        assertEquals("sk-****3456", CryptoService.mask("sk-secret-123456"));
        assertEquals("****", CryptoService.mask("short"));
        assertEquals("****", CryptoService.mask(null));
    }

    @Test
    void missingKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService(""));
    }

    @Test
    void wrongLengthKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService("AAAA"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd "D:/Internship Program/CodeOffer/backend" && ./mvnw.cmd test -Dtest=CryptoServiceTest -q`
Expected: 编译失败（CryptoService 不存在）。

- [ ] **Step 3: 实现 CryptoService**

```java
package com.codeoffer.backend.settings;

import com.codeoffer.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** LLM key 静态加密:AES-GCM,主密钥 LLM_MASTER_KEY(Base64 32 字节),缺失启动快速失败。 */
@Component
public class CryptoService {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public CryptoService(@Value("${LLM_MASTER_KEY:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("缺少 LLM_MASTER_KEY:请生成 32 字节 Base64 密钥写入 config/.env");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("LLM_MASTER_KEY 不是合法 Base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("LLM_MASTER_KEY 必须为 32 字节(Base64 编码)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥加密失败");
        }
    }

    public String decrypt(String stored) {
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalArgumentException("bad prefix");
            }
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥解密失败,请检查 LLM_MASTER_KEY 是否变更");
        }
    }

    public static String mask(String plain) {
        if (plain == null || plain.length() <= 8) {
            return "****";
        }
        return plain.substring(0, 3) + "****" + plain.substring(plain.length() - 4);
    }
}
```

- [ ] **Step 4: 实体与仓库**

LlmProfile / AppSetting 按 Interfaces 字段建（JPA 注解风格同 AppUser；AppSetting.value `@Column(length = 4000)`；`@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "setting_key"}))`）。仓库：

```java
public interface LlmProfileRepository extends JpaRepository<LlmProfile, Long> {
    java.util.List<LlmProfile> findByUserIdOrderByIdAsc(Long userId);
    java.util.Optional<LlmProfile> findByIdAndUserId(Long id, Long userId);
}

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {
    java.util.Optional<AppSetting> findByUserIdAndSettingKey(Long userId, String settingKey);
}
```

- [ ] **Step 5: SettingsService（KV + 校验 + 默认值）**

```java
package com.codeoffer.backend.settings;

import com.codeoffer.backend.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 用户设置 KV:复习参数/生成偏好/激活档案;含校验与默认值。 */
@Service
public class SettingsService {

    public record ReviewParams(List<Integer> intervalDays, int unknownPenalty, int unknownDelaySeconds) {
    }

    public record GenerationPrefs(String language, String verbosity) {
    }

    public record SettingsView(Long activeProfileId, ReviewParams review, GenerationPrefs generation) {
    }

    public static final String KEY_ACTIVE = "active_profile_id";
    public static final String KEY_REVIEW = "review_params";
    public static final String KEY_GENERATION = "generation_prefs";

    private static final ReviewParams DEFAULT_REVIEW =
            new ReviewParams(List.of(0, 1, 3, 7, 15, 30), 2, 60);
    private static final GenerationPrefs DEFAULT_GENERATION = new GenerationPrefs("zh", "standard");

    private final AppSettingRepository settings;
    private final ObjectMapper mapper;

    public SettingsService(AppSettingRepository settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public SettingsView get(Long userId) {
        return new SettingsView(activeProfileId(userId), reviewFor(userId), generationFor(userId));
    }

    public Long activeProfileId(Long userId) {
        return read(userId, KEY_ACTIVE, Long.class, null);
    }

    public ReviewParams reviewFor(Long userId) {
        return read(userId, KEY_REVIEW, ReviewParams.class, DEFAULT_REVIEW);
    }

    public GenerationPrefs generationFor(Long userId) {
        return read(userId, KEY_GENERATION, GenerationPrefs.class, DEFAULT_GENERATION);
    }

    /** 局部更新:null 字段不改。 */
    public void update(Long userId, Long activeProfileId, ReviewParams review, GenerationPrefs generation) {
        if (activeProfileId != null) {
            write(userId, KEY_ACTIVE, activeProfileId);
        }
        if (review != null) {
            validateReview(review);
            write(userId, KEY_REVIEW, review);
        }
        if (generation != null) {
            validateGeneration(generation);
            write(userId, KEY_GENERATION, generation);
        }
    }

    private void validateReview(ReviewParams r) {
        List<Integer> days = r.intervalDays();
        if (days == null || days.size() != 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "复习间隔需为 6 个等级");
        }
        int prev = -1;
        for (Integer d : days) {
            if (d == null || d < 0 || d > 365 || d < prev) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "复习间隔需为 0-365 且非递减");
            }
            prev = d;
        }
        if (r.unknownPenalty() < 0 || r.unknownPenalty() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生疏扣减需为 0-5");
        }
        if (r.unknownDelaySeconds() < 0 || r.unknownDelaySeconds() > 86400) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生疏延迟需为 0-86400 秒");
        }
    }

    private void validateGeneration(GenerationPrefs g) {
        if (!List.of("zh", "en").contains(g.language())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "语言仅支持 zh / en");
        }
        if (!List.of("concise", "standard", "detailed").contains(g.verbosity())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "篇幅仅支持 concise / standard / detailed");
        }
    }

    private <T> T read(Long userId, String key, Class<T> type, T fallback) {
        return settings.findByUserIdAndSettingKey(userId, key)
                .map(s -> mapper.readValue(s.getValue(), type))
                .orElse(fallback);
    }

    private void write(Long userId, String key, Object value) {
        AppSetting s = settings.findByUserIdAndSettingKey(userId, key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setUserId(userId);
            n.setSettingKey(key);
            return n;
        });
        s.setValue(mapper.writeValueAsString(value));
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }
}
```

- [ ] **Step 6: application-test.yml 加测试主密钥**

```yaml
LLM_MASTER_KEY: AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=
```

- [ ] **Step 7: SettingsServiceTest（H2 集成）**

```java
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
    }

    @Test
    void defaults_whenUnset() {
        SettingsService.SettingsView v = settings.get(uid);
        assertEquals(List.of(0, 1, 3, 7, 15, 30), v.review().intervalDays());
        assertEquals(2, v.review().unknownPenalty());
        assertEquals(60, v.review().unknownDelaySeconds());
        assertEquals("zh", v.generation().language());
        assertEquals("standard", v.generation().verbosity());
    }

    @Test
    void update_partialAndReadBack() {
        settings.update(uid, null, new SettingsService.ReviewParams(List.of(0, 2, 4, 8, 16, 32), 3, 120), null);
        SettingsService.SettingsView v = settings.get(uid);
        assertEquals(List.of(0, 2, 4, 8, 16, 32), v.review().intervalDays());
        assertEquals(3, v.review().unknownPenalty());
        assertEquals("zh", v.generation().language()); // 未改保持默认
        settings.update(uid, null, null, new SettingsService.GenerationPrefs("en", "detailed"));
        assertEquals("en", settings.get(uid).generation().language());
    }

    @Test
    void invalidReview_rejected() {
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 1, 3), 2, 60), null));
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 1, 3, 7, 15, 400), 2, 60), null));
        assertThrows(ApiException.class, () -> settings.update(uid, null,
                new SettingsService.ReviewParams(List.of(0, 5, 3, 7, 15, 30), 2, 60), null));
    }

    @Test
    void invalidGeneration_rejected() {
        assertThrows(ApiException.class, () -> settings.update(uid, null, null,
                new SettingsService.GenerationPrefs("fr", "standard")));
        assertThrows(ApiException.class, () -> settings.update(uid, null, null,
                new SettingsService.GenerationPrefs("zh", "huge")));
    }
}
```

- [ ] **Step 8: 运行全部测试 → 绿 → 提交**

Run: `./mvnw.cmd test -q`
Expected: 全绿。

```bash
git add backend/src/main/java/com/codeoffer/backend/settings/ backend/src/test/java/com/codeoffer/backend/settings/ backend/src/test/resources/application-test.yml
git commit -m "feat(backend): 设置数据模型与 AES-GCM 密钥加密,复习/生成参数 KV 读写"
```

---

### Task A2: 档案 CRUD + 激活 + 连接测试代理 + import-env

**Files:**
- Create: `settings/ProfileService.java`、`settings/SettingsController.java`
- Modify: `ai-service`（test-connection 端点在 A3；A2 的 test 代理先按 A3 契约写,A3 完成后联调）
- Test: `backend/src/test/java/com/codeoffer/backend/settings/ProfileApiTest.java`

**Interfaces:**
- Consumes: A1 CryptoService/SettingsService/仓库；CurrentUser。
- Produces（HTTP,均当前用户作用域）:
  - `GET /api/settings/llm-profiles` → `[{id,name,provider,baseUrl,model,apiKeyMasked,verifySsl,active}]`
  - `POST /api/settings/llm-profiles` `{name,provider,baseUrl,apiKey,model,verifySsl}` → 视图
  - `PUT /api/settings/llm-profiles/{id}` 同请求（apiKey 空=保留）→ 视图
  - `DELETE /api/settings/llm-profiles/{id}` → 204；激活中 409「请先切换激活档案再删除」
  - `POST /api/settings/llm-profiles/{id}/activate` → 204
  - `POST /api/settings/llm-profiles/{id}/test` → `{ok,latencyMs,model,error}`（A3 端点就绪后真实联调）
  - `POST /api/settings/llm-profiles/import-env` → 视图；env 未配置 400「服务器未配置 LLM 环境变量」
  - `GET /api/settings` → SettingsView；`PUT /api/settings` `{activeProfileId?,review?,generation?}` → 204
  - 校验：name 1–50；baseUrl `^https?://`；model 1–100；provider ∈ {deepseek,qwen,zhipu,vllm,custom}
  - `ProfileService.runtimeFor(Long userId) → LlmRuntime`（无激活档案 409「请先在设置中配置 AI 提供商」；解密 key）

- [ ] **Step 1: LlmRuntime record（ai 包）**

```java
package com.codeoffer.backend.ai;

/** 一次生成任务的 LLM 配置快照(请求线程内取得,异步全程透传)。 */
public record LlmRuntime(
        String baseUrl, String apiKey, String model, boolean verifySsl,
        String language, String verbosity) {
}
```

- [ ] **Step 2: ProfileService**

实现 Interfaces 全部方法。要点：
- 视图 record `ProfileView(Long id, String name, String provider, String baseUrl, String model, String apiKeyMasked, boolean verifySsl, boolean active)`；active 由 SettingsService.activeProfileId 比对。
- create/update 校验后 `crypto.encrypt(apiKey)` 存 apiKeyEnc；update 时 apiKey null/blank → 保留原密文。
- activate：`findByIdAndUserId` 存在性校验后 `settings.update(userId, id, null, null)`。
- delete：激活中抛 409。
- import-env：读 `System.getenv` 的 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL（注意 main.py 用 dotenv 加载 .env 到进程 env 的是 Python 进程；**Java 进程 env 不含 .env 的 LLM_***——application.yml 只 import MYSQL？核实：`spring.config.import: optional:file:../config/.env[.properties]` 会把 .env 全部键注入 Spring Environment！故用 `@Value("${LLM_BASE_URL:}")` 等注入读取）；任一缺失 → 400；provider 固定 custom、name「从 .env 导入」。
- runtimeFor：activeProfileId null → 409；profile 不存在（被删）→ 409；decrypt + generationFor 组装 LlmRuntime。
- test：`findByIdAndUserId` → decrypt → `ai.testConnection(runtime)`（A3 提供；A2 先写调用,A3 补实现后联调）。**Java 侧把 AI 服务的 snake_case 转 camelCase**：`latency_ms → latencyMs`。

- [ ] **Step 3: SettingsController**

按 Interfaces 路由委托；`PUT /api/settings` 请求 record `UpdateSettingsRequest(Long activeProfileId, SettingsService.ReviewParams review, SettingsService.GenerationPrefs generation)`；激活档案 id 校验归属（settings.update 前若 activeProfileId != null 需 profileService 校验存在,否则 404）。

- [ ] **Step 4: ProfileApiTest（MockMvc,seed 用户+认证,断言脱敏/保留/禁删/隔离）**

```java
package com.codeoffer.backend.settings;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProfileApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        for (String name : new String[]{"prof_a", "prof_b"}) {
            if (users.findByUsername(name).isEmpty()) {
                AppUser u = new AppUser();
                u.setUsername(name);
                u.setPasswordHash(encoder.encode("pass12345"));
                users.save(u);
            }
        }
    }

    private String createProfile(String user, String name) throws Exception {
        return mvc.perform(post("/api/settings/llm-profiles").with(user(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"provider\":\"custom\",\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"sk-secret-123456\",\"model\":\"m1\",\"verifySsl\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked").value("sk-****3456"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_masksKey_andListShowsActive() throws Exception {
        String body = createProfile("prof_a", "p1");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(post("/api/settings/llm-profiles/{id}/activate", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/settings/llm-profiles").with(user("prof_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].apiKeyMasked").value("sk-****3456"));
        // 完整 key 绝不出现在任何响应
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("sk-secret-123456"));
    }

    @Test
    void update_emptyKey_keepsOld() throws Exception {
        String body = createProfile("prof_a", "p2");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(put("/api/settings/llm-profiles/{id}", id).with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p2-renamed\",\"provider\":\"custom\",\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"\",\"model\":\"m2\",\"verifySsl\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("p2-renamed"))
                .andExpect(jsonPath("$.apiKeyMasked").value("sk-****3456"));
    }

    @Test
    void deleteActive_conflict() throws Exception {
        String body = createProfile("prof_a", "p3");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(post("/api/settings/llm-profiles/{id}/activate", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/settings/llm-profiles/{id}", id).with(user("prof_a")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先切换激活档案再删除"));
    }

    @Test
    void crossUser_profileInvisible() throws Exception {
        String body = createProfile("prof_a", "p4");
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(put("/api/settings/llm-profiles/{id}", id).with(user("prof_b")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hack\",\"provider\":\"custom\",\"baseUrl\":\"https://x.test/v1\",\"apiKey\":\"\",\"model\":\"m\",\"verifySsl\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/settings/llm-profiles").with(user("prof_b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void settingsAggregate_defaultsAndUpdate() throws Exception {
        mvc.perform(get("/api/settings").with(user("prof_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.intervalDays.length()").value(6))
                .andExpect(jsonPath("$.generation.language").value("zh"));
        mvc.perform(put("/api/settings").with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"generation\":{\"language\":\"en\",\"verbosity\":\"concise\"}}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/settings").with(user("prof_a")))
                .andExpect(jsonPath("$.generation.language").value("en"));
    }

    @Test
    void invalidProfile_rejected() throws Exception {
        mvc.perform(post("/api/settings/llm-profiles").with(user("prof_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"provider\":\"custom\",\"baseUrl\":\"ftp://x\",\"apiKey\":\"k\",\"model\":\"\",\"verifySsl\":true}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 5: 运行全部测试 → 绿 → 提交**

（test 端点此时调用 AiServiceClient.testConnection 尚未实现 → 本任务先在 AiServiceClient 加方法签名与实现,指向 A3 将提供的 `/api/ai/test-connection`；MockMvc 测试不触发 test 端点,故可绿。）

```bash
git add backend/src/main/java/com/codeoffer/backend/settings/ backend/src/main/java/com/codeoffer/backend/ai/LlmRuntime.java backend/src/main/java/com/codeoffer/backend/ai/AiServiceClient.java backend/src/test/java/com/codeoffer/backend/settings/ProfileApiTest.java
git commit -m "feat(backend): LLM 档案 CRUD/激活/脱敏与设置聚合 API"
```

---

### Task A3: ai-service 配置覆盖 + prefs 注入 + 新端点

**Files:**
- Modify: `ai-service/schemas.py`、`ai-service/llm/gateway.py`、`ai-service/prompts/__init__.py`、`ai-service/main.py`
- Test: `ai-service/tests/test_overrides.py`

**Interfaces:**
- Consumes: A2 的请求契约 `llm:{base_url,api_key,model,verify_ssl}`、`prefs:{language,verbosity}`。
- Produces:
  - `LLMConfig.from_values(base_url, api_key, model, verify_ssl)`；`configured` 仅要求 base_url；空 key 跳 Authorization 头
  - `_post(..., attempts=MAX_RETRIES, max_tokens=8000)`；`chat_test(cfg) → dict{ok,latency_ms,model,error}`（单次、15s、max_tokens 16）
  - 五个生成端点接受可选 llm/prefs；prompt 构建器 `prefs` 参数注入 system 指令
  - `POST /api/ai/test-connection` `{llm}` → `{ok,latency_ms,model,error}`
  - `GET /api/repos/cache-info` → `{size_bytes,repo_count}`；`POST /api/repos/clear-cache` → `{ok,removed}`（仅 REPO_DATA_DIR 直接子项）

- [ ] **Step 1: test_overrides.py（失败）**

```python
"""请求级 LLM 覆盖与 prefs 注入。"""
import httpx
import pytest
from fastapi.testclient import TestClient

import main
from llm.gateway import LLMConfig


def test_from_values_and_empty_key_skips_auth():
    cfg = LLMConfig.from_values("https://x.test/v1", "", "m", False)
    assert cfg.configured  # base_url 非空即可(本地 vLLM 无鉴权)
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": 1}'}}]})

    from llm.gateway import chat_json
    chat_json([{"role": "user", "content": "hi"}], cfg=cfg, transport=httpx.MockTransport(handler))
    assert captured["auth"] is None


def test_endpoint_llm_override_used(monkeypatch):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.read().decode()
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"cards": []}'}}]})

    monkeypatch.setattr("llm.gateway._client", lambda timeout, transport, verify=True: httpx.Client(transport=httpx.MockTransport(handler)))
    client = TestClient(main.app)
    r = client.post("/api/ai/knowledge-cards", json={
        "repo_info": {"name": "x"}, "types": ["architecture"],
        "llm": {"base_url": "https://override.test/v1", "api_key": "k2", "model": "m2", "verify_ssl": True},
        "prefs": {"language": "en", "verbosity": "concise"},
    })
    assert r.status_code == 200
    assert seen["url"].startswith("https://override.test/v1/")
    assert '"model": "m2"' in seen["body"] or '"model":"m2"' in seen["body"]


def test_prefs_injected_into_prompts():
    from prompts import build_knowledge_cards_messages
    msgs = build_knowledge_cards_messages({"name": "x"}, ["architecture"], prefs={"language": "en", "verbosity": "concise"})
    system = " ".join(m["content"] for m in msgs if m["role"] == "system")
    assert "English" in system
    assert "精简" in system or "concise" in system.lower()


def test_test_connection_endpoint(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": "pong"}}], "model": "m-echo"})

    monkeypatch.setattr("llm.gateway._client", lambda timeout, transport, verify=True: httpx.Client(transport=httpx.MockTransport(handler)))
    client = TestClient(main.app)
    r = client.post("/api/ai/test-connection", json={"llm": {"base_url": "https://x.test/v1", "api_key": "k", "model": "m", "verify_ssl": True}})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["model"] == "m-echo"
    assert body["latency_ms"] >= 0


def test_cache_info_and_clear(tmp_path, monkeypatch):
    (tmp_path / "repo1").mkdir()
    (tmp_path / "repo1" / "f.txt").write_text("abc")
    (tmp_path / "repo2").mkdir()
    monkeypatch.setattr(main, "REPO_DATA_DIR", tmp_path)
    client = TestClient(main.app)
    info = client.get("/api/repos/cache-info").json()
    assert info["repo_count"] == 2
    assert info["size_bytes"] >= 3
    cleared = client.post("/api/repos/clear-cache").json()
    assert cleared["ok"] is True
    assert cleared["removed"] == 2
    assert list(tmp_path.iterdir()) == []
```

- [ ] **Step 2: 运行确认失败**

Run: `cd "D:/Internship Program/CodeOffer/ai-service" && .venv/Scripts/python -m pytest tests/test_overrides.py -q`
Expected: 失败（from_values/端点不存在）。

- [ ] **Step 3: 实现 gateway 改动**

- `LLMConfig.__init__` 保持 env 读取；加 `@classmethod from_values(cls, base_url, api_key, model, verify_ssl)` 构造实例并赋属性（rstrip("/") base_url）。
- `configured` → `bool(self.base_url)`。
- `_post` headers：`headers = {"Content-Type": "application/json"}`；`if cfg.api_key: headers["Authorization"] = f"Bearer {cfg.api_key}"`。
- `_post` 签名加 `attempts: int = MAX_RETRIES, max_tokens: int = 8000`；循环 `range(1, attempts+1)`；payload max_tokens 用参数；重试条件 `attempt < attempts`。
- `chat_test(cfg)`：`t0=time.monotonic()`；try `_post([{"role":"user","content":"ping"}], cfg, temperature=0.0, timeout=15.0, attempts=1, max_tokens=16)` → 返回 `{"ok": True, "latency_ms": int((time.monotonic()-t0)*1000), "model": cfg.model, "error": None}`；except LLMError as e → `{"ok": False, "latency_ms": ..., "model": cfg.model, "error": str(e)[:300]}`。model 回显：_post 只返回 content——chat_test 需要响应 model：改 _post 返回？保持 _post 不变,chat_test 的 model 用 cfg.model（spec 说模型回显——用响应 model 更真：给 _post 加 `return_model: bool=False`？简化：chat_test 自行发请求不复用 _post？重复重试逻辑不好。**决定**：_post 增加可选 `raw: bool=False`,raw=True 返回解析后的 dict；chat_test 用 raw 取 data.get("model", cfg.model)。）

- [ ] **Step 4: schemas + prompts + main**

- schemas：`class LlmOverride(BaseModel): base_url: str; api_key: str = ""; model: str; verify_ssl: bool = True`；`class GenerationPrefs(BaseModel): language: str = "zh"; verbosity: str = "standard"`；五个生成请求加 `llm: LlmOverride | None = None`、`prefs: GenerationPrefs | None = None`；`class TestConnectionRequest(BaseModel): llm: LlmOverride`。
- prompts：每个 build_* 加 `prefs: dict | None = None` 参数；`_prefs_instruction(prefs)` 返回附加 system 文本（zh:「所有自然语言输出使用简体中文。」en:「All natural-language output in English.」；concise:「内容精简,直击要点,篇幅约为标准的一半。」detailed:「内容详尽,展开技术细节与示例,篇幅约为标准的 1.5 倍。」standard 无）；追加到 system content 末尾。
- main：`_cfg_for(req)` → req.llm 存在则 LLMConfig.from_values(...) 否则 None；`_run_json(messages, what, cfg=None)` 传 cfg；各端点构建 messages 时传 `prefs=req.prefs.model_dump() if req.prefs else None`；新端点 test-connection/cache-info/clear-cache（clear 仅删 `REPO_DATA_DIR` 直接子项：`for p in REPO_DATA_DIR.iterdir(): shutil.rmtree(p) if p.is_dir() else p.unlink()`，根目录不存在时返回 0）。

- [ ] **Step 5: 全量 pytest → 绿 → 提交**

Run: `.venv/Scripts/python -m pytest -q`
Expected: 全绿（现有 prompts 调用若位置参数兼容则不改旧测试）。

```bash
git add ai-service/schemas.py ai-service/llm/gateway.py ai-service/prompts/__init__.py ai-service/main.py ai-service/tests/test_overrides.py
git commit -m "feat(ai-service): 请求级 LLM 覆盖与生成偏好注入,连接测试与缓存管理端点"
```

---

### Task A4: Java 运行时注入 + 复习参数生效 + 废除 env 回退

**Files:**
- Modify: `ai/AiServiceClient.java`（生成方法加 LlmRuntime + llm/prefs body）、`ai/GenerateService.java`（userId + 快照）、`project/ProjectService.java`（预检）、`project/CardService.java`（预检+复习参数）、`project/ResumeService.java`（预检）、`project/ProjectController.java`（透传不变,service 签名变）
- Modify: 现有测试适配（mock 签名 + seed 激活档案）
- Test: `backend/src/test/java/com/codeoffer/backend/SettingsInjectionTest.java`

**Interfaces:**
- Consumes: A2 ProfileService.runtimeFor；A3 请求契约。
- Produces: `AiServiceClient.knowledgeCards(Map repoInfo, List<String> types, LlmRuntime rt)` 等五方法；`testConnection(LlmRuntime) → Map`；`GenerateService.runFullGeneration(Long projectId, Long taskId, Long userId)`；`regenerateCard(Long cardId, Long userId)`；`regenerateResume(Long projectId, Long userId)`；复习按用户设置计算。

- [ ] **Step 1: AiServiceClient 注入 llm/prefs**

私有 `Map<String,Object> llmBody(LlmRuntime rt)` → `{"llm": {base_url,api_key,model,verify_ssl}, "prefs": {language,verbosity}}`；五个生成方法 body 合并该 map（`Map` 不可变 → 用 `new java.util.HashMap<>(...)` 合并）。testConnection：`post("/api/ai/test-connection", Map.of("llm", llmMap(rt)))`，读超时单独 30s？RestClient 全局 220s——test 端点自身 15s 返回,可接受共用。cacheInfo/clearCache：`post/get` 对应路径（get 用 rest.get()）。

- [ ] **Step 2: GenerateService 快照与透传**

- `runFullGeneration(projectId, taskId, userId)`：doRun 内加载 project/task 后 `LlmRuntime rt = profileService.runtimeFor(userId);`（失败 → 走 catch 置 FAILED,消息友好）。
- 五处 ai 调用传 rt；`regenerateCard(cardId, userId)` / `regenerateResume(projectId, userId)` 内部 runtimeFor。
- 注意循环依赖：ProfileService 依赖 AiServiceClient（test）,GenerateService 依赖 ProfileService,AiServiceClient 不依赖 GenerateService → 无环。

- [ ] **Step 3: 预检与复习参数**

- ProjectService.create/regenerate：开头 `profileService.runtimeFor(userId)` 预检（409 友好）——但 create 内随后异步再快照一次（一致）。
  循环依赖警告：ProjectService → ProfileService → AiServiceClient；GenerateService → ProfileService；ProjectService → GenerateService。无环 ✓。
- CardService.regenerateCard/reviewCard：regenerate 预检；reviewCard 用 `settings.reviewFor(userId)`：known → mastery+1,间隔 `days.get(mastery)`；unknown → mastery-penalty（clamp 0）,delay 秒。
- ResumeService.regenerate 预检。

- [ ] **Step 4: 现有测试适配（断言不变）**

- 所有 `when(ai.knowledgeCards(any(), anyList()))` → `when(ai.knowledgeCards(any(), anyList(), any()))`；flowDiagram/resume/interviewQuestions 同加 `any()`；healthReport → `healthReport(anyString(), any(), any())`。
- seed 用户处追加：为该用户建激活档案（直接 repository：LlmProfile + crypto.encrypt("test-key") + SettingsService.update(uid, profileId, null, null)）——抽公共测试 helper `TestProfiles.seedActive(users, encoder, crypto, settings, username)` 放 `backend/src/test/java/com/codeoffer/backend/TestSupport.java`，各测试类 @BeforeEach 调用。
- GenerateFlowIntegrationTest/HealthReportIntegrationTest：runFullGeneration 三参（传 seed uid）。

- [ ] **Step 5: SettingsInjectionTest（失败→绿）**

```java
package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.LlmRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

/** 运行时注入:生成请求携带用户档案;无档案 409;复习参数按设置生效。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SettingsInjectionTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private AiServiceClient ai;

    @Test
    void noProfile_create409() throws Exception {
        // inject_noprofile 用户不 seed 档案
        TestSupport.seedUserOnly("inject_noprofile");
        mvc.perform(post("/api/projects").with(user("inject_noprofile")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/inject-" + System.nanoTime() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先在设置中配置 AI 提供商"));
    }

    @Test
    void generation_carriesUserProfile() throws Exception {
        TestSupport.seedWithProfile("inject_user", "https://profile.test/v1", "profile-key", "profile-model");
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake");
        when(ai.analyze(anyString())).thenReturn(java.util.Map.of("name", "x"));
        when(ai.healthReport(anyString(), any(), any())).thenReturn(null);
        when(ai.knowledgeCards(any(), anyList(), any())).thenReturn(java.util.Map.of("cards", java.util.List.of()));
        when(ai.flowDiagram(any(), any())).thenReturn(java.util.Map.of("title", "t", "description", "d", "mermaid", "m"));
        when(ai.resume(any(), any())).thenReturn(java.util.Map.of("star", java.util.Map.of()));
        when(ai.interviewQuestions(any(), any())).thenReturn(java.util.Map.of("questions", java.util.List.of()));

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
```

（TestSupport 提供 seedUserOnly / seedWithProfile：建用户 + 档案 + 激活；@Autowired 静态注入不便 → TestSupport 做成 @Component 测试 bean？简化：TestSupport 为普通类,构造接收 repositories,测试类 new TestSupport(users, profiles, settings, crypto, encoder) 调用。实现时按此调整,断言不变。）

- [ ] **Step 6: 全量测试 → 绿 → 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/ backend/src/test/java/com/codeoffer/backend/
git commit -m "feat(backend): 生成请求注入用户 LLM 档案与偏好,复习参数按设置生效,废除 env 回退"
```

---

### Task A5: 备份导出 + 缓存管理（ADMIN）

**Files:**
- Modify: `settings/SettingsController.java`、`settings/ProfileService.java`（或新建 BackupService）
- Test: `backend/src/test/java/com/codeoffer/backend/settings/DataManagementApiTest.java`

**Interfaces:**
- `GET /api/settings/backup` → `{exportedAt, username, projects:[{id,name,repoUrl,status,description,techStack,healthReport,cards:[CardView 全字段],resume:ResumeView}]}`（仅当前用户）
- `GET /api/settings/storage-info`（ADMIN）→ AI cache-info 透传并转 camelCase `{sizeBytes,repoCount}`
- `POST /api/settings/clear-repo-cache`（ADMIN）→ `{ok,removed}`；非 ADMIN 403

- [ ] **Step 1: 实现 BackupService + 端点**

BackupService.export(userId)：projects.findAllByUserId → 组装（cards/resumes 按 projectId 查）。storage/clear：controller 检查 `currentUser.require().getRole() == ADMIN` 否则 ApiException 403「无权访问」；委托 AiServiceClient.cacheInfo()/clearCache()。

- [ ] **Step 2: DataManagementApiTest**

seed 普通用户+项目（直接 repository,带 userId）与 ADMIN 用户；断言：backup 含自己的项目不含他人；storage-info 普通 403、ADMIN 200（mock AiServiceClient.cacheInfo）；clear 普通 403。

- [ ] **Step 3: 全量测试 → 绿 → 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/settings/ backend/src/main/java/com/codeoffer/backend/ai/AiServiceClient.java backend/src/test/java/com/codeoffer/backend/settings/DataManagementApiTest.java
git commit -m "feat(backend): 用户数据备份导出与管理员克隆缓存管理"
```

---

### Task A6: 前端设置页 + 主题 + 浏览器实测

**Files:**
- Create: `frontend/src/pages/SettingsPage.tsx`、`frontend/src/components/settings/{ProfilesTab,ReviewTab,GenerationTab,DataTab,AccountTab,AppearanceTab,UsersTab}.tsx`、`frontend/src/theme/ThemeProvider.tsx`
- Modify: `frontend/src/api/client.ts`、`types.ts`、`App.tsx`、`components/AppLayout.tsx`

**Interfaces:**
- Consumes: A2/A5 全部 HTTP 端点。
- Produces: `/settings` 路由（AppLayout 内,菜单加「设置」）；七分区（ADMIN 多「用户管理」）；主题 light/dark/system 全局生效。

- [ ] **Step 1: types + client API**

types：`LlmProfileView{id,name,provider,baseUrl,model,apiKeyMasked,verifySsl,active}`、`SettingsView{activeProfileId,review:{intervalDays,unknownPenalty,unknownDelaySeconds},generation:{language,verbosity}}`、`TestResult{ok,latencyMs,model,error}`、`AdminUserView{id,username,role,enabled,createdAt}`。
client：settingsGet/settingsPut/profilesList/profileCreate/profileUpdate/profileDelete/profileActivate/profileTest/profileImportEnv/backupDownload（fetch blob → a.download）/storageInfo/clearCache/adminUsers/adminSetStatus/changePassword。

- [ ] **Step 2: ThemeProvider**

```tsx
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { ConfigProvider, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'

type Mode = 'light' | 'dark' | 'system'
const ThemeContext = createContext<{ mode: Mode; setMode: (m: Mode) => void }>({ mode: 'system', setMode: () => {} })

function resolve(mode: Mode): boolean {
  if (mode === 'dark') return true
  if (mode === 'light') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<Mode>(() => (localStorage.getItem('codeoffer-theme') as Mode) || 'system')
  const [dark, setDark] = useState(() => resolve(mode))
  const setMode = (m: Mode) => { localStorage.setItem('codeoffer-theme', m); setModeState(m); setDark(resolve(m)) }
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => { if (mode === 'system') setDark(mq.matches) }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [mode])
  return (
    <ThemeContext value={{ mode, setMode }}>
      <ConfigProvider locale={zhCN} theme={{ algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm }}>
        {children}
      </ConfigProvider>
    </ThemeContext>
  )
}

export function useTheme() { return useContext(ThemeContext) }
```

App.tsx：ThemeProvider 包在 AuthProvider 外；移除原 ConfigProvider（移入 ThemeProvider）。

- [ ] **Step 3: SettingsPage + 七 Tab**

`Tabs tabPosition="left"`：AI 模型 / 学习复习 / 生成偏好 / 数据管理 / 账号安全 / 外观 / 用户管理(ADMIN)。
- ProfilesTab：档案 Card 列表（active Tag、provider Tag、model、masked key）；新建/编辑 Modal（预设 Select：deepseek→https://api.deepseek.com/v1+deepseek-chat；qwen→https://dashscope.aliyuncs.com/compatible-mode/v1+qwen-plus；zhipu→https://open.bigmodel.cn/api/paas/v4+glm-4-flash；vllm→http://localhost:8000/v1+模型必填；custom 空）；编辑时 key 留空=保留提示；每卡「测试连接」（结果 Alert：ok→延迟+model；fail→error）、「激活」、「删除」Popconfirm；顶部「从 .env 导入」按钮；无档案 Alert 引导。
- ReviewTab：6 个 InputNumber + penalty + delay；保存调 settingsPut；「恢复默认」按钮。
- GenerationTab：language/verbosity Select + 保存。
- DataTab：备份下载按钮；ADMIN 区：缓存占用展示 + 清理 Popconfirm。
- AccountTab：旧密码/新密码/确认 → changePassword；成功 message + 提示其它会话已失效。
- AppearanceTab：Radio.Group light/dark/system → useTheme().setMode。
- UsersTab：Table（username/role/enabled Switch/createdAt）；自己行 Switch disabled；调 adminSetStatus。

- [ ] **Step 4: AppLayout 菜单加「设置」**（selectedKeys 按 pathname 前缀）。

- [ ] **Step 5: build + lint → 绿**

Run: `cd frontend && npm run build && npm run lint`

- [ ] **Step 6: 浏览器实测（dev-up 三服务已在跑;后端需重启加载 A 代码）**

重启后端（dev-up 或单独 mvnw）；浏览器登录 demo_mb：
1. /settings 七分区可见（ADMIN 含用户管理）；
2. 「从 .env 导入」→ 档案出现（masked key）→ 激活 → 测试连接返回真实延迟；
3. 新建第二档案（bad base_url https://invalid.test/v1）→ 激活 → 单卡重生成 → 错误提及 invalid.test（**证明运行时注入**）→ 切回好档案；
4. 复习参数改 [0,2,4,8,16,32] → 自评掌握 → nextReview ≈ 2 天后（API 断言）；
5. 生成偏好 en/concise 保存 → 重生成单卡 → 卡片内容英文（抽查）；
6. 备份下载 JSON 含自己的项目；
7. 外观切暗色 → 全局变暗 → 刷新保持；
8. 改密码 → 成功；用户管理列表可见 iso_demo。

- [ ] **Step 7: 提交**

```bash
git add frontend/src/
git commit -m "feat(frontend): 设置页七分区(档案/复习/偏好/数据/账号/外观/用户管理)与全局主题"
```

---

## 验收（对照 spec §八 A 条）

- [ ] 档案切换后下一次生成走新端点（SettingsInjectionTest + 浏览器 bad-endpoint 证明）
- [ ] 测试连接返回真实延迟（浏览器）
- [ ] key 完整值不出现在任何 API 响应/日志（ProfileApiTest + 抽查）
- [ ] 复习参数修改后自评立即按新间隔（浏览器/API）
- [ ] 无档案 409 友好提示（SettingsInjectionTest）
- [ ] 非 ADMIN 缓存管理 403（DataManagementApiTest）
- [ ] 后端/ai-service 全量测试绿；前端 build+lint 绿