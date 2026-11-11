# CodeOffer 多用户化、设置子系统与学习页重构设计规格

- 日期：2026-11-11
- 状态：已获用户批准（对话中分节确认）
- 范围：四个工作流，按 **C → D → A → B** 顺序实现
- 约束：不改变现有技术栈（Java 21 / Spring Boot 4.1 / React 19 / FastAPI / MySQL 8）；新增依赖仅 `spring-boot-starter-security`（同栈）

---

## 一、背景与目标

CodeOffer 目前是单用户本地工具：无登录、无设置界面、AI 配置固化在 `config/.env`、学习页单列无导航。本次改造目标：

1. **C 后端分层重构**：抽取 service 层、统一错误契约、补事务边界；顺带修复三个已证实的缺陷（见 §2.2）。
2. **D 登录与多用户**：注册/登录 + 图形验证码，全部业务数据按用户隔离，BYOK（每用户自带 LLM Key）。
3. **A 设置子系统**：多套 LLM 档案一键切换 + 连接测试、学习/复习参数、生成偏好、数据管理、账号安全、外观主题。
4. **B 学习页重构**：左侧目录 + 子路由分页，URL 可直达各类内容，目录带掌握度徽标。

## 二、现状与已证实的问题

### 2.1 关键现状（证据）

- LLM 唯一调用点 `ai-service/llm/gateway.py`：OpenAI 兼容端点，`chat_json(messages, *, temperature, transport, cfg)` **已支持传入 cfg**；配置来自 env（`LLM_BASE_URL/API_KEY/MODEL/VERIFY_SSL`），`LLM_PROVIDER` 不被代码读取。
- Java 不直连 LLM：`AiServiceClient`（RestClient，HTTP/1.1 强制，读超时 220s）→ FastAPI。
- `ProjectController` 415 行 / 18 端点直连 4 个 Repository，仅 1 处 `@Transactional`，无全局异常处理。
- 前端仅 2 条路由（`/`、`/projects/:id`），无状态管理库；`client.ts` 错误处理读响应体 `detail` 字段。
- 复习间隔硬编码 `ProjectController.REVIEW_INTERVAL_DAYS = {0,1,3,7,15,30}`；生疏 → mastery-2、60 秒后再复习。
- 数据库 4 表：project / gen_task / knowledge_card / resume_entry，`ddl-auto: update`。
- 测试：后端 JUnit5 + H2（3 个测试类）、ai-service pytest（27 用例）、前端仅 tsc + oxlint。

### 2.2 已证实的缺陷（本次修复）

| # | 缺陷 | 证据 | 修复归属 |
| --- | --- | --- | --- |
| 1 | 体检报告是死功能：端点/prompt 齐全但流水线从未调用，`project.healthReport` 恒 null，前端不渲染 | `GenerateService.doRunFullGeneration` 无 healthReport 调用 | C（接线）+ B（渲染） |
| 2 | 后端错误消息到不了前端：Spring 默认错误体无 `detail` 且 `include-message` 默认关闭，用户只见「HTTP 409」 | `client.ts:33` 读 `body.detail`；`application.yml` 无 error 配置 | C（统一错误契约） |
| 3 | `COMPARE_PRACTICE` 死枚举：ai-service 无任何支持，从未生成 | `CardType.java:13`；ai-service 全文无 compare_practice | C（删除） |

## 三、总体架构

```
React SPA ──(会话 Cookie + XSRF 头)──> Spring Boot(:8080)
                                          │  业务 DB(MySQL, 全部按 user_id 隔离)
                                          │  LLM 档案(AES-GCM 加密) / 设置(KV)
                                          └──(HTTP, 请求体携带 llm + prefs)──> FastAPI(:8001)
                                                                                │  无状态, 不碰业务 DB
                                                                                └──> 用户自选的 OpenAI 兼容端点
```

- **单一事实源**：所有设置存 MySQL；Java 在每次 AI 请求体注入 `llm`（解密后的档案）与 `prefs`（生成偏好）。AI 服务无状态，重启不丢配置。
- **职责边界不变**：AI 服务只处理「仓库 + 大模型」，不读业务 DB。
- **env 回退废除**（多用户下回退等于把服务器主人的 Key 借给所有用户）：无激活档案时，创建/重生成端点直接返回 409「请先在设置中配置 AI 提供商」。`config/.env` 的 `LLM_*` 仅保留给 `import-env` 便利建档。

---

## 四、工作流 C：后端分层重构（行为保持 + 缺陷修复）

### 4.1 目标包结构

```
com.codeoffer.backend/
├── common/
│   ├── ApiException.java            # RuntimeException + HttpStatus + 中文消息
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice
├── project/
│   ├── ProjectController.java       # 瘦身:路由 + 参数接收,目标 ≤180 行
│   ├── ProjectService.java          # 录入/列表/详情/确认/删除/整仓重生成 + URL 校验
│   ├── CardService.java             # 编辑/采纳/补充输入/自评/复习队列
│   ├── ResumeService.java           # STAR 编辑(重生成委托 GenerateService)
│   └── (entity / repository / ApiViews / CardType 保留)
├── ai/                              # GenerateService / AiServiceClient / GenerationWriter
└── config/                          # 现有配置保留
```

### 4.2 统一错误契约

所有错误响应体为 `{"detail": "<中文消息>"}`（与前端 `client.ts` 现有读取逻辑对齐，前端零改动修复缺陷 2）：

- `ApiException` / `ResponseStatusException` → 原状态码 + reason；
- `MethodArgumentNotValidException` / `HttpMessageNotReadableException` → 400 + 首条字段消息；
- `AccessDeniedException` → 403「无权访问」（D 阶段启用）；
- 兜底 `Exception` → 500「服务器内部错误,请查看后端日志」，**不回显堆栈**，日志记录完整异常。

### 4.3 事务与行为

- `ProjectService.delete`（四表级联删）加 `@Transactional`；**create 不得包事务**——`@Async` 生成线程在 READ_COMMITTED 下看不到未提交行，包事务会使生成任务被当作「已删除」跳过；create 的两次 save 依赖 JPA 各自原子性（与现状一致）。单条 save 不滥加事务。
- 现有全部端点路径、请求/响应结构、状态机流转**不变**；现有 3 个测试类必须原样通过。
- `REVIEW_INTERVAL_DAYS` 与生疏策略移入 `CardService`（C 阶段仍用默认常量，A 阶段参数化）。
- 删除 `CardType.COMPARE_PRACTICE`。

### 4.4 体检报告接线（缺陷 1）

- `doRunFullGeneration` 在解析完成后调用 `ai.healthReport(repoUrl, repoInfo)`（meta 直接传 repoInfo）。
- 新增 `buildHealthContent(Map)`：JSON → Markdown，规则固定：

```
# 项目体检报告

**结论**：{verdict}（评分 {score}/10）

## 评估理由
- {reasons 逐项}

## 学习路线
1. {learning_path 逐项}

## 潜在风险
{risk}
```

- 写入 `project.healthReport`；字段缺失时按空串降级，不抛错。
- 进度重排：CLONING 5 → ANALYZING 20 →（解析 + 体检报告完成）35 → GENERATING 50 → 卡片 70 → 流程图 80 → 简历/面试题 90 → REVIEW 100。
- 体检报告失败不阻断主流程：捕获异常记日志，healthReport 置 null，继续生成（体检是增值产物，卡片才是核心）。

### 4.5 测试

- 现有测试原样通过；新增：错误契约测试（409/400/404 响应体含 `detail` 中文消息）、体检报告 Markdown 转换单测、service 层关键路径（重复录入 409、状态机约束）。

---

## 五、工作流 D：登录与多用户

### 5.1 用户表与账号规则

`app_user`：id / username（唯一，`^[A-Za-z0-9_]{3,32}$`）/ password_hash（BCrypt）/ role（`ADMIN`|`USER`）/ enabled（boolean）/ created_at / updated_at。

- 密码 8–72 字节（按 UTF-8 编码计，BCrypt 72 字节上限；中文密码按实际字节校验）。
- **首个注册用户 = ADMIN**，并在同一事务内把当时已存在的业务表（D 阶段为 project / gen_task / knowledge_card / resume_entry 四表；A 阶段新表建表即带 user_id，无需回填）中 `user_id IS NULL` 的存量行全部回填给该用户（继承存量数据）；后续用户从空数据开始。
- 管理员可禁用/启用用户；**不能禁用自己**（由此保证至少一个管理员存活）。被禁用用户：登录拒绝（403「账号已被禁用」），其活跃会话立即失效。

### 5.2 认证端点（公开）

| 方法 路径 | 请求 | 行为 |
| --- | --- | --- |
| `GET /api/auth/captcha` | — | 返回 `image/png`；答案与过期时间存 Session；每次调用生成新码 |
| `POST /api/auth/register` | `{username, password, captcha}` | 校验验证码 → 建用户 → 自动登录；首用户触发 ADMIN + 存量回填 |
| `POST /api/auth/login` | `{username, password, captcha}` | 校验验证码 → 认证 → 建会话 |
| `GET /api/auth/me` | — | 已登录返回 `{id, username, role}`；未登录 401 |
| `POST /api/auth/logout` | — | 会话失效 |
| `PUT /api/auth/password` | `{oldPassword, newPassword}` | 需会话；旧密码错误 400；成功后**其它会话全部失效**（SessionRegistry），当前会话保留 |

错误消息统一：凭据错误 401「用户名或密码错误」（不区分用户不存在/密码错）；验证码错误/过期 400「验证码错误或已过期」；用户名已存在 409「用户名已被注册」。

### 5.3 图形验证码

- Java2D 自绘（零新依赖）：4 位随机字母数字（剔除 `0O1lI`），120×40 PNG，干扰线 + 噪点。
- Session 存 `captchaCode` + `captchaExpiresAt`；**5 分钟过期、一次性**（无论校验成败立即失效）、忽略大小写。
- 注册与登录都必须携带 captcha 字段。

### 5.4 会话与安全配置

- 引入 `spring-boot-starter-security`。Session Cookie 认证：HttpOnly、SameSite=Lax，HTTPS 下 Secure。
- CSRF：`CookieCsrfTokenRepository.withHttpOnlyFalse()`；前端对写请求带 `X-XSRF-TOKEN` 头（读 `XSRF-TOKEN` cookie）。
- 放行：`/api/auth/captcha|login|register`、`/api/health`、静态资源与 SPA 前端路由；其余 `/api/**` 需认证。
- 401/403 响应体同样走 `{"detail": ...}` 契约（AuthenticationEntryPoint / AccessDeniedHandler）。
- 注册 `HttpSessionEventPublisher` + `SessionRegistry`，支撑改密/禁用时的会话失效。
- **限流**：`/api/auth/**` 按 IP 内存滑窗 30 次/分钟，超限 429「请求过于频繁,请稍后再试」。单实例实现；多实例需 Redis（部署扩展点，不在本次范围）。

### 5.5 数据隔离

- 六表加 `user_id`（可空列，`ddl-auto: update` 自动加；首用户注册时回填）：project / gen_task / knowledge_card / resume_entry（D 阶段）+ llm_profile / app_setting（A 阶段建表即带）。
- Repository 全部改为按 userId 作用域查询（如 `findByIdAndUserId`）；service 从 SecurityContext 取当前用户。
- 跨用户访问一律 **404**（不暴露资源存在性）。
- **异步陷阱**：`@Async` 线程无 SecurityContext——`runFullGeneration(projectId, taskId, userId)` 显式透传 userId；`regenerateCard/regenerateResume` 在请求线程内先校验归属再执行。
- 列表/详情/复习队列/掌握度聚合全部只统计当前用户数据。

### 5.6 管理端点（仅 ADMIN）

| 方法 路径 | 行为 |
| --- | --- |
| `GET /api/admin/users` | 用户列表 `[{id, username, role, enabled, createdAt}]` |
| `PUT /api/admin/users/{id}/status` | `{enabled}`；禁用自己 → 400；禁用时同步失效该用户会话 |

### 5.7 前端

- **`/login` 页**（独立于 AppLayout）：登录/注册双 Tab；用户名 + 密码 + 验证码输入；验证码图片点击刷新；错误走 `detail` 契约展示。
- **AuthContext**：App 启动 `GET /api/auth/me`；`RequireAuth` 包装业务路由，未登录重定向 `/login`（携带来源路径，登录后跳回）。
- **client.ts**：写请求自动带 `X-XSRF-TOKEN`；收到 401（认证端点除外）统一跳 `/login`。
- **AppLayout**（全局导航壳）：顶栏品牌（回首页）+ 菜单「项目 / 设置」+ 右侧用户下拉（用户名、角色 Tag、退出登录）。

### 5.8 测试

- H2 集成测试：注册/登录/验证码一次性与过期、首用户 ADMIN + 存量回填、双用户数据隔离（跨用户 404）、禁用后会话失效、改密后其它会话失效、限流 429、CSRF 缺失 403。
- 现有测试适配：测试上下文注入认证（`@WithMockUser` 或测试专用 SecurityFilterChain），业务断言不变。

---

## 六、工作流 A：设置子系统

### 6.1 数据模型（均按 user_id 隔离）

**`llm_profile`**：id / user_id / name（1–50 字符）/ provider（`deepseek|qwen|zhipu|vllm|custom`）/ base_url（`^https?://`）/ api_key_enc（AES-GCM 密文）/ model（1–100 字符）/ verify_ssl / created_at / updated_at。

**`app_setting`**：id / user_id / setting_key / value（TEXT，JSON）/ updated_at；唯一约束 `(user_id, setting_key)`。键：`active_profile_id`、`review_params`、`generation_prefs`。

**默认值**（未设置时）：
- review：`{intervalDays:[0,1,3,7,15,30], unknownPenalty:2, unknownDelaySeconds:60}`
- generation：`{language:"zh", verbosity:"standard"}`

**参数校验**：intervalDays 为 6 个 0–365 整数且非递减；unknownPenalty 0–5；unknownDelaySeconds 0–86400；language ∈ {zh, en}；verbosity ∈ {concise, standard, detailed}。复习时实时读库生效。

### 6.2 Key 加密（静态加密）

- AES/GCM/NoPadding，12 字节随机 IV，128 位 tag；存储格式 `v1:` + Base64(IV‖密文)。
- 主密钥 `LLM_MASTER_KEY`（Base64 编码 32 字节）来自 `config/.env` / 容器 env；**缺失或非法 → 启动快速失败**（沿用 MYSQL_PASSWORD 先例）。
- 解密仅发生在：构建 AI 请求、连接测试、脱敏展示。日志严禁打印 key 与含 key 的请求体。
- 脱敏规则：明文长度 >8 → 前 3 位 + `****` + 后 4 位；否则 `****`。API **永不返回完整 key**；更新时 key 传空 = 保留原值。

### 6.3 设置 API（`/api/settings`，全部当前用户作用域）

| 方法 路径 | 说明 |
| --- | --- |
| `GET /llm-profiles` | 档案列表（key 脱敏，含 active 标记） |
| `POST /llm-profiles` | 新建 |
| `PUT /llm-profiles/{id}` | 编辑（key 空 = 保留） |
| `DELETE /llm-profiles/{id}` | 删除；激活中 → 409「请先切换激活档案再删除」 |
| `POST /llm-profiles/{id}/activate` | 一键激活 |
| `POST /llm-profiles/{id}/test` | 连接测试（经 AI 服务，Java 读超时 30s）→ `{ok, latencyMs, model, error}` |
| `POST /llm-profiles/import-env` | 从服务器 `config/.env` 的 LLM_* 为**当前用户**建档；env 未配置 → 400 |
| `GET /` | 聚合 `{activeProfileId, review, generation}` |
| `PUT /` | 按分类局部更新 `{activeProfileId?, review?, generation?}` |
| `GET /backup` | 当前用户全部项目 + 卡片 + 简历导出 JSON（不含任务）：`{exportedAt, username, projects:[...]}` |
| `GET /storage-info` | **仅 ADMIN**：克隆缓存 `{sizeBytes, repoCount}`（经 AI 服务） |
| `POST /clear-repo-cache` | **仅 ADMIN**：清理克隆缓存（经 AI 服务）；不影响已生成产物，重生成时自动重新克隆 |

### 6.4 运行时注入链路

- `SettingsService.runtimeFor(userId)` → `LlmRuntime` record（baseUrl / apiKey 明文 / model / verifySsl / language / verbosity）；无激活档案抛 ApiException(409)。
- `ProjectService.create/regenerate`、`CardService.regenerateCard`、`ResumeService.regenerateResume` 在**请求线程内预检**档案存在（409 友好提示），随后 `GenerateService` 在异步开始时**快照一次** LlmRuntime（生成中途切换档案不影响本次任务），全程透传。
- `AiServiceClient` 五个生成方法签名追加 `LlmRuntime`，请求体追加：

```json
{"llm": {"base_url": "...", "api_key": "...", "model": "...", "verify_ssl": true},
 "prefs": {"language": "zh", "verbosity": "standard"}}
```

### 6.5 ai-service 改造

- `schemas.py`：五个生成请求加可选 `llm: LlmOverride | None`、`prefs: GenerationPrefs | None`。
- `LLMConfig.from_values(base_url, api_key, model, verify_ssl)`；`configured` 改为仅要求 base_url 非空；**key 为空时跳过 Authorization 头**（本地 vLLM 无鉴权场景）。
- 请求带 `llm` → 用覆盖配置；不带 → 沿用 env（兼容旧契约与 pytest）。
- `prefs` 注入：prompt 构建器追加 system 指令——language：zh「所有自然语言输出使用简体中文。」/ en「All natural-language output in English.」（含 Mermaid label）；verbosity：concise「内容精简,直击要点,篇幅约为标准的一半。」/ detailed「内容详尽,展开技术细节与示例,篇幅约为标准的 1.5 倍。」/ standard 不追加。
- 新端点：
  - `POST /api/ai/test-connection`：入参 `{llm}`；最小 chat 请求（max_tokens 16、timeout 15s、**不重试**）→ `{ok, latency_ms, model, error}`；
  - `GET /api/repos/cache-info`：遍历 REPO_DATA_DIR → `{size_bytes, repo_count}`；
  - `POST /api/repos/clear-cache`：仅删除 REPO_DATA_DIR 直接子项（resolve 后校验 `is_relative_to` 根目录）→ `{ok, removed}`。

### 6.6 前端设置页（`/settings`，AppLayout 内）

左侧菜单分区（管理员多一个「用户管理」）：

1. **AI 模型**：档案卡片列表（激活徽标 / provider Tag / 模型 / 脱敏 key）；新建/编辑弹窗含预设下拉，一键填充：
   | 预设 | base_url | 默认模型 |
   | --- | --- | --- |
   | deepseek | `https://api.deepseek.com/v1` | `deepseek-chat` |
   | qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
   | zhipu | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
   | vllm | `http://localhost:8000/v1` | 必填（用户自填） |
   | custom | 空 | 空 |
   每档案「测试连接」按钮（显示延迟/模型回显/错误）与「激活」按钮；「从 .env 导入」便利按钮；无档案时顶部横幅引导。
2. **学习/复习**：6 级间隔天数、生疏扣减、生疏延迟表单（带校验与默认值恢复）。
3. **生成偏好**：语言（中文/English）、篇幅（精简/标准/详细）。
4. **数据管理**：备份下载（`codeoffer-backup-YYYYMMDD.json`）；管理员可见缓存占用与清理（Popconfirm）。
5. **账号安全**：修改密码（旧密码 + 新密码 + 确认）。
6. **外观**：亮/暗/跟随系统；存 localStorage `codeoffer-theme`，antd `ConfigProvider` 主题算法切换，system 监听 `matchMedia`。**主题不入数据库**（设备相关偏好）。
7. **用户管理**（仅 ADMIN）：用户列表 + 禁用/启用开关（自己行禁用）。

### 6.7 测试

- 后端 H2：档案 CRUD/激活/禁删、key 脱敏与空值保留、加解密往返、参数校验边界、无档案 409、backup 内容、非 ADMIN 访问 storage-info 403。
- pytest：llm 覆盖生效、空 key 跳头、prefs 注入、test-connection 成功/失败、cache-info/clear-cache 安全边界（越界路径不删）。
- 前端：浏览器实测五个分区黄金路径 + 测试连接失败态。

---

## 七、工作流 B：学习页子路由重构

### 7.1 路由

```
/projects/:id            → ProjectLayout
  ├── index              → <Navigate to="overview" replace>
  ├── overview           → OverviewSection
  ├── cards/:type        → CardSection(6 类:architecture/difficulty/highlight/
  │                         takeaway/run_guide/flow_diagram)
  ├── resume             → ResumeStarSection
  └── interview          → InterviewSection
```

### 7.2 ProjectLayout

- 现有 detail + task 轮询（3s、seq 乱序守卫、DONE/FAILED 停轮询）上移至 Layout；子路由经 `useOutletContext` 取 `{detail, task, refresh, projectId}`，**不引入状态管理库**。
- 头部：返回、项目名、状态 Tag、导出 Markdown、复制简历、整仓重生成、开始自测（QuizMode Modal 保留在 Layout）。
- 全局横幅：RUNNING 进度条 / FAILED 原因，任何子页面可见。
- 左侧 antd Menu（`Layout.Sider`，窄屏折叠）：总览 / 6 类卡片 / STAR 简历 / 模拟面试题；当前路由高亮。
- **掌握度徽标**（按 type 聚合 detail.cards，优先级从上到下）：mastery ≥3 → 绿色对勾；否则 mastery==0 或 nextReview 到期 → 橙色时钟；其余无徽标。

### 7.3 内容区

- **OverviewSection**：项目信息 Descriptions + 体检报告 Markdown 渲染（C 已转 MD）+ REVIEW 状态确认入库卡片。
- **CardSection**：按 `:type` 过滤卡片，复用 CardList（编辑/采纳/重生成/踩坑记录全保留）；非法或未生成 type → Empty 空态。
- **ResumeStarSection / InterviewSection**：现 ResumeSection 一拆为二；重生成接口一次出两者，两视图共用按钮与 refresh。

### 7.4 边界情况

| 场景 | 行为 |
| --- | --- |
| 项目不存在 / ID 非法 / 跨用户 | Layout 级错误页 + 返回按钮 |
| FAILED | 全局失败横幅 + 重生成引导，分区空态 |
| RUNNING | 进度横幅实时刷新，已有内容可看，未生成内容空态 |
| 直达未生成内容 URL | 空态，不崩 |
| 生成中切换子路由 | Layout 轮询不中断，徽标随刷新更新 |

### 7.5 验证

- tsc + oxlint；浏览器实测黄金路径（录入→生成→逐分区浏览→编辑/采纳/重生成→自测→入库）与 §7.4 全表；检查 `scripts/e2e.ps1` 覆盖面并扩展到新路由。
- 不新增前端测试框架。

---

## 八、验收标准（按工作流）

- **C**：现有测试全绿 + 新增契约测试绿；错误响应含中文 `detail`；体检报告在生成后非 null 且为 Markdown；COMPARE_PRACTICE 无残留引用。
- **D**：未登录访问业务 API 全部 401；注册/登录/验证码流程浏览器可用；双用户互不可见对方数据；首用户继承存量；禁用/改密会话失效生效。
- **A**：设置页五+分区浏览器可用；档案切换后**下一次生成**走新端点（AI 服务日志/请求可证）；测试连接返回真实延迟；key 任何 API 响应与日志中不出现完整值；复习参数修改后自评立即按新间隔计算。
- **B**：全部子路由直达可用；徽标准确；§7.4 边界全过；旧链接 `/projects/:id` 兼容。
- **收尾**：`README.md` 与 `config/.env.example` 按 doc-current-state 规范同步（新增 LLM_MASTER_KEY、登录说明、设置说明）；后端 `mvnw test`、ai-service pytest、前端 build + lint 全绿。

## 九、范围外（明确不做）

数据导入/恢复、短信/邮箱验证码、邀请码/注册审批、Flyway 迁移、SSE 推送、Redis 限流/会话、每用户磁盘配额、OAuth 第三方登录、忘记密码邮件找回、多实例部署适配。

## 十、部署清单（文档标注，非代码缺口）

1. 公网必须 HTTPS（反代终结），否则会话 Cookie 与 Key 传输无保护。
2. `LLM_MASTER_KEY` 妥善备份——丢失 = 所有用户 Key 不可解密，只能重填。
3. 多实例部署需替换：内存限流 → Redis、Session → Spring Session Redis。
4. 注册开放 + BYOK 下无 LLM 成本风险，但克隆消耗磁盘/CPU；磁盘配额为后续运营项。