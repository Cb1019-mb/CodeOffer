# CodeOffer · 智能项目学习与简历生成引擎

> **核心目标**:利用 AI 将 GitHub 开源项目转化为个人简历上的核心竞争力,实现从「代码阅读」到「Offer 获取」的高效转化。

## 为什么做这个项目

- **学习转化难**:优秀开源项目多,但常止步于「跑通代码」,缺乏深度理解与内化。
- **简历产出难**:做过很多 Demo,却不知道如何挖掘亮点、写成有竞争力的 STAR 简历描述。

**核心价值主张**:不是笔记工具,而是**主动式的学习内化引擎**——覆盖学习旅程全阶段:

```
①选项目 → ②跑通 → ③读懂 → ④内化 → ⑤验证 → ⑥沉淀
 体检报告  运行指南  核心流程图  主动回忆自测  模拟面试题  STAR 简历
```

## 技术架构(双层服务式)

```
浏览器 React 18 + Vite + Ant Design 5(SPA,端口 5173 dev / 由后端托管产物)
   │ HTTP(JSON,开发模式经 Vite proxy)
   ▼
Java 层 · Spring Boot 4.1 + Java 21(端口 8080)
  ├─ 业务编排:项目 CRUD、异步生成状态机(GenTask)、草稿/掌握度管理
  ├─ 持久化:SQLite + Spring Data JPA
  ├─ 静态托管:前端构建产物
  └─ AiServiceClient:HTTP/1.1 调用 Python 服务
   │ HTTP(JSON)
   ▼
Python 层 · FastAPI(端口 8000)
  ├─ 仓库抓取:GitPython 浅克隆(--depth 1,本地 hash 缓存)
  ├─ 代码解析:目录树 / 语言统计 / 核心文件启发式提取
  ├─ 提示词编排:知识卡片 / 流程图 / STAR 简历 / 面试题 / 体检报告
  └─ LLM 网关:OpenAI 兼容端点,可切换 DeepSeek / 通义 / 智谱
```

**职责边界**:Java 不直接碰模型 API,Python 不碰业务数据库,两层仅通过 HTTP 契约交互。

## 快速开始

环境要求:JDK 21、Python 3.12+、Node 18+、git;Docker 可选(Compose 部署)。

```powershell
# 1. 配置 LLM key(国内大模型,OpenAI 兼容端点)
Copy-Item config\.env.example config\.env
#    编辑 config\.env:填入 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL

# 2. 一键启动(后端 8080 + AI 服务 8000)
.\scripts\dev-up.ps1

# 3. 前端开发模式(可选,生产模式前端产物由后端托管)
cd frontend
npm install
npm run dev          # http://localhost:5173

# 4. 打开浏览器
#    http://localhost:8080  (后端托管)
#    http://localhost:5173  (开发模式)
```

**端到端自检**(自动构造本地仓库,拉起服务,校验录入→生成→编辑→自测):

```powershell
.\scripts\e2e.ps1
```

> 未配置有效 LLM key 时,e2e 验证到「基础设施链路」(克隆/解析/编排/错误处理),AI 生成环节提示配置 key 后重跑。

## 目录结构

```
frontend/   React SPA(录入/列表/详情/自测/导出)
backend/    Spring Boot 服务(实体/编排/AI 客户端/API)
ai-service/ FastAPI 服务(LLM 网关/仓库解析/prompts/测试)
config/     LLM 与端口配置(.env,不入库)
scripts/    dev-up.ps1 一键启动、e2e.ps1 端到端自检
docs/       设计规格(design)与实现计划(plan)
data/       运行时数据(SQLite 库 + 仓库克隆缓存,git 忽略)
```

## 功能清单(已实现)

| 功能 | 说明 | 落点 |
|---|---|---|
| 项目录入 + 体检报告 | 粘贴仓库 URL,AI 评估学习价值与路线 | M1/M2 |
| 仓库解析 | 目录树 / 语言统计 / 核心文件 | M1 |
| 知识卡片 | 架构/难点/亮点/收获/运行指南 + 核心流程图(Mermaid) | M1/M2 |
| 人机协作编辑 | 编辑/重新生成/采纳草稿,已编辑内容不被覆盖 | M4 |
| 简历与面试题 | STAR 四段 + 基础/进阶/深挖题库 | M1/M2 |
| 主动回忆自测 | 翻卡自测,掌握/生疏自评,间隔复习 | M5 |
| 掌握度徽标 | 列表页卡片数/已掌握/待复习 | M3/M5 |
| 导出 | 全部成果 Markdown 导出、简历一键复制 | M6 |

## 测试

```powershell
# Python 层(22 个,LLM 网关/仓库解析/API 端点,mock 响应零费用)
cd ai-service; .\.venv\Scripts\python.exe -m pytest

# Java 层(9 个,编排状态机/控制器/编辑/自测,H2 内存库)
cd backend; .\mvnw.cmd test
```

## 里程碑

- [x] M0 工程骨架:双层服务可独立启动(健康检查/静态托管/Compose)
- [x] M1 Python AI 服务:LLM 网关 + 仓库抓取解析 + 7 契约端点
- [x] M2 Java 后端:实体/状态机/生成编排/项目 API
- [x] M3 前端基础:录入/进度条/列表(掌握度徽标)
- [x] M4 生成与编辑:卡片/简历编辑 + 重新生成不覆盖已编辑
- [x] M5 内化功能:N1 主动回忆自测 + N7 掌握度
- [x] M6 导出与 E2E:Markdown 导出 + e2e.ps1 全链路

## 扩展路线图(P1)

- 对话式学习:「如果我来实现」对比练习 + 追问面试模式
- 完整学习仪表盘(趋势/待复习队列/时长)
- 简历导出 PDF、多仓库对比

设计文档:`docs/superpowers/specs/2026-08-09-codeoffer-design.md`
实现计划:`docs/superpowers/plans/2026-08-09-codeoffer-implementation-plan.md`
