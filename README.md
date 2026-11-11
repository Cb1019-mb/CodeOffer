<div align="center">

# 🧠 CodeOffer — AI 简历知识库生成平台

> 输入一个 GitHub/Gitee/GitLab 仓库地址，AI 自动生成 **知识卡片、项目体检报告、核心流程图、STAR 简历与模拟面试题**，帮助你真正「读懂并讲出」一个开源项目。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green)
![React](https://img.shields.io/badge/React-19-blue)
![FastAPI](https://img.shields.io/badge/FastAPI-blue)

</div>

---

## 目录

- [项目概述](#一项目概述)
- [技术栈](#二技术栈)
- [环境配置与安装](#三环境配置与安装)
- [使用指南](#四使用指南)
- [API 文档](#五api-文档)
- [项目结构](#六项目结构)
- [贡献规范](#七贡献规范)
- [许可证](#八许可证)
- [联系方式与致谢](#九联系方式与致谢)

---

## 一、项目概述

### 1.1 简介

**CodeOffer** 是一个「学习型 AI 助手」Web 平台。传统的「读开源项目」往往止步于 `git clone` 之后的一头雾水。CodeOffer 通过 **双层服务架构**（Java 业务后端 + Python AI 服务），对任意受信任的仓库地址完成：

1. **浅克隆**并静态解析仓库（目录树、语言统计、核心文件）；
2. 调用大模型生成结构化知识产物；
3. 通过前端呈现，并支持**编辑、自测、复习**等闭环学习流程。

它面向的典型场景是：求职者准备项目面试、开发者快速上手陌生代码库、复盘技术亮点。

### 1.2 主要特性

- **一键录入**：粘贴仓库 URL 即自动触发生成，全流程异步执行并实时反馈进度。
- **五类知识产物**：
  - 📋 **知识卡片**：架构设计 / 技术难点 / 项目亮点 / 学习收获 / 运行指南
  - 🏥 **项目体检报告**：值不值得学 + 学习路线建议
  - 🧭 **核心流程图**：Mermaid 可视化关键流程
  - 📝 **STAR 简历描述**：可直接用于项目简历
  - 🎯 **模拟面试题**：基础 / 进阶 / 深挖三档
- **迭代编辑**：AI 草稿 → 采纳或手写定稿；已编辑内容在重新生成时**不会被覆盖**。
- **自测与复习**：卡片掌握度（0–5 级）+ 间隔复习队列，内置待复习队列。
- **完整成果管理**：确认入库、删除项目、整仓重新生成。

---

## 二、技术栈

| 层次 | 技术 | 说明 |
| --- | --- | --- |
| **后端** | Java 21 · Spring Boot 4.1 | 业务 API、状态机、异步生成编排 |
| | Spring Data JPA · MySQL 8.4（mysql-connector-j；测试用 H2） | 数据持久化 |
| | Spring Actuator · Validation | 健康检查与参数校验 |
| **AI 服务** | Python 3.13 · FastAPI · Uvicorn | 仓库抓取/解析 + LLM 调用 |
| | GitPython · httpx · pydantic | 仓库操作 / HTTP 客户端 / 数据模型 |
| **前端** | React 19 · TypeScript · Vite | SPA 界面 |
| | Ant Design 6 · react-router-dom 7 | UI 组件与路由 |
| | react-markdown · mermaid 11 | Markdown 渲染与流程图 |
| **工程化** | Maven · npm · oxlint | 构建与代码规范 |
| **部署** | Docker Compose | 一键容器化 |

> **AI 提供商**：通过 OpenAI 兼容端点接入，支持 `deepseek` / `qwen` / `zhipu`（见 `config/.env.example`）。

---

## 三、环境配置与安装

### 3.1 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21+ |
| Maven | 3.9+（仓库自带 `mvnw.cmd`） |
| Node.js | 20+（推荐 22 LTS） |
| Python | 3.11+（本项目开发环境为 3.13） |
| Git | 2.30+（本地 file 仓库场景） |
| MySQL | 8.0+（本机服务名 MySQL，默认端口 3306） |
| Docker（可选） | 容器化部署时需要 |

### 3.2 克隆与配置

```bash
git clone https://github.com/Cb1019-mb/CodeOffer.git
cd CodeOffer
```

复制环境变量模板并填入真实的 LLM Key：

```bash
cp config/.env.example config/.env
```

编辑 `config/.env`：

```dotenv
# AI 提供商:deepseek / qwen / zhipu(均走 OpenAI 兼容端点)
LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
LLM_MODEL=deepseek-chat

# 端口
BACKEND_PORT=8080
AI_SERVICE_PORT=8001

# MySQL(后端持久层;库与账号需预先创建,见 scripts/setup-mysql.sql)
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=codeoffer
MYSQL_USER=codeoffer_app
MYSQL_PASSWORD=your-password
```

> ⚠️ `config/.env` **不入库**（见 `.gitignore`）。若在代理/自签名证书环境遇到 SSL 报错，可追加 `LLM_VERIFY_SSL=false`。
> MySQL 初始化执行 `scripts/setup-mysql.sql`：创建 `codeoffer` 库与 `codeoffer_app` 账号（只建库建账号，不建表；表由后端 `ddl-auto: update` 自动生成）。`docs/database/schema.sql` 仅是建表语句参考，以 `DROP TABLE IF EXISTS` 开头、重跑会清空数据，不要当作初始化脚本执行。存量 SQLite 数据迁移用 `scripts/migrate_sqlite_to_mysql.py`。该脚本会先清空目标四表再写入快照，目标表非空时须加 `--force` 才会执行，效果是回退到 SQLite 快照并丢弃迁移后新增的数据。

### 3.3 一键启动（推荐，Windows）

仓库提供了开发环境一键启动脚本，会先确认本机 MySQL 服务已运行（未运行则自动 `Start-Service`），再创建 Python 虚拟环境并拉起两个服务：

```powershell
.\scripts\dev-up.ps1
```

启动后访问：

- 后端健康检查：<http://127.0.0.1:8080/api/health>
- AI 服务健康检查：<http://127.0.0.1:8001/api/health>
- 前端开发服务器（可选，需单独启动）：见下

手动分步启动时，请自行确保 `MySQL` 服务已启动（`net start MySQL`）。

### 3.4 手动分步启动

**① AI 服务（端口 8001）**

```bash
cd ai-service
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt   # Windows
# 或 source .venv/bin/activate && pip install -r requirements.txt
.venv\Scripts\python run.py --port 8001
```

**② 后端（端口 8080）**

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

**③ 前端（开发模式，端口 5173）**

```bash
cd frontend
npm install
npm run dev
```

生产模式下，前端产物会构建并复制到后端 `static` 目录，由 Java 后端统一托管（端口 8080）：

```powershell
.\scripts\build-frontend.ps1
```

### 3.5 Docker Compose（可选）

```bash
docker compose up -d
```

Compose 会依次构建并启动 `mysql`（3306）、`ai-service`（8001）与 `backend`（8080）：

- `mysql` 使用 `mysql:8.4` 镜像，数据持久化到命名卷 `mysql-data`，库名取 `MYSQL_DB`（默认 `codeoffer`）；
- `backend` 等待 `ai-service` 与 `mysql` 均通过健康检查后才启动，容器内通过 `MYSQL_HOST=mysql` 连接数据库；
- 宿主机的 `./data` 挂载为 `/data`。MySQL 账号密码仍从 `config/.env` 注入（缺失时按 `.env.example` 补齐）。

> 若本机已有 MySQL 服务占用 3306：
>
> - 需要容器发布到 3306 时，先停掉本机服务（管理员权限）：`net stop MySQL`；
> - 保留本机服务时，在 shell 里为本次调用指定其它宿主机端口（Compose 的 `${MYSQL_PORT:-3306}` 只从 shell 环境或仓库根 `.env` 读取，`config/.env` 仅注入容器内部，改它不影响端口映射）：
>
>   ```bash
>   MYSQL_PORT=3307 docker compose up -d          # Git Bash
>   ```
>
>   ```powershell
>   $env:MYSQL_PORT="3307"; docker compose up -d  # PowerShell
>   ```
>
> 容器内的 `backend` 仍通过 `mysql:3306` 访问数据库，无需改动 `config/.env`。另外，`docker compose up -d ai-service backend` 并不能避开该冲突：`backend` 依赖 `mysql` 的健康检查，Compose 仍会启动并发布 `mysql`。

### 3.6 常见问题

| 现象 | 处理 |
| --- | --- |
| 端口 3306 被占用 / MySQL 服务未启动 | 本机服务未启动时执行 `net start MySQL`（服务名 `MySQL`）；若本机服务占着 3306 而要用 Compose 的 `mysql`，先 `net stop MySQL`，或在 shell 里设 `MYSQL_PORT` 把容器发布到其它端口（见 3.5）——不要改 `config/.env` 的 `MYSQL_PORT`，它只决定后端 JDBC 连接端口 |

---

## 四、使用指南

### 4.1 基本流程

```
录入仓库 → 异步生成（克隆→解析→生成）→ 审阅产物 → 编辑/采纳 → 自测复习 → 确认入库
```

### 4.2 核心功能演示

1. **录入项目**：在首页输入受信任平台（GitHub / Gitee / GitLab / GitCode / Bitbucket 等）的 HTTPS 仓库地址，点击创建。后端校验通过后立即返回 `projectId` 并异步生成。
2. **查看进度**：项目详情页轮询 `/api/projects/{id}/task`，展示任务状态与进度。
3. **审阅知识卡片**：生成完成后（`REVIEW` 状态）浏览五类卡片。对每张卡片可：
   - 编辑定稿（`PUT .../content`）；
   - 采纳 AI 草稿（`POST .../adopt`）；
   - 重新生成单卡（`POST .../regenerate`）；
   - 补充个人输入（`POST .../user-input`）。
4. **自测复习**：对卡片进行「掌握 / 生疏」自评，更新掌握度与下次复习时间；「待复习」队列会汇总到期卡片。
5. **导出与入库**：审阅满意后点击「确认入库」，项目状态变为 `DONE`，成果持久化保存。

### 4.3 状态机

项目状态流转如下：

```
PENDING → CLONING → ANALYZING → GENERATING → REVIEW → DONE
                              ↘（任意失败）       FAILED
```

- `REVIEW` / `DONE` 状态可「确认入库」；
- 任何非「生成中」状态均可「重新生成」整仓（已编辑内容不会被覆盖）。

### 4.4 常见用例

| 场景 | 操作 |
| --- | --- |
| 准备项目面试 | 录入目标仓库 → 阅读知识卡片 → 练习模拟面试题 |
| 快速上手陌生代码库 | 录入仓库 → 查看核心流程图与运行指南 |
| 丰富简历 | 生成 STAR 简历描述并编辑定稿 |
| 长期复习巩固 | 使用自测与待复习队列管理掌握度 |

---

## 五、API 文档

服务启动后，交互式 API 文档可直接访问：

- **后端**（Spring Boot）：`/swagger-ui.html`（如启用）
- **AI 服务**（FastAPI）：<http://127.0.0.1:8001/docs>

### 5.1 后端 API（`/api`）

#### 项目

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/projects` | 录入仓库，创建项目与任务并异步生成 |
| `GET` | `/api/projects` | 项目列表（含掌握度徽标聚合） |
| `GET` | `/api/projects/{id}` | 项目详情（卡片 + 简历） |
| `GET` | `/api/projects/{id}/task` | 最新生成任务（前端轮询进度） |
| `GET` | `/api/projects/{id}/cards` | 知识卡片列表 |
| `GET` | `/api/projects/{id}/resume` | 简历产出 |
| `POST` | `/api/projects/{id}/confirm` | 确认入库（REVIEW/DONE → DONE） |
| `DELETE` | `/api/projects/{id}` | 删除项目及全部关联数据 |
| `POST` | `/api/projects/{id}/regenerate` | 重新生成整个项目 |

#### 卡片

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/api/projects/{id}/cards/{cardId}/content` | 编辑卡片定稿 |
| `POST` | `/api/projects/{id}/cards/{cardId}/adopt` | 采纳 AI 草稿 |
| `POST` | `/api/projects/{id}/cards/{cardId}/regenerate` | 重新生成单卡 |
| `POST` | `/api/projects/{id}/cards/{cardId}/user-input` | 补充个人输入 |
| `POST` | `/api/projects/{id}/cards/{cardId}/review` | 自测（掌握/生疏） |
| `GET` | `/api/projects/{id}/review-queue` | 待复习队列 |

#### 简历

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/api/projects/{id}/resume/star` | 编辑 STAR 简历 |
| `POST` | `/api/projects/{id}/resume/regenerate` | 重新生成简历与面试题 |

#### 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/health` | 后端存活探针 |

#### 调用示例

```bash
# 录入仓库（触发异步生成）
curl -X POST http://127.0.0.1:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"repoUrl": "https://github.com/octocat/Hello-World"}'
# => {"projectId":1,"taskId":1,"status":"PENDING"}

# 轮询生成进度
curl http://127.0.0.1:8080/api/projects/1/task

# 获取项目详情（卡片 + 简历）
curl http://127.0.0.1:8080/api/projects/1
```

### 5.2 AI 服务 API（`/api`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/health` | AI 服务存活探针 |
| `POST` | `/api/repos/clone` | 浅克隆仓库到缓存（命中返回缓存） |
| `POST` | `/api/repos/analyze` | 静态解析仓库（目录树/语言统计/核心文件） |
| `POST` | `/api/ai/health-report` | 生成项目体检报告 |
| `POST` | `/api/ai/knowledge-cards` | 生成知识卡片 |
| `POST` | `/api/ai/flow-diagram` | 生成核心流程 Mermaid 图 |
| `POST` | `/api/ai/resume` | 生成 STAR 简历描述 |
| `POST` | `/api/ai/interview-questions` | 生成模拟面试题 |

---

## 六、项目结构

```
CodeOffer/
├── backend/                      # Java 业务后端 (Spring Boot)
│   ├── pom.xml
│   └── src/main/java/com/codeoffer/backend/
│       ├── BackendApplication.java
│       ├── HealthController.java
│       ├── ai/                   # 异步生成编排与 AI 客户端
│       │   ├── AiServiceClient.java
│       │   ├── GenerateService.java
│       │   └── GenerationWriter.java
│       ├── config/               # 异步、Web、SPA 回退配置
│       └── project/              # 领域模型、仓库、控制器、API 视图
│   └── src/test/                 # 集成与控制器测试
│
├── ai-service/                   # Python AI 服务 (FastAPI)
│   ├── main.py                   # 路由入口
│   ├── schemas.py                # Pydantic 请求/响应模型
│   ├── run.py                    # 服务启动器 (SO_REUSEADDR)
│   ├── llm/gateway.py            # LLM 网关 (OpenAI 兼容)
│   ├── prompts/                  # 各类生成任务的 Prompt 构建
│   ├── services/repo.py          # 仓库克隆与静态解析
│   └── tests/                    # pytest 测试
│
├── frontend/                     # React SPA
│   ├── src/
│   │   ├── api/client.ts         # 后端 API 客户端
│   │   ├── pages/                # HomePage / ProjectPage
│   │   ├── components/           # CardList / QuizMode / ResumeSection / ...
│   │   ├── utils/export.ts       # 导出工具
│   │   ├── types.ts
│   │   ├── App.tsx / main.tsx
│   └── package.json
│
├── scripts/                      # 开发/构建/E2E/运维脚本 (PowerShell · Python · SQL)
│   ├── dev-up.ps1
│   ├── build-frontend.ps1
│   ├── e2e.ps1
│   ├── migrate_sqlite_to_mysql.py # 存量 SQLite 数据迁移到 MySQL
│   └── setup-mysql.sql           # 建库/建账号初始化脚本
│
├── config/
│   ├── .env.example              # 环境变量模板
│   └── .env                      # 本地配置（不入库）
├── data/                         # 运行时数据（克隆仓库 / 日志，不入库）
├── docs/
│   ├── database/schema.sql       # 建表语句（由真实库导出）
│   └── superpowers/              # 设计与实现文档
├── docker-compose.yml
└── LICENSE
```

---

## 七、贡献规范

欢迎提交 Issue 与 Pull Request！请遵循以下约定，保证协作的一致性。

### 7.1 分支管理

- 主分支：`master`（受保护，禁止直接推送）。
- 功能分支命名：`feature/<简述>`、`fix/<简述>`、`docs/<简述>`。
- 提交前请确保当前分支基于最新的 `master`。

### 7.2 提交信息格式

采用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<type>(<scope>): <subject>

<body>(可选)
```

- `type`：`feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `style`
- `scope`（可选）：`backend` / `ai-service` / `frontend` / `scripts` / `docs`

示例：

```text
feat(frontend): 增加复习队列倒计时显示

fix(ai-service): 修复 LLM 超时导致的 502 错误
```

### 7.3 代码规范

- **后端**：遵循 Spring 官方约定，保持 `project` 包内「控制器 — 服务 — 仓库 — 视图」分层；公共映射逻辑收敛到 `toXxxView()` 辅助方法，避免内联重复。
- **AI 服务**：保持「服务只处理仓库 + 大模型、不触碰业务 DB」的职责边界；新增 Prompt 请放入 `prompts/`。
- **前端**：使用 `npm run lint`（oxlint）校验，类型错误用 `npm run build` 拦截。
- **测试**：后端 `.\mvnw.cmd test`、AI 服务 `.venv\Scripts\python -m pytest`；新增功能应补充相应单测。

### 7.4 Pull Request 流程

1. Fork 仓库并创建功能分支；
2. 提交代码，确保本地测试全部通过；
3. 向 `master` 发起 PR，标题遵循提交信息规范；
4. 通过 CI 与 Reviewer 审查后合并（squash merge）。

---

## 八、许可证

本项目基于 **[Apache License 2.0](LICENSE)** 开源。

```
Copyright [2026] [CodeOffer Contributors]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 九、联系方式与致谢

### 联系方式

- **仓库**：<https://github.com/Cb1019-mb/CodeOffer>
- 欢迎通过 GitHub Issues 提交缺陷、功能建议或疑问。

### 致谢

- 感谢 [Spring Boot](https://spring.io/projects/spring-boot)、[FastAPI](https://fastapi.tiangolo.com/)、[React](https://react.dev/)、[Ant Design](https://ant.design/) 等优秀开源框架。
- 感谢 DeepSeek / Qwen / 智谱 AI 提供的大模型能力。
- 感谢所有为本项目贡献代码、文档与反馈的贡献者。

---

*文档维护：随代码演进持续更新。若发现与实现不一致，欢迎提交 PR 修正。*
