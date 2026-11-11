# CodeOffer 实训优化设计规格：MySQL 迁移与实训交付物

> 课程：《软件开发综合实训》（计算机科学与技术，第五学期，2 周 32 学时）
> 项目：CodeOffer — AI 简历知识库生成平台
> 状态：待用户审阅

## 1. 背景与目标

CodeOffer 现有系统为「React 19 前端 + Spring Boot 4.1 后端 + FastAPI AI 服务」的双层架构 Web 应用，功能完整（仓库录入、异步生成、五类知识产物、编辑采纳、自测复习、成果入库）。

本次实训优化包含两件事：

1. **持久层迁移**：将运行数据库从 SQLite 切换为 MySQL 8，对齐指导书工具要求（IDEA / MySQL / Git / Chrome DevTools）与「数据库设计」环节，并迁移现有业务数据。
2. **实训交付物套件**：按指导书七环节补齐需求分析、系统设计、测试文档、实训报告、答辩 PPT，全部内容聚焦软件开发生命周期本身。

明确不做的事：不新增功能、不新增自动化测试代码、不改前端与 AI 服务、不引入 Flyway、不加物理外键、不做双数据库 Profile。所有文档内容只描述开发流程，不包含任何时间性叙事。

## 2. 实训环节映射

| 指导书环节 | 本项目工作项 | 交付物 |
| --- | --- | --- |
| 环节一 系统设计基础 | 选题背景、技术选型、开发计划 | 01-需求分析说明书 §1 |
| 环节二 系统分析和环境搭建 | 功能模块拆分、用例图、E-R 图、建表语句、MySQL 环境 | 01-需求分析说明书、02-系统设计说明书、README |
| 环节三 设计和实现前端功能 | 前端设计与实现（已有，文档化阐述） | 02-系统设计说明书前端章节、04-实训报告 |
| 环节四 设计和实现主要业务逻辑功能 | Java 分层架构、CRUD、业务逻辑、前后端对接（迁移后） | 02-系统设计说明书、04-实训报告核心代码详解 |
| 环节五 系统测试 | 白盒覆盖说明 + 黑盒用例设计与执行记录 | 03-测试用例与测试报告 |
| 环节六 撰写实训报告 | 按指导书结构撰写 | 04-实训报告.docx |
| 环节七 系统和报告验收 | 演示流程、技术要点问答准备 | 05-答辩PPT.pptx |

## 3. 架构与改动清单

架构不变：浏览器 → React SPA（生产由后端托管）→ Spring Boot 后端（8080）→ FastAPI AI 服务（8001）；持久层由 SQLite 文件改为 MySQL 8。

| 文件 | 改动 |
| --- | --- |
| `backend/pom.xml` | 移除 `sqlite-jdbc`、`hibernate-community-dialects`；新增 `com.mysql:mysql-connector-j`（runtime scope，版本由 Spring Boot 父 POM 管理） |
| `backend/src/main/resources/application.yml` | 数据源改为 `jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DB:codeoffer}`（utf8mb4、`serverTimezone=Asia/Shanghai`、`allowPublicKeyRetrieval=true`、`useSSL=false`）；驱动 `com.mysql.cj.jdbc.Driver`；删除 SQLite 的 `connection-init-sql: PRAGMA busy_timeout`；删除 `database-platform`（自动检测 MySQLDialect）；`ddl-auto: update` 保留 |
| `backend/.../project/Project.java`、`KnowledgeCard.java`、`GenTask.java`、`ResumeEntry.java` | `@GeneratedValue(strategy = AUTO)` → `IDENTITY`（MySQL 自增主键；AUTO 在 MySQL 下会继续生成 `*_seq` 模拟序列表，不必要的复杂度） |
| `docker-compose.yml` | 新增 `mysql` 服务（`mysql:8.4`，`MYSQL_DATABASE/MYSQL_USER/MYSQL_PASSWORD` 来自 `config/.env`，独立数据卷，healthcheck）；`backend` 增加 `depends_on: mysql: condition: service_healthy`，并注入 `MYSQL_HOST=mysql` 等变量 |
| `config/.env.example` | 新增 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD` 模板 |
| `scripts/dev-up.ps1` | 启动前检测本机 MySQL Windows 服务（`MySQL`），未运行则尝试 `Start-Service`，失败则提示手动启动并退出 |
| `README.md` | 环境要求增加 MySQL 8.0+；技术栈表、配置、启动步骤、Docker 段更新 |
| 新增 `scripts/migrate_sqlite_to_mysql.py` | 一次性数据迁移脚本（见 §5） |
| 新增 `docs/database/schema.sql` | 建表语句（从迁移后的运行库导出并校对，作为设计文档与报告附录） |

前端、AI 服务、`data/repos` 克隆缓存均不改动。

## 4. 数据库设计

### 4.1 实体关系（逻辑）

```
project 1 ──< knowledge_card   (project_id)
project 1 ──< gen_task         (project_id)
project 1 ──< resume_entry     (project_id)
```

关联由业务层维护（实体无 JPA 关联映射），本次不新增物理外键：现有删除流程按顺序清理子表，加外键会引入行为变化与回归风险。E-R 图与文档按上述逻辑关系呈现。

### 4.2 表结构（以 Hibernate 生成的 DDL 为权威，`schema.sql` 从运行库导出）

统一 `ENGINE=InnoDB`、`utf8mb4 / utf8mb4_unicode_ci`。

| 表 | 字段（逻辑） | 说明 |
| --- | --- | --- |
| `project` | `id` BIGINT 主键自增；`repo_url` VARCHAR NOT NULL；`name`；`description`；`tech_stack`；`status` VARCHAR NOT NULL（PENDING/CLONING/ANALYZING/GENERATING/REVIEW/DONE/FAILED）；`health_report`；`repo_info` 大文本；`repo_dir`；`created_at`、`updated_at` | 一个仓库一个项目 |
| `gen_task` | `id`；`project_id`；`type`（FULL_GENERATION/REGENERATE_CARD）；`status`（RUNNING/DONE/FAILED）；`progress` INT；`error_msg`；`created_at`、`updated_at` | 驱动进度与重试 |
| `knowledge_card` | `id`；`project_id`；`type`（7 种枚举）；`title`；`content`；`ai_draft`；`user_input`；`edited`；`mastery` INT；`last_reviewed`；`next_review`；`created_at`、`updated_at` | 草稿/定稿分离与复习调度 |
| `resume_entry` | `id`；`project_id`；`star_content`；`interview_qas`；`ai_draft`；`edited`；`created_at`、`updated_at` | STAR 简历与面试题库 |

字段类型以 Hibernate 6 + MySQLDialect 实际导出为准（如 `repo_info` 预期 MEDIUMTEXT、`edited` 预期 BIT/TINYINT），导出后写入 `schema.sql` 并在设计文档中展示，不做手工猜测。

## 5. 数据迁移

- **源**：`data/codeoffer.db`（SQLite，只读打开，文件保持原样，作为回滚依据）。
- **目标**：MySQL 库 `codeoffer`。表结构由后端首次启动的 `ddl-auto: update` 创建；迁移脚本只负责写数据，启动时校验四表存在，缺表则报错退出并提示先启动一次后端。
- **范围**：`project`(5) / `gen_task`(21) / `knowledge_card`(30) / `resume_entry`(5)。`HTE_*`（空内部表）与 `*_seq`（AUTO 序列模拟表）不迁移——迁移后主键为 IDENTITY 自增，序列表不再需要。
- **流程**（`scripts/migrate_sqlite_to_mysql.py`）：
  1. 读取环境变量连接 MySQL（复用 `config/.env`，脚本内解析，不硬编码密码）；
  2. 清空目标四表（`DELETE`，保持表结构）；
  3. 单事务逐表写入，显式保留主键 `id`；
  4. 写入完成后 `ALTER TABLE <t> AUTO_INCREMENT = max(id)+1`；
  5. **对账**：逐表 `COUNT(*)` 与 `MAX(id)` 比对，任一项不一致即失败回滚，进程退出码非 0；
  6. 抽样校验：`project.repo_url` 集合与源一致。
- **依赖**：脚本使用 Python 3（本机 3.13）+ `PyMySQL`；如未安装则在执行前安装（不写入 AI 服务 requirements，保持职责边界）。
- **回滚**：SQLite 文件未被修改；MySQL 侧失败可 `DROP DATABASE codeoffer` 重建后重试。

## 6. 配置与安全

- `config/.env`（不入库）新增：`MYSQL_HOST=127.0.0.1`、`MYSQL_PORT=3306`、`MYSQL_DB=codeoffer`、`MYSQL_USER=codeoffer_app`、`MYSQL_PASSWORD=<用户填写>`。
- 账号由用户在 MySQL 中创建（本机 Windows 服务 `MySQL` 已运行）：

```sql
CREATE DATABASE IF NOT EXISTS codeoffer CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'codeoffer_app'@'localhost' IDENTIFIED BY '<密码>';
GRANT ALL PRIVILEGES ON codeoffer.* TO 'codeoffer_app'@'localhost';
FLUSH PRIVILEGES;
```

- Agent 不读取、不记录、不校验密码；凭据仅通过环境变量注入后端进程与容器。

## 7. 测试与验证

| 项 | 方式 | 通过标准 |
| --- | --- | --- |
| 后端回归 | `backend\mvnw.cmd test`（H2） | 现有用例全绿 |
| AI 服务回归 | `ai-service\.venv\Scripts\python -m pytest` | 全绿 |
| 迁移对账 | 迁移脚本内建 | 四表行数/最大 ID 一致，退出码 0 |
| 真实库启动 | 后端连 MySQL 启动 | `/api/health` 200；`/api/projects` 返回 5 项且详情/卡片正常 |
| 黑盒用例 | 按等价类+边界值设计 15–20 条用例，覆盖录入、状态机、卡片编辑/采纳/重生成、复习、确认入库、删除；curl 或前端手工执行 | 记录执行结果进测试报告；发现缺陷修复后复测 |
| 白盒说明 | 现有单元/集成测试的语句/分支覆盖叙述 | 写入测试文档，不设强制覆盖率门禁 |
| 前端 | `npm run build` + `npm run lint` | 通过 |
| Docker Compose | 配置审查（本机 Docker daemon 未运行） | 如实标注「未实测」，以本机 MySQL 为验收路径 |

## 8. 交付物

**仓库内（技术产物）**

- `scripts/migrate_sqlite_to_mysql.py`
- `docs/database/schema.sql`
- 本规格 `docs/design/specs/2026-11-10-codeoffer-training-optimization-design.md`
- 更新后的 `README.md`、`docker-compose.yml`、`config/.env.example`、`scripts/dev-up.ps1`

**仓库外（课程材料，含个人信息，避免进入公开仓库）**
目录：`D:\Internship Program\CodeOffer-实训材料\`

| 文件 | 内容 | 格式 |
| --- | --- | --- |
| `01-需求分析说明书.docx` | 项目概述、选题背景、技术选型、实施计划、功能模块拆分、用例图、业务规则 | docx |
| `02-系统设计说明书.docx` | 总体架构、模块设计、E-R 图、表结构、schema.sql 附录、接口设计、前端设计 | docx |
| `03-测试用例与测试报告.docx` | 白盒覆盖说明、黑盒测试用例表（编号/前置/步骤/预期/实际/结论）、缺陷与修复记录 | docx |
| `04-实训报告.docx` | 封面占位说明 → 自动目录 → ①目的及意义 ②实训任务 ③实训时间及地点（指导书要求小节，仅写时间地点事实）④实训内容（4.1 开发环境 / 4.2 需求分析 / 4.3 系统设计 / 4.4 编码与实现 / 4.5 系统测试 / 4.6 部署运行）⑤小结 → 参考文献 | docx |
| `05-答辩PPT.pptx` | 选题与方案、架构、数据库设计、核心功能演示要点、测试结果、技术难点与解决、总结与展望 | pptx |
| `assets/` | 用例图、E-R 图、架构图源文件与 PNG | — |

图表全文统一编号（图 1-1、表 2-1 …）；实训报告与设计文档中的核心代码片段均附详细解释；报告不含任何时间性叙事。

## 9. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 迁移失败或数据错漏 | SQLite 只读保留；脚本事务 + 对账失败回滚；MySQL 库可整库重建 |
| `schema.sql` 与运行库漂移 | 从迁移后的真实库导出，而非按实体手写 |
| 演示依赖 LLM 外网 | 已入库 5 个项目支撑「浏览/复习/导出」类演示；重新生成类演示依赖 API 可用性 |
| Docker Compose 未实测 | 明确标注审查级验证，验收路径为本机 MySQL |
| 指导教师对技术栈疑问 | 文档中给出技术映射（Spring MVC 控制器 ↔ Servlet、JPA ↔ JDBC 封装），README 与答辩 PPT 同步 |

## 10. 验收标准

1. 后端无 SQLite 运行时依赖，可连接 MySQL 启动，核心 API 全部可用；
2. 现有后端测试与 AI 服务测试全绿；`npm run build` 与 lint 通过；
3. 迁移数据四表行数与主键范围一致，5 个项目可正常查看；
4. 五份课程材料齐全，实训报告结构符合指导书 §6，图表编号连续、核心代码有解释；
5. README、`.env.example`、`docker-compose.yml`、启动脚本与实现一致。
