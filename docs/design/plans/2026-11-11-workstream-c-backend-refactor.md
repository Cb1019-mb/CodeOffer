# 工作流 C：后端分层重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 415 行的 ProjectController 拆成「控制器—服务」分层，统一错误契约为 `{"detail": 中文消息}`，接通体检报告生成，删除死枚举——对外 API 行为保持兼容。

**Architecture:** 新增 common 包（ApiException + GlobalExceptionHandler）；project 包内抽 ProjectService / CardService / ResumeService，控制器只做路由与参数接收；GenerateService 流水线插入体检报告调用（失败不阻断）。映射逻辑收敛为 ApiViews 上的静态工厂。

**Tech Stack:** Java 21 · Spring Boot 4.1.0 · Spring Data JPA · Jackson 3（`tools.jackson.databind`，**不是** com.fasterxml）· JUnit5 + MockMvc + H2（test profile）

**Spec:** `docs/design/specs/2026-11-11-multiuser-settings-navigation-refactor-design.md` §四

## Global Constraints

- 不新增任何 Maven 依赖（C 阶段）。
- 所有错误响应体必须是 `{"detail": "<中文消息>"}`；兜底 500 不回显堆栈。
- 现有 3 个测试类（BackendApplicationTests / ProjectControllerTest / GenerateFlowIntegrationTest）**文件内容不得修改**，必须原样通过。
- 现有端点路径、请求/响应 JSON 结构、状态机流转不变。
- 平台 Windows + Git Bash；Maven 命令在 `backend/` 目录下用 `./mvnw.cmd` 执行。
- 提交信息：Conventional Commits，中文主题，scope 用 `backend`。
- 每个任务结束提交一次；只暂存本任务涉及的文件（`git add <明确路径>`，禁止 `git add -A`）。
- 本工作流不改 ai-service 与 frontend 代码。

## 文件结构

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `backend/src/main/java/com/codeoffer/backend/common/ApiException.java` | 新建 | 业务异常（HttpStatus + 中文消息） |
| `backend/src/main/java/com/codeoffer/backend/common/GlobalExceptionHandler.java` | 新建 | 统一错误渲染 |
| `backend/src/main/java/com/codeoffer/backend/project/ProjectService.java` | 新建 | 项目域：录入/列表/详情/确认/删除/重生成 + URL 校验 |
| `backend/src/main/java/com/codeoffer/backend/project/CardService.java` | 新建 | 卡片域：编辑/采纳/补充/重生成/自评/复习队列 |
| `backend/src/main/java/com/codeoffer/backend/project/ResumeService.java` | 新建 | 简历域：STAR 编辑/重生成委托 |
| `backend/src/main/java/com/codeoffer/backend/project/ApiViews.java` | 修改 | CardView.from / ResumeView.from 静态工厂 |
| `backend/src/main/java/com/codeoffer/backend/project/ProjectController.java` | 修改 | 瘦身到 ~110 行，纯委托 |
| `backend/src/main/java/com/codeoffer/backend/project/CardType.java` | 修改 | 删除 COMPARE_PRACTICE |
| `backend/src/main/java/com/codeoffer/backend/ai/GenerateService.java` | 修改 | 体检报告接线 + 进度重排 |
| `backend/src/test/java/com/codeoffer/backend/ErrorContractTest.java` | 新建 | 错误契约集成测试 |
| `backend/src/test/java/com/codeoffer/backend/HealthReportIntegrationTest.java` | 新建 | 体检报告接线集成测试 |
| `backend/src/test/java/com/codeoffer/backend/ai/HealthContentTest.java` | 新建 | Markdown 转换单测 |

---

### Task 1: 统一错误契约（common 包）

**Files:**
- Create: `backend/src/main/java/com/codeoffer/backend/common/ApiException.java`
- Create: `backend/src/main/java/com/codeoffer/backend/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/codeoffer/backend/ErrorContractTest.java`

**Interfaces:**
- Consumes: 现有 `ProjectController` 抛出的 `ResponseStatusException`（本任务不改控制器）。
- Produces: `ApiException(HttpStatus status, String message)` + `getStatus()`；后续所有 service 抛此异常。错误体契约 `{"detail": "..."}`。

- [ ] **Step 1: 写失败测试**

```java
package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 错误契约:所有错误响应体必须含中文 detail 字段(前端 client.ts 依赖)。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ErrorContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private ProjectRepository projects;
    @MockitoBean private AiServiceClient ai;

    @Test
    void duplicateCreate_returns409WithChineseDetail() throws Exception {
        Project p = new Project();
        p.setRepoUrl("https://github.com/octocat/error-contract-dup");
        projects.save(p);

        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"https://github.com/octocat/error-contract-dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("该仓库已录入过,请勿重复提交"));
    }

    @Test
    void unknownProject_returns404WithDetail() throws Exception {
        mvc.perform(get("/api/projects/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("项目不存在: 999999"));
    }

    @Test
    void blankUrl_returns400WithDetail() throws Exception {
        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("repoUrl 不能为空"));
    }

    @Test
    void unknownApiPath_returns404WithDetail() throws Exception {
        mvc.perform(get("/api/definitely-not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void malformedBody_returns400WithDetail() throws Exception {
        mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd "D:/Internship Program/CodeOffer/backend" && ./mvnw.cmd test -Dtest=ErrorContractTest -q`
Expected: FAIL — `duplicateCreate`/`unknownProject`/`blankUrl` 断言 `$.detail` 不存在（Spring 默认错误体无 detail）。

- [ ] **Step 3: 实现 ApiException**

```java
package com.codeoffer.backend.common;

import org.springframework.http.HttpStatus;

/** 业务异常:携带 HTTP 状态码与面向用户的中文消息,由 GlobalExceptionHandler 统一渲染。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
```

- [ ] **Step 4: 实现 GlobalExceptionHandler**

```java
package com.codeoffer.backend.common;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一错误契约:所有错误响应体为 {"detail": "<中文消息>"},与前端 client.ts 对齐。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? "请求失败" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("detail", reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getFieldErrors().stream()
                .findFirst()
                .map(f -> Objects.requireNonNullElse(f.getDefaultMessage(), "参数校验失败"))
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Map.of("detail", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", "请求体格式错误"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("detail", "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "服务器内部错误,请查看后端日志"));
    }
}
```

- [ ] **Step 5: 运行确认通过（含全部现有测试）**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS（ErrorContractTest 5 个 + 现有测试）。若 `unknownApiPath` 因 SPA fallback 返回 200，检查请求路径确以 `/api` 开头（SpaFallbackFilter 只放行非 /api 路径）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/common/ backend/src/test/java/com/codeoffer/backend/ErrorContractTest.java
git commit -m "feat(backend): 统一错误契约为 {detail} 中文消息,修复前端拿不到错误提示"
```

---

### Task 2: ProjectService 抽取 + ApiViews 静态工厂

**Files:**
- Create: `backend/src/main/java/com/codeoffer/backend/project/ProjectService.java`
- Modify: `backend/src/main/java/com/codeoffer/backend/project/ApiViews.java`（CardView/ResumeView 加静态工厂）
- Modify: `backend/src/main/java/com/codeoffer/backend/project/ProjectController.java`（项目域端点改委托）

**Interfaces:**
- Consumes: Task 1 的 `ApiException(HttpStatus, String)`。
- Produces: `ProjectService` 公开方法（后续任务依赖）：
  - `CreateResponse create(CreateRequest req)`
  - `List<ProjectView> list()`
  - `ProjectDetail detail(Long id)`
  - `TaskView latestTask(Long id)`
  - `List<CardView> cards(Long id)`
  - `ResumeView resume(Long id)`
  - `ProjectDetail confirm(Long id)`
  - `void delete(Long id)`
  - `CreateResponse regenerate(Long id)`
  - `Project requireProject(Long id)`（public，CardService/ResumeService 复用）
  - `ApiViews.CardView.from(KnowledgeCard c)` / `ApiViews.ResumeView.from(ResumeEntry r)` 静态工厂

- [ ] **Step 1: ApiViews 加静态工厂**

在 `CardView` record 体内加：

```java
        public static CardView from(KnowledgeCard c) {
            return new CardView(
                    c.getId(),
                    c.getType().name(),
                    c.getType().getLabel(),
                    c.getTitle(),
                    c.getContent(),
                    c.getAiDraft(),
                    c.getUserInput(),
                    c.isEdited(),
                    c.getMastery(),
                    c.getNextReview());
        }
```

在 `ResumeView` record 体内加：

```java
        public static ResumeView from(ResumeEntry r) {
            return new ResumeView(r.getStarContent(), r.getInterviewQas(), r.isEdited());
        }
```

- [ ] **Step 2: 实现 ProjectService**

把 ProjectController 中项目域逻辑**原样搬移**（行为不变），`ResponseStatusException` 全部替换为 `ApiException`，`toCardView/toResumeView` 改用静态工厂：

```java
package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.ApiViews.CardView;
import com.codeoffer.backend.project.ApiViews.CreateRequest;
import com.codeoffer.backend.project.ApiViews.CreateResponse;
import com.codeoffer.backend.project.ApiViews.ProjectDetail;
import com.codeoffer.backend.project.ApiViews.ProjectView;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import com.codeoffer.backend.project.ApiViews.TaskView;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 项目域服务:录入/列表/详情/确认/删除/整仓重生成与仓库 URL 安全校验。 */
@Service
public class ProjectService {

    /** 受信任的仓库托管平台(host 精确匹配或子域)。 */
    private static final List<String> ALLOWED_REPO_HOSTS = List.of(
            "github.com", "gitee.com", "gitlab.com", "gitlab.cn",
            "gitcode.com", "bitbucket.org", "raw.githubusercontent.com");

    private final ProjectRepository projects;
    private final KnowledgeCardRepository cards;
    private final ResumeEntryRepository resumes;
    private final GenTaskRepository tasks;
    private final GenerateService generate;

    public ProjectService(
            ProjectRepository projects,
            KnowledgeCardRepository cards,
            ResumeEntryRepository resumes,
            GenTaskRepository tasks,
            GenerateService generate) {
        this.projects = projects;
        this.cards = cards;
        this.resumes = resumes;
        this.tasks = tasks;
        this.generate = generate;
    }

    // 注意:不加 @Transactional——@Async 生成线程在 READ_COMMITTED 下看不到未提交行,
    // 包事务会导致生成任务被当作「已删除」跳过。两次 save 各自原子,与现状一致。
    public CreateResponse create(CreateRequest req) {
        String url = req.repoUrl() == null ? "" : req.repoUrl().trim();
        if (url.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "repoUrl 不能为空");
        }
        validateRepoUrl(url);
        if (projects.findByRepoUrl(url).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该仓库已录入过,请勿重复提交");
        }
        Project project = new Project();
        project.setRepoUrl(url);
        project.setName(deriveName(url));
        project = projects.save(project);

        GenTask task = new GenTask();
        task.setProjectId(project.getId());
        task = tasks.save(task);

        generate.runFullGeneration(project.getId(), task.getId());
        return new CreateResponse(project.getId(), task.getId(), project.getStatus().name());
    }

    public List<ProjectView> list() {
        return projects.findAll().stream()
                .map(this::toView)
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                .toList();
    }

    public ProjectDetail detail(Long id) {
        Project p = requireProject(id);
        List<CardView> cardViews = cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(CardView::from)
                .toList();
        ResumeView resume = resumes.findByProjectId(id).map(ResumeView::from).orElse(null);
        return new ProjectDetail(
                p.getId(), p.getName(), p.getRepoUrl(), p.getDescription(),
                p.getStatus().name(), p.getTechStack(), p.getHealthReport(),
                cardViews, resume);
    }

    public TaskView latestTask(Long id) {
        requireProject(id);
        return tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> new TaskView(
                        t.getId(), t.getType().name(), t.getStatus().name(),
                        t.getProgress(), t.getErrorMsg()))
                .orElse(null);
    }

    public List<CardView> cards(Long id) {
        requireProject(id);
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .map(CardView::from)
                .toList();
    }

    public ResumeView resume(Long id) {
        requireProject(id);
        return resumes.findByProjectId(id)
                .map(ResumeView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "尚无简历产出"));
    }

    public ProjectDetail confirm(Long id) {
        Project p = requireProject(id);
        if (p.getStatus() != ProjectStatus.REVIEW && p.getStatus() != ProjectStatus.DONE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "当前状态 " + p.getStatus() + " 不可确认,需先生成完毕(REVIEW 状态)");
        }
        p.setStatus(ProjectStatus.DONE);
        p.setUpdatedAt(Instant.now());
        projects.save(p);
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        requireProject(id);
        cards.deleteByProjectId(id);
        resumes.findByProjectId(id).ifPresent(resumes::delete);
        tasks.findByProjectIdOrderByIdDesc(id).forEach(tasks::delete);
        projects.deleteById(id);
    }

    public CreateResponse regenerate(Long id) {
        Project p = requireProject(id);
        if (tasks.findFirstByProjectIdOrderByIdDesc(id)
                .map(t -> t.getStatus() == TaskStatus.RUNNING)
                .orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "已有生成任务进行中,请等待完成后再重新生成");
        }
        p.setStatus(ProjectStatus.PENDING);
        p.setUpdatedAt(Instant.now());
        projects.save(p);
        GenTask task = new GenTask();
        task.setProjectId(id);
        task = tasks.save(task);
        generate.runFullGeneration(id, task.getId());
        return new CreateResponse(id, task.getId(), p.getStatus().name());
    }

    public Project requireProject(Long id) {
        return projects.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "项目不存在: " + id));
    }

    /**
     * 校验仓库地址:仅允许 https(受信任托管平台)或本地 file 仓库(e2e 场景),
     * 拒绝携带凭证(user:pass@)的 URL,防止 SSRF 与凭证泄露。
     */
    private static void validateRepoUrl(String url) {
        if (url.toLowerCase().startsWith("file://")) {
            if (url.contains("@")) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仓库地址格式不合法");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仓库地址缺少协议(如 https://)");
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 https 或本地 file 仓库地址");
        }
        String host = uri.getHost();
        boolean allowed = host != null && ALLOWED_REPO_HOSTS.stream()
                .anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!allowed) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "仅支持受信任的仓库托管平台: " + String.join(" / ", ALLOWED_REPO_HOSTS));
        }
    }

    private ProjectView toView(Project p) {
        List<KnowledgeCard> list = cards.findByProjectIdOrderByIdAsc(p.getId());
        long mastered = list.stream().filter(c -> c.getMastery() >= 3).count();
        long due = list.stream()
                .filter(c -> c.getMastery() > 0 && c.getNextReview() != null
                        && c.getNextReview().isBefore(Instant.now()))
                .count();
        return new ProjectView(
                p.getId(), p.getName(), p.getRepoUrl(), p.getStatus().name(),
                (long) list.size(), mastered, due, p.getUpdatedAt());
    }

    private static String deriveName(String repoUrl) {
        String trimmed = repoUrl.endsWith(".git") ? repoUrl.substring(0, repoUrl.length() - 4) : repoUrl;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
```

- [ ] **Step 3: 控制器项目域端点改委托**

ProjectController 中：构造器改为只注入 `ProjectService`（卡片/简历端点暂时保留原 Repository 直连，Task 3/4 再迁）；`create/list/detail/latestTask/cards/resume/confirm/delete/regenerate` 九个端点方法体替换为单行委托，例如：

```java
    @PostMapping
    public CreateResponse create(@RequestBody CreateRequest req) {
        return projectService.create(req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        projectService.delete(id);
    }
```

其余七个同型（`list()` / `detail(id)` / `latestTask(id)` / `cards(id)` / `resume(id)` / `confirm(id)` / `regenerate(id)`）。删除控制器中已搬走的私有方法（validateRepoUrl / deriveName / requireProject / toView）与 `ALLOWED_REPO_HOSTS`；`toCardView/toResumeView` 暂留（卡片/简历端点还在用），`requireCard` 暂留。删除控制器上的 `@Transactional`（已移入 service）。

- [ ] **Step 4: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS，现有测试文件零改动。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/project/ProjectService.java backend/src/main/java/com/codeoffer/backend/project/ApiViews.java backend/src/main/java/com/codeoffer/backend/project/ProjectController.java
git commit -m "refactor(backend): 抽取 ProjectService,项目域逻辑与事务边界移出控制器"
```

---

### Task 3: CardService 抽取

**Files:**
- Create: `backend/src/main/java/com/codeoffer/backend/project/CardService.java`
- Modify: `backend/src/main/java/com/codeoffer/backend/project/ProjectController.java`（卡片域端点改委托）

**Interfaces:**
- Consumes: `ProjectService.requireProject(Long)`、`ApiViews.CardView.from(KnowledgeCard)`、`GenerateService.regenerateCard(Long cardId)`（现有签名）。
- Produces: `CardService` 公开方法：`CardView editCard(Long id, Long cardId, String content)`、`CardView adoptDraft(Long id, Long cardId)`、`CardView addUserInput(Long id, Long cardId, String input)`、`CardView regenerateCard(Long id, Long cardId)`、`CardView reviewCard(Long id, Long cardId, boolean known)`、`List<CardView> reviewQueue(Long id)`。复习策略常量 `REVIEW_INTERVAL_DAYS` 归此服务（A 阶段参数化）。

- [ ] **Step 1: 实现 CardService**

```java
package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.ApiViews.CardView;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 卡片域服务:编辑/采纳/补充输入/重生成/自评与复习队列。 */
@Service
public class CardService {

    /** 掌握度 0-5 对应的复习间隔(天);A 阶段将改为按用户设置读取。 */
    static final int[] REVIEW_INTERVAL_DAYS = {0, 1, 3, 7, 15, 30};

    private final KnowledgeCardRepository cards;
    private final ProjectService projectService;
    private final GenerateService generate;

    public CardService(
            KnowledgeCardRepository cards,
            ProjectService projectService,
            GenerateService generate) {
        this.cards = cards;
        this.projectService = projectService;
        this.generate = generate;
    }

    /** 编辑卡片定稿:标记 edited,重新生成不再覆盖。 */
    public CardView editCard(Long id, Long cardId, String content) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        card.setContent(content);
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 采纳 AI 草稿为定稿(标记为编辑过,防止下次重新生成覆盖)。 */
    public CardView adoptDraft(Long id, Long cardId) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        if (card.getAiDraft() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂无 AI 草稿");
        }
        card.setContent(card.getAiDraft());
        card.setEdited(true);
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 用户补充输入(如运行指南的踩坑记录)。 */
    public CardView addUserInput(Long id, Long cardId, String input) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        String existing = card.getUserInput() == null ? "" : card.getUserInput();
        String merged = (existing + "\n" + (input == null ? "" : input)).trim();
        card.setUserInput(merged);
        if (!card.isEdited()) {
            card.setEdited(true);
        }
        card.setUpdatedAt(Instant.now());
        return CardView.from(cards.save(card));
    }

    /** 重新生成单卡:归属校验后委托生成服务(已编辑卡片只更新草稿)。 */
    public CardView regenerateCard(Long id, Long cardId) {
        requireOwnedCard(id, cardId);
        return CardView.from(generate.regenerateCard(cardId));
    }

    /** 自评更新掌握度与下次复习时间:掌握 → mastery+1 且间隔递增;生疏 → mastery-2 且立即复习。 */
    public CardView reviewCard(Long id, Long cardId, boolean known) {
        KnowledgeCard card = requireOwnedCard(id, cardId);
        Instant now = Instant.now();
        if (known) {
            int mastery = Math.min(5, card.getMastery() + 1);
            card.setMastery(mastery);
            card.setNextReview(now.plus(Duration.ofDays(REVIEW_INTERVAL_DAYS[mastery])));
        } else {
            card.setMastery(Math.max(0, card.getMastery() - 2));
            card.setNextReview(now.plusSeconds(60));
        }
        card.setLastReviewed(now);
        card.setUpdatedAt(now);
        return CardView.from(cards.save(card));
    }

    /** 待复习队列:mastery==0 或到期的卡片,按下次复习时间升序,最多 20 条。 */
    public List<CardView> reviewQueue(Long id) {
        projectService.requireProject(id);
        Instant now = Instant.now();
        return cards.findByProjectIdOrderByIdAsc(id).stream()
                .filter(c -> c.getMastery() == 0
                        || (c.getNextReview() != null && !c.getNextReview().isAfter(now)))
                .sorted(Comparator.comparing(
                        c -> c.getNextReview() == null ? Instant.EPOCH : c.getNextReview()))
                .limit(20)
                .map(CardView::from)
                .toList();
    }

    private KnowledgeCard requireOwnedCard(Long id, Long cardId) {
        projectService.requireProject(id);
        KnowledgeCard card = cards.findById(cardId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "卡片不存在: " + cardId));
        if (!card.getProjectId().equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "卡片不属于该项目");
        }
        return card;
    }
}
```

- [ ] **Step 2: 控制器卡片域端点改委托**

构造器追加注入 `CardService`；六个端点（`editCard/adoptDraft/regenerateCard/addUserInput/reviewCard/reviewQueue`）方法体替换为委托（从 record 取参），例如：

```java
    @PutMapping("/{id}/cards/{cardId}/content")
    public CardView editCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody EditContentRequest req) {
        return cardService.editCard(id, cardId, req.content());
    }

    @PostMapping("/{id}/cards/{cardId}/review")
    public CardView reviewCard(@PathVariable Long id, @PathVariable Long cardId, @RequestBody ReviewRequest req) {
        return cardService.reviewCard(id, cardId, req.known());
    }
```

删除控制器中 `REVIEW_INTERVAL_DAYS`、`requireCard`、`toCardView` 与不再使用的 import（Duration/Comparator 等）。`EditContentRequest/UserInputRequest/ReviewRequest` record 留在控制器。

- [ ] **Step 3: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS（重点：reviewCard_updatesMasteryAndQueue、editCard_marksEditedAndPersists）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/project/CardService.java backend/src/main/java/com/codeoffer/backend/project/ProjectController.java
git commit -m "refactor(backend): 抽取 CardService,复习策略与卡片归属校验收敛到服务层"
```

---

### Task 4: ResumeService 抽取 + 控制器收尾

**Files:**
- Create: `backend/src/main/java/com/codeoffer/backend/project/ResumeService.java`
- Modify: `backend/src/main/java/com/codeoffer/backend/project/ProjectController.java`

**Interfaces:**
- Consumes: `ProjectService.requireProject(Long)`、`ApiViews.ResumeView.from(ResumeEntry)`、`GenerateService.regenerateResume(Long projectId)`（现有签名）。
- Produces: `ResumeView editStar(Long id, String starContent)`、`ResumeView regenerate(Long id)`。控制器最终形态：仅注入三个 service，~110 行。

- [ ] **Step 1: 实现 ResumeService**

```java
package com.codeoffer.backend.project;

import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.common.ApiException;
import com.codeoffer.backend.project.ApiViews.ResumeView;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 简历域服务:STAR 编辑;重生成委托 GenerateService(已编辑字段不被覆盖)。 */
@Service
public class ResumeService {

    private final ResumeEntryRepository resumes;
    private final ProjectService projectService;
    private final GenerateService generate;

    public ResumeService(
            ResumeEntryRepository resumes,
            ProjectService projectService,
            GenerateService generate) {
        this.resumes = resumes;
        this.projectService = projectService;
        this.generate = generate;
    }

    public ResumeView editStar(Long id, String starContent) {
        projectService.requireProject(id);
        ResumeEntry entry = resumes.findByProjectId(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "尚无简历产出"));
        if (starContent == null || starContent.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        entry.setStarContent(starContent);
        entry.setEdited(true);
        entry.setUpdatedAt(Instant.now());
        return ResumeView.from(resumes.save(entry));
    }

    public ResumeView regenerate(Long id) {
        projectService.requireProject(id);
        return ResumeView.from(generate.regenerateResume(id));
    }
}
```

- [ ] **Step 2: 控制器收尾**

构造器改为只注入 `ProjectService / CardService / ResumeService`（删除四个 Repository 与 GenerateService 注入）；`editResume/regenerateResume` 改委托：

```java
    @PutMapping("/{id}/resume/star")
    public ResumeView editResume(@PathVariable Long id, @RequestBody EditStarRequest req) {
        return resumeService.editStar(id, req.starContent());
    }

    @PostMapping("/{id}/resume/regenerate")
    public ResumeView regenerateResume(@PathVariable Long id) {
        return resumeService.regenerate(id);
    }
```

删除 `toResumeView` 与全部残留 import（Repository/GenTask/Instant/HttpStatus/ResponseStatusException/Transactional 等）。控制器不再有任何业务逻辑。

- [ ] **Step 3: 运行全部测试 + 行数检查**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS。
Run: `wc -l src/main/java/com/codeoffer/backend/project/ProjectController.java`
Expected: ≤ 180 行（目标 ~110）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/project/ResumeService.java backend/src/main/java/com/codeoffer/backend/project/ProjectController.java
git commit -m "refactor(backend): 抽取 ResumeService,控制器完成瘦身仅做路由委托"
```

---

### Task 5: 体检报告接线

**Files:**
- Modify: `backend/src/main/java/com/codeoffer/backend/ai/GenerateService.java`
- Test: `backend/src/test/java/com/codeoffer/backend/ai/HealthContentTest.java`
- Test: `backend/src/test/java/com/codeoffer/backend/HealthReportIntegrationTest.java`

**Interfaces:**
- Consumes: `AiServiceClient.healthReport(String repoUrl, Map<String,Object> meta)`（现有方法，此前从未被调用）。
- Produces: `GenerateService.buildHealthContent(Map<String,Object>)`（包级静态，供单测）；生成完成后 `project.healthReport` 为 Markdown 或 null（失败降级）。

- [ ] **Step 1: 写转换单测（失败）**

```java
package com.codeoffer.backend.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthContentTest {

    @Test
    void nullResponse_returnsNull() {
        assertNull(GenerateService.buildHealthContent(null));
    }

    @Test
    void fullResponse_rendersAllSections() {
        String md = GenerateService.buildHealthContent(Map.of(
                "verdict", "值得学",
                "score", 8,
                "reasons", List.of("文档齐全", "架构清晰"),
                "learning_path", List.of("读 README", "跑通示例"),
                "risk", "依赖较旧"));
        assertTrue(md.startsWith("# 项目体检报告"));
        assertTrue(md.contains("**结论**：值得学（评分 8/10）"));
        assertTrue(md.contains("- 文档齐全"));
        assertTrue(md.contains("1. 读 README"));
        assertTrue(md.contains("依赖较旧"));
    }

    @Test
    void missingFields_degradesWithoutError() {
        String md = GenerateService.buildHealthContent(Map.of("verdict", "值得浅尝"));
        assertTrue(md.contains("**结论**：值得浅尝"));
        assertTrue(md.contains("## 评估理由"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw.cmd test -Dtest=HealthContentTest -q`
Expected: 编译失败 — `buildHealthContent` 不存在。

- [ ] **Step 3: 实现 buildHealthContent 并接线流水线**

GenerateService 中：
1. `transition(project, task, ProjectStatus.ANALYZING, 25)` 的 25 改为 **20**。
2. 在步骤 2 的 `saveProject(project);` 之后、`transition(... GENERATING, 50)` 之前插入：

```java
            // 2.5) 体检报告(增值产物,失败不阻断主流程)
            try {
                Map<String, Object> healthResp = ai.healthReport(project.getRepoUrl(), repoInfo);
                project.setHealthReport(buildHealthContent(healthResp));
                saveProject(project);
            } catch (Exception e) {
                log.warn("项目 {} 体检报告生成失败(不阻断主流程): {}", projectId, e.getMessage());
            }
            task.setProgress(35);
            saveTask(task);
```

3. 在 `buildFlowContent` 旁新增（包级静态，供单测）：

```java
    /** 体检报告 JSON → Markdown;resp 为 null 时返回 null,字段缺失按空串降级。 */
    static String buildHealthContent(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        String verdict = str(resp.get("verdict"));
        String score = str(resp.get("score"));
        StringBuilder sb = new StringBuilder("# 项目体检报告\n\n");
        sb.append("**结论**：").append(verdict.isEmpty() ? "未知" : verdict);
        if (!score.isEmpty()) {
            sb.append("（评分 ").append(score).append("/10）");
        }
        sb.append("\n\n## 评估理由\n");
        appendItems(sb, resp.get("reasons"), "- ");
        sb.append("\n## 学习路线\n");
        appendItems(sb, resp.get("learning_path"), "1. ");
        sb.append("\n## 潜在风险\n").append(str(resp.get("risk")));
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void appendItems(StringBuilder sb, Object raw, String prefix) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                sb.append(prefix).append(str(item)).append('\n');
            }
        }
    }
```

注意：现有测试未 stub `healthReport`，Mockito 默认返回 null → `buildHealthContent(null)` 返回 null → 现有断言不受影响。

- [ ] **Step 4: 运行单测确认通过**

Run: `./mvnw.cmd test -Dtest=HealthContentTest -q`
Expected: 3 个 PASS。

- [ ] **Step 5: 写集成测试**

```java
package com.codeoffer.backend;

import com.codeoffer.backend.ai.AiServiceClient;
import com.codeoffer.backend.ai.GenerateService;
import com.codeoffer.backend.project.GenTask;
import com.codeoffer.backend.project.GenTask.TaskStatus;
import com.codeoffer.backend.project.GenTaskRepository;
import com.codeoffer.backend.project.Project;
import com.codeoffer.backend.project.ProjectRepository;
import com.codeoffer.backend.project.ProjectStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 体检报告接线:成功写入 Markdown;失败降级不阻断主流程。 */
@SpringBootTest
@ActiveProfiles("test")
class HealthReportIntegrationTest {

    @Autowired private ProjectRepository projects;
    @Autowired private GenTaskRepository tasks;
    @Autowired private GenerateService generate;
    @MockitoBean private AiServiceClient ai;

    private void stubAi() {
        when(ai.cloneRepo(anyString())).thenReturn("C:/fake/repo");
        when(ai.analyze(anyString())).thenReturn(
                Map.of("name", "fake", "languages", List.of(), "core_files", List.of(),
                        "stats", Map.of("file_count", 1), "tree", List.of()));
        when(ai.knowledgeCards(any(), anyList())).thenReturn(Map.of("cards", List.of(
                Map.of("type", "architecture", "title", "架构", "content", "内容1"))));
        when(ai.flowDiagram(any())).thenReturn(Map.of("title", "flow", "description", "d", "mermaid", "graph LR"));
        when(ai.resume(any())).thenReturn(
                Map.of("star", Map.of("situation", "s", "task", "t", "action", "a", "result", "r", "summary", "s")));
        when(ai.interviewQuestions(any())).thenReturn(
                Map.of("questions", List.of(Map.of("level", "base", "question", "q", "answer", "a"))));
    }

    private long runGeneration() throws InterruptedException {
        Project p = new Project();
        p.setRepoUrl("https://github.com/octocat/health-" + System.nanoTime());
        p.setName("health");
        p = projects.save(p);
        GenTask task = new GenTask();
        task.setProjectId(p.getId());
        task = tasks.save(task);
        generate.runFullGeneration(p.getId(), task.getId());
        for (int i = 0; i < 100; i++) {
            GenTask t = tasks.findFirstByProjectIdOrderByIdDesc(p.getId()).orElseThrow();
            if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }
        return p.getId();
    }

    @Test
    void healthReport_storedAsMarkdown() throws Exception {
        stubAi();
        when(ai.healthReport(anyString(), any())).thenReturn(Map.of(
                "verdict", "值得学", "score", 8,
                "reasons", List.of("文档齐全"), "learning_path", List.of("读 README"), "risk", "依赖较旧"));

        long projectId = runGeneration();

        Project updated = projects.findById(projectId).orElseThrow();
        assertEquals(ProjectStatus.REVIEW, updated.getStatus());
        assertTrue(updated.getHealthReport().startsWith("# 项目体检报告"));
        assertTrue(updated.getHealthReport().contains("值得学"));
    }

    @Test
    void healthReportFailure_doesNotBlockGeneration() throws Exception {
        stubAi();
        when(ai.healthReport(anyString(), any()))
                .thenThrow(new IllegalStateException("AI 服务调用失败(HTTP 502)"));

        long projectId = runGeneration();

        Project updated = projects.findById(projectId).orElseThrow();
        assertEquals(ProjectStatus.REVIEW, updated.getStatus());
        assertNull(updated.getHealthReport());
    }
}
```

- [ ] **Step 6: 运行全部测试**

Run: `./mvnw.cmd test -q`
Expected: 全部 PASS（含现有 3 个测试类零改动）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/ai/GenerateService.java backend/src/test/java/com/codeoffer/backend/ai/HealthContentTest.java backend/src/test/java/com/codeoffer/backend/HealthReportIntegrationTest.java
git commit -m "feat(backend): 接通体检报告生成流水线,失败降级不阻断主流程"
```

---

### Task 6: 删除死枚举 + 全量自检

**Files:**
- Modify: `backend/src/main/java/com/codeoffer/backend/project/CardType.java`

**Interfaces:**
- Consumes: 无。
- Produces: CardType 仅余 6 个常量（B 阶段导航按这 6 类构建）。

- [ ] **Step 1: 删除 COMPARE_PRACTICE**

CardType.java 删除 `COMPARE_PRACTICE("对比练习");` 一行（注意把上一行 `FLOW_DIAGRAM("核心流程图"),` 的逗号改为分号）。

- [ ] **Step 2: 全仓残留检查**

Run: `cd "D:/Internship Program/CodeOffer" && git grep -n -i "COMPARE_PRACTICE\|compare_practice\|对比练习" -- backend frontend ai-service`
Expected: 无输出（docs 中的历史文档允许存在）。若有代码残留，一并清除。

- [ ] **Step 3: 全量测试**

Run: `cd backend && ./mvnw.cmd test -q`
Expected: 全部 PASS。
Run: `cd ../ai-service && .venv/Scripts/python -m pytest -q`（若 .venv 不存在则先按 README §3.4 创建）
Expected: 27 个 PASS（C 未改 ai-service，验证基线未被波及）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/codeoffer/backend/project/CardType.java
git commit -m "refactor(backend): 删除从未生成的 COMPARE_PRACTICE 死枚举"
```

---

## 验收（对照 spec §八 C 条）

- [ ] 现有 3 个测试类文件零改动且全绿（`git diff a995509 -- backend/src/test/java/com/codeoffer/backend/ProjectControllerTest.java backend/src/test/java/com/codeoffer/backend/GenerateFlowIntegrationTest.java backend/src/test/java/com/codeoffer/backend/BackendApplicationTests.java` 为空）
- [ ] 409/400/404 响应体含中文 `detail`（ErrorContractTest 证明）
- [ ] 体检报告生成后非 null 且为 Markdown（HealthReportIntegrationTest 证明）
- [ ] COMPARE_PRACTICE 无代码残留（git grep 证明）
- [ ] ProjectController ≤ 180 行且无 Repository 注入