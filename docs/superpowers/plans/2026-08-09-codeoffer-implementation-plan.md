# CodeOffer 实现计划(MVP)

> 日期:2026-08-09
> 依据设计文档:`docs/superpowers/specs/2026-08-09-codeoffer-design.md`(v2,已批准)
> 注:本环境无 writing-plans 技能,此文档为手动编写的等价实现计划。

## 1. 目标与范围

实现 CodeOffer MVP:本地 Web 应用,双层服务架构(Spring Boot + FastAPI),完整链路浅做——
录入(含体检报告)→ 解析 → 知识卡片(基础 4 类 + 运行指南 + 核心流程图)→ STAR 简历 + 面试题 → 主动回忆自测 + 掌握度徽标。

**不在本计划范围**:N2 对比练习、追问面试模式、完整仪表盘、PDF 导出、多仓库对比(均列 P1,见设计文档 §10)。

## 2. 技术选型

| 层 | 技术 | 说明 |
|---|---|---|
| 前端 | React 18 + Vite + Ant Design 5 | SPA;`mermaid` 运行时渲染流程图;`react-markdown` 渲染卡片 |
| Java | Spring Boot 3.3 + Java 17 | Web、JPA;SQLite 用 `org.xerial:sqlite-jdbc` + Hibernate dialect |
| Python | FastAPI + uvicorn + httpx + GitPython | AI 服务;LLM 网关走 OpenAI 兼容格式端点 |
| 部署 | Docker Compose | `java`(8080) + `python`(8000);前端构建产物由 Java 静态托管 |
| 配置 | 根目录 `config/` | `.env` 存 LLM API key(不入库) |

**LLM 网关设计**:国内各家(DeepSeek/通义/智谱)均提供 OpenAI 兼容端点,网关统一按 `/v1/chat/completions` 调用,`base_url + api_key + model` 三个配置项即可切换;响应统一校验 JSON 并修复重试。

## 3. Java ↔ Python 契约草案

| 方法 | Python 端点 | 请求 | 响应 |
|---|---|---|---|
| 克隆 | `POST /api/repos/clone` | `{repo_url}` | `{repo_dir, ok}` |
| 解析 | `POST /api/repos/analyze` | `{repo_dir}` | 目录树/语言统计/核心文件清单(JSON) |
| 体检报告 | `POST /api/ai/health-report` | `{repo_url, meta}` | 报告(JSON: 值得学/理由/路线) |
| 卡片 | `POST /api/ai/knowledge-cards` | `{repo_info, types[]}` | `{cards: [{type, title, content}]}` |
| 流程图 | `POST /api/ai/flow-diagram` | `{repo_info}` | `{mermaid: string}` |
| 简历 | `POST /api/ai/resume` | `{repo_info}` | `{star: {situation,task,action,result}}` |
| 面试题 | `POST /api/ai/interview-questions` | `{repo_info}` | `{questions: [{level, q, a}]}` |

说明:生成类端点**同步返回**草稿(大型模型单次请求);Java 侧 `gen_task` 负责编排与进度状态,AI 服务不做任务队列(MVP 浅做,超时走网关重试)。

## 4. 里程碑与任务

### M0 · 工程骨架
- [ ] 仓库目录结构:`frontend/`、`backend/`(Java)、`ai-service/`(Python)、`scripts/`、`config/`、`data/`
- [ ] 三个子项目可独立启动:Vite dev server、`mvn spring-boot:run`、`uvicorn`
- [ ] Docker Compose 编排 + `.env.example`(LLM key 占位)
- [ ] Java 静态托管前端构建产物
- **验收**:`scripts/dev-up.ps1` 一键拉起,浏览器打开 Java 8080 看到前端首页;健康检查端点 `/actuator/health`、`/api/health` 返回 OK。

### M1 · Python AI 服务
- [ ] `llm/gateway.py`:统一 OpenAI 兼容调用,JSON 校验 + 指数退避重试
- [ ] `services/repo.py`:GitPython 浅克隆(`--depth 1`)、目录树、语言统计、核心文件提取
- [ ] `prompts/`:各产物类型结构化 prompt(卡片/流程图/简历/面试题/体检)
- [ ] `main.py`:注册 §3 契约端点;`tests/` pytest(mock LLM 响应)
- **验收**:`pytest` 全绿;对公开小仓库(如 `octocat/Hello-World`)手动 curl 各端点返回合理 JSON。

### M2 · Java 后端
- [ ] 实体:`Project`、`KnowledgeCard`、`ResumeEntry`、`GenTask` + Repository
- [ ] 状态机:`PENDING → CLONING → ANALYZING → GENERATING → REVIEW → DONE / FAILED`,含重试
- [ ] `AiServiceClient`:HTTP 调 Python 服务(Spring `RestClient` 或 WebClient)
- [ ] 业务 Service:项目 CRUD、生成编排(串行调用契约端点、落库 ai_draft)、掌握度更新
- [ ] 控制器:`/api/projects`、`/api/projects/{id}/cards|resume|questions`、`/api/projects/{id}/review`
- **验收**:`mvn test`(H2)全绿;curl 走通"建项目 → 触发生成 → 查状态 → 查卡片"。

### M3 · 前端基础
- [ ] 项目录入页(URL 输入 + 体检报告可选勾选)+ 任务进度条(轮询 gen_task)
- [ ] 项目列表页(含掌握度徽标:卡片数/通过率/待复习)
- [ ] 项目详情页路由与骨架
- **验收**:录入一个仓库 URL,页面实时看到 抓取→解析→生成 进度推进。

### M4 · 生成与编辑
- [ ] 卡片列表:按 type 分组展示,Markdown 渲染;Mermaid 卡渲染流程图(失败降级代码块)
- [ ] 卡片编辑:"重新生成 / 采纳草稿 / 手动编辑",编辑后标"已修改"且不再被覆盖
- [ ] 简历条目 STAR 四段展示与编辑;面试题分级展示
- [ ] 运行指南卡:AI 草稿 + 用户追加踩坑记录
- **验收**:对真实仓库生成完整产物;编辑后重新生成不覆盖已修改内容。

### M5 · 内化功能(N1/N7)
- [ ] 自测模式:卡片→问题卡(正面问题/背面答案),翻面自评,更新 `mastery`/`next_review`
- [ ] 待复习优先队列(按 `next_review` 排序)
- [ ] 掌握度徽标数据接口(聚合卡片数/通过率/待复习数)
- **验收**:自测一轮后重进页面,掌握度徽标与待复习顺序正确持久化。

### M6 · 导出与端到端
- [ ] Markdown 导出(卡片合集 / 简历);简历一键复制
- [ ] `scripts/e2e.ps1`:拉起两层 → 真实小仓库全链路(录入→生成→编辑→自测)
- [ ] 测试补全:Java(状态机/掌握度)+ Python(解析/网关)+ 前端冒烟
- [ ] 更新 `README.md`:架构图、启动方式、使用说明
- **验收**:`e2e.ps1` 全流程通过;按 README 从零可复现。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 大仓库克隆慢/超限 | `--depth 1`;>100MB 提示并中止 |
| LLM JSON 格式不稳 | 网关 JSON 校验 + 1 次修复重试;仍失败降级纯文本 |
| Hibernate + SQLite 方言坑 | 锁死 sqlite-jdbc 版本 + 显式 dialect;实体避免保留字字段 |
| 国内 API 限流 | 指数退避 + 配置并发上限(默认串行) |
| Mermaid 渲染兼容 | 前端降级代码块 + 新窗口打开链接 |

## 6. 完成定义(DoD)

- [ ] M0-M6 全部任务完成,各里程碑验收通过
- [ ] `pytest`、`mvn test` 全绿;`scripts/e2e.ps1` 全流程通过
- [ ] 对 1 个真实中小型开源仓库(如 `spring-projects/spring-boot` 或用户指定)产出:体检报告、6 类卡片、STAR 简历、面试题,并完成一次自测
- [ ] README 更新,从零可复现启动
