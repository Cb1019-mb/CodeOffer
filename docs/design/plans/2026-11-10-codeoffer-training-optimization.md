# CodeOffer 实训优化实现计划（MySQL 迁移 + 实训交付物）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 CodeOffer 的持久层从 SQLite 迁移到 MySQL 8（含存量业务数据迁移），并产出符合《软件开发综合实训》指导书要求的整套课程材料。

**Architecture:** 应用架构不变（React SPA → Spring Boot 4.1 后端 → FastAPI AI 服务），仅把 Java 后端的运行数据库从 SQLite 文件替换为 MySQL；测试继续使用 H2。课程材料（需求/设计/测试/报告/PPT）放在仓库外的 `D:\Internship Program\CodeOffer-实训材料\`，包含个人信息，不进入公开仓库。

**Tech Stack:** Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL 8.4 · H2（测试）· React 19 · FastAPI · Python 3.13 · Word/PPT 文档工具

**Spec:** `docs/design/specs/2026-11-10-codeoffer-training-optimization-design.md`

## Global Constraints

- 平台：Windows 11 + Git Bash；仓库根目录 `D:\Internship Program\CodeOffer`。
- 运行数据库唯一：MySQL 8（本机 Windows 服务名 `MySQL`，已在运行；客户端 `D:\Users\mysql8\bin\mysql.exe`）；库名 `codeoffer`，账号 `codeoffer_app`。
- 测试数据库：H2，配置文件 `backend/src/test/resources/application-test.yml`，不得改为连 MySQL。
- 禁止保留 SQLite 运行时依赖；`hibernate-community-dialects` 与 `sqlite-jdbc` 从 `pom.xml` 移除。
- 主键策略统一 `GenerationType.IDENTITY`；表字符集 `utf8mb4 / utf8mb4_unicode_ci`，引擎 InnoDB；**不新增物理外键**。
- `docs/database/schema.sql` 必须从迁移后的真实库导出（`SHOW CREATE TABLE`），不按实体手写猜测字段类型。
- 数据迁移：SQLite 源库只读打开；单事务写入；对账（行数 + MAX(id)）失败整体回滚；`HTE_*`、`*_seq` 表不迁移。
- 不新增任何自动化测试代码；黑盒用例通过 curl/前端手工执行并记录结果。
- 课程材料目录：`D:\Internship Program\CodeOffer-实训材料\`；文档不得包含任何「暑期原型 / 实训期优化」等时间性叙事。
- 图表全文统一编号（图 1-1、表 2-1…）；报告中的核心代码必须附逐段解释。
- 密码：不读取、不记录、不校验；只经 `config/.env`（gitignored）→ 环境变量注入。
- Git：只 `git add` 明确列出的文件；**不得** stage `backend/src/main/resources/static/` 下的既有删除/修改（与本次无关的工作区变更）。
- 提交信息遵循仓库约定：`<type>(<scope>): <subject>`，中文描述。

---

### Task 1: 切换到 MySQL 数据源（配置 + 实体 + 环境）

**Files:**
- Create: `scripts/setup-mysql.sql`
- Modify: `config/.env.example`
- Modify: `config/.env`（gitignored，用户填写密码）
- Modify: `backend/pom.xml:37-44`
- Modify: `backend/src/main/resources/application.yml`（全文重写）
- Modify: `backend/src/test/resources/application-test.yml`（补 H2 用户名）
- Modify: `backend/src/main/java/com/codeoffer/backend/project/{Project,KnowledgeCard,GenTask,ResumeEntry}.java`（各 1 行）

**Interfaces:**
- Produces: 环境变量键 `MYSQL_HOST`（默认 127.0.0.1）、`MYSQL_PORT`（3306）、`MYSQL_DB`（codeoffer）、`MYSQL_USER`（codeoffer_app）、`MYSQL_PASSWORD`；`spring.config.import` 读取 `../config/.env`；后端启动时以 `ddl-auto: update` 创建四张表。

- [ ] **Step 1: 创建建库建号脚本** `scripts/setup-mysql.sql`

```sql
-- CodeOffer 专用数据库与账号（密码请替换为强密码后执行）
-- 执行: "D:\Users\mysql8\bin\mysql.exe" -u root -p < scripts/setup-mysql.sql
CREATE DATABASE IF NOT EXISTS codeoffer CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'codeoffer_app'@'localhost' IDENTIFIED BY '<替换为密码>';
GRANT ALL PRIVILEGES ON codeoffer.* TO 'codeoffer_app'@'localhost';
FLUSH PRIVILEGES;
```

- [ ] **Step 2: 更新 `config/.env.example`，追加 MySQL 段**

```dotenv
# MySQL 连接（本机服务名 MySQL；密码不入库）
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=codeoffer
MYSQL_USER=codeoffer_app
MYSQL_PASSWORD=请填写
# 仅 Docker Compose 的 mysql 容器初始化使用
MYSQL_ROOT_PASSWORD=请填写
```

- [ ] **Step 3: 【用户操作，阻塞等待】建库并写入密码**

告知用户执行（Agent 不接触密码）：
1. 把 `scripts/setup-mysql.sql` 里 `<替换为密码>` 改成真实密码后执行：`"D:\Users\mysql8\bin\mysql.exe" -u root -p < scripts/setup-mysql.sql`
2. 把同一密码写入 `config/.env` 的 `MYSQL_PASSWORD=`（`MYSQL_ROOT_PASSWORD` 可留空，仅容器用到）。

等待用户回复「已配置」。

- [ ] **Step 4: 修改 `backend/pom.xml`**

删除这两个依赖节点：

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-community-dialects</artifactId>
</dependency>
```

在 `spring-boot-starter-data-jpa` 之后新增：

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

若构建报「缺少版本」，显式补 `<version>9.1.0</version>`（Boot 4.1 BOM 未托管时才需要）。

- [ ] **Step 5: 重写 `backend/src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: backend
  config:
    # 本地开发从 config/.env 读取 MYSQL_*（该文件不入库）；容器内由 env_file 注入，此处可缺省
    import: optional:file:../config/.env[.properties]
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DB:codeoffer}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: ${MYSQL_USER:codeoffer_app}
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        # Instant 统一按 UTC 存库，与迁移脚本的时间戳换算保持一致
        jdbc:
          time_zone: UTC
  mvc:
    static-path-pattern: /**

ai-service:
  url: ${AI_SERVICE_URL:http://127.0.0.1:8001}

management:
  endpoints:
    web:
      exposure:
        include: health,info

server:
  port: 8080
```

- [ ] **Step 6: 更新 `backend/src/test/resources/application-test.yml`**

完整替换为（补 H2 用户名，避免继承主配置的 MySQL 账号）：

```yaml
# 测试 profile:使用 H2 内存库,与运行库 MySQL 完全隔离
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
    hikari:
      connection-init-sql: "SET DEFAULT_LOCK_TIMEOUT 5000"
  jpa:
    hibernate:
      ddl-auto: create-drop

ai-service:
  url: http://127.0.0.1:8001
```

- [ ] **Step 7: 四个实体主键策略改 IDENTITY**

`Project.java`、`KnowledgeCard.java`、`GenTask.java`、`ResumeEntry.java` 各自把：

```java
@GeneratedValue(strategy = GenerationType.AUTO)
```

改为：

```java
@GeneratedValue(strategy = GenerationType.IDENTITY)
```

（`KnowledgeCard.java` 第 14 行注释里的「SQLite/H2 的 TEXT」改为「MySQL/H2 的文本列」，`Project.java` 第 14 行同理。）

- [ ] **Step 8: 跑 H2 回归**

Run: `cd backend && ./mvnw.cmd test`
Expected: BUILD SUCCESS，`Tests run: N, Failures: 0, Errors: 0`

- [ ] **Step 9: 启动后端验证**

Run: `cd backend && ./mvnw.cmd spring-boot:run`（后台）→ `curl -s http://127.0.0.1:8080/api/health`
Expected: HTTP 200，日志无数据源异常。四表是否创建成功由 Task 2 Step 3 的干跑检查确认（脚本会校验目标表存在），此处不执行需要交互输入密码的 mysql 命令。

- [ ] **Step 10: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml scripts/setup-mysql.sql config/.env.example backend/src/main/java/com/codeoffer/backend/project/Project.java backend/src/main/java/com/codeoffer/backend/project/KnowledgeCard.java backend/src/main/java/com/codeoffer/backend/project/GenTask.java backend/src/main/java/com/codeoffer/backend/project/ResumeEntry.java
git commit -m "feat(backend): 持久层切换 MySQL 8（IDENTITY 主键 + 配置注入）"
```

---

### Task 2: 数据迁移脚本 + 存量数据迁移 + schema.sql 导出

**Files:**
- Create: `scripts/migrate_sqlite_to_mysql.py`
- Create: `docs/database/schema.sql`（脚本导出）
- Modify: `.gitignore`（无需改动：`.venv/` 已忽略）

**Interfaces:**
- Consumes: Task 1 的 `config/.env`（MYSQL_*）与已建好的四表。
- Produces: CLI 参数 `--dry-run`（只校验不写入）、`--schema-out <path>`（导出建表语句）；退出码 0=成功、1=表缺失或对账失败。

- [ ] **Step 1: 编写迁移脚本** `scripts/migrate_sqlite_to_mysql.py`

```python
#!/usr/bin/env python3
"""SQLite(codeoffer.db) -> MySQL 一次性数据迁移。

用法:
  scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --dry-run
  scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --schema-out docs/database/schema.sql

约定:
  - 源库 data/codeoffer.db 只读打开,不做任何修改(回滚依据)
  - 目标四表须已由后端首次启动(ddl-auto=update)创建,缺表即失败退出
  - 单事务: 清空 -> 写入 -> 对账,任一步失败整体回滚
  - 时间戳: SQLite 存 epoch 毫秒(Hibernate SQLiteDialect),MySQL 存 UTC DATETIME
    (application.yml 已设 hibernate.jdbc.time_zone=UTC)
"""
import argparse
import os
import sqlite3
import sys
from datetime import datetime, timezone

import pymysql

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQLITE_PATH = os.path.join(ROOT, "data", "codeoffer.db")
ENV_PATH = os.path.join(ROOT, "config", ".env")

TABLES = ["project", "gen_task", "knowledge_card", "resume_entry"]
TIME_COLUMNS = {"created_at", "updated_at", "last_reviewed", "next_review"}


def load_env(path):
    env = {}
    if not os.path.exists(path):
        return env
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def convert(value, column):
    if value is None:
        return None
    if column in TIME_COLUMNS and isinstance(value, int):
        return datetime.fromtimestamp(value / 1000, tz=timezone.utc).replace(tzinfo=None)
    return value


def dump_schema(conn, path):
    lines = ["-- CodeOffer MySQL 建表语句（由迁移脚本从运行库导出）", "SET NAMES utf8mb4;", ""]
    with conn.cursor() as cur:
        for table in TABLES:
            cur.execute(f"SHOW CREATE TABLE `{table}`")
            ddl = cur.fetchone()[1]
            lines += [f"DROP TABLE IF EXISTS `{table}`;", ddl + ";", ""]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    print(f"[DONE] schema 已导出: {path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--schema-out", default=None)
    args = parser.parse_args()

    env = load_env(ENV_PATH)
    src = sqlite3.connect(f"file:{SQLITE_PATH}?mode=ro", uri=True)
    src.row_factory = sqlite3.Row
    conn = pymysql.connect(
        host=env.get("MYSQL_HOST", "127.0.0.1"),
        port=int(env.get("MYSQL_PORT", "3306")),
        user=env.get("MYSQL_USER", "codeoffer_app"),
        password=env.get("MYSQL_PASSWORD", ""),
        database=env.get("MYSQL_DB", "codeoffer"),
        charset="utf8mb4",
        autocommit=False,
    )
    try:
        with conn.cursor() as cur:
            for table in TABLES:
                cur.execute(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s",
                    (table,),
                )
                if cur.fetchone()[0] == 0:
                    print(f"[FAIL] 目标表 {table} 不存在: 请先启动一次后端以自动建表")
                    sys.exit(1)

        for table in TABLES:
            with conn.cursor() as cur:
                cur.execute(f"SHOW COLUMNS FROM `{table}`")
                mysql_cols = [row[0] for row in cur.fetchall()]
            sqlite_cols = [row[1] for row in src.execute(f"PRAGMA table_info({table})")]
            cols = [c for c in mysql_cols if c in sqlite_cols]
            rows = [
                tuple(convert(row[c], c) for c in cols)
                for row in src.execute(f"SELECT {','.join(cols)} FROM {table} ORDER BY id")
            ]
            print(f"[{'DRY' if args.dry_run else 'LOAD'}] {table}: {len(rows)} 行, {len(cols)} 列")
            if args.dry_run:
                continue
            with conn.cursor() as cur:
                cur.execute(f"DELETE FROM `{table}`")
                placeholders = ",".join(["%s"] * len(cols))
                col_sql = ",".join(f"`{c}`" for c in cols)
                cur.executemany(
                    f"INSERT INTO `{table}` ({col_sql}) VALUES ({placeholders})", rows
                )

        # 对账(同一事务内可见未提交数据)
        ok = True
        for table in TABLES:
            src_count = src.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            src_max = src.execute(f"SELECT COALESCE(MAX(id),0) FROM {table}").fetchone()[0]
            with conn.cursor() as cur:
                cur.execute(f"SELECT COUNT(*), COALESCE(MAX(id),0) FROM `{table}`")
                dst_count, dst_max = cur.fetchone()
            matched = src_count == dst_count and src_max == dst_max
            ok = ok and matched
            print(f"[{'OK' if matched else 'FAIL'}] {table}: 源 {src_count}/{src_max} 目标 {dst_count}/{dst_max}")

        if args.dry_run:
            print("[DONE] 干跑校验通过（未写入）")
            return
        if not ok:
            conn.rollback()
            print("[FAIL] 对账不一致,已回滚")
            sys.exit(1)
        conn.commit()
        print("[DONE] 迁移提交完成")
        if args.schema_out:
            dump_schema(conn, os.path.join(ROOT, args.schema_out))
    except Exception:
        conn.rollback()
        raise
    finally:
        src.close()
        conn.close()


if __name__ == "__main__":
    main()
```

> 说明：不显式执行 `ALTER TABLE ... AUTO_INCREMENT = N` —— InnoDB 在显式插入较大主键时会自动推进自增计数器；且 DDL 会隐式提交，破坏单事务原子性。

- [ ] **Step 2: 准备脚本虚拟环境**

Run: `python -m venv scripts/.venv && scripts/.venv/Scripts/pip install pymysql`
Expected: `Successfully installed pymysql-*`

- [ ] **Step 3: 干跑校验**

Run: `scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --dry-run`
Expected: 四表分别输出 `DRY ... 5/21/30/5 行`，结尾 `[DONE] 干跑校验通过`

- [ ] **Step 4: 正式迁移并导出 schema**

Run: `scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --schema-out docs/database/schema.sql`
Expected: 四表 `[OK]` 对账一致，`[DONE] 迁移提交完成`，`[DONE] schema 已导出`

- [ ] **Step 5: 重启后端验证数据与时间戳**

Run: `curl -s http://127.0.0.1:8080/api/projects | scripts/.venv/Scripts/python -c "import sys,json;d=json.load(sys.stdin);print(len(d));print(d[0])"`
Expected: 数量为 5；`updatedAt` 为 ISO 时间。
**时间戳核对**：取任一项目，比较 API 的 `updatedAt` 与源库值：

```bash
python -c "import sqlite3;print(sqlite3.connect('file:data/codeoffer.db?mode=ro',uri=True).execute('SELECT updated_at FROM project ORDER BY id LIMIT 1').fetchone()[0])"
```

把 API 时间串按 UTC 解析回 epoch 毫秒，与源值误差应 < 1000ms。若整体偏移 ±8 小时，说明 `hibernate.jdbc.time_zone=UTC` 未生效或不匹配——检查 application.yml 后重跑迁移（先 `DELETE` 四表再执行脚本即可）。

- [ ] **Step 6: 核对 schema.sql**

Run: `grep -c "CREATE TABLE" docs/database/schema.sql`
Expected: `4`；抽查包含 `AUTO_INCREMENT`、`utf8mb4`、无 `sqlite` 字样。

- [ ] **Step 7: 提交**

```bash
git add scripts/migrate_sqlite_to_mysql.py docs/database/schema.sql
git commit -m "feat(data): SQLite 存量数据迁移脚本与 MySQL 建表语句"
```

---

### Task 3: Docker Compose、启动脚本与 README 对齐

**Files:**
- Modify: `docker-compose.yml`
- Modify: `scripts/dev-up.ps1:1-12`
- Modify: `README.md`（技术栈表、环境要求、配置、Docker、项目结构）

**Interfaces:**
- Consumes: Task 1 的环境变量契约。
- Produces: `docker compose config` 可解析；`dev-up.ps1` 在 MySQL 服务未运行时自动拉起。

- [ ] **Step 1: 重写 `docker-compose.yml`**

```yaml
services:
  mysql:
    image: mysql:8.4
    container_name: codeoffer-mysql
    env_file:
      # 提供 MYSQL_USER / MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD
      - path: ./config/.env
        required: false
    environment:
      - MYSQL_DATABASE=${MYSQL_DB:-codeoffer}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u$$MYSQL_USER -p$$MYSQL_PASSWORD"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 30s
    restart: unless-stopped

  ai-service:
    build: ./ai-service
    container_name: codeoffer-ai
    ports:
      - "${AI_SERVICE_PORT:-8001}:8001"
    env_file:
      - path: ./config/.env
        required: false
    volumes:
      - ./data/repos:/data/repos
    environment:
      - REPO_DATA_DIR=/data/repos
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8001/api/health', timeout=2).status==200 else 1)"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
    restart: unless-stopped

  backend:
    build: ./backend
    container_name: codeoffer-backend
    ports:
      - "${BACKEND_PORT:-8080}:8080"
    env_file:
      - path: ./config/.env
        required: false
    depends_on:
      ai-service:
        condition: service_healthy
      mysql:
        condition: service_healthy
    environment:
      - AI_SERVICE_URL=http://ai-service:8001
      - DATA_DIR=/data
      - MYSQL_HOST=mysql
      - MYSQL_PORT=3306
    volumes:
      - ./data:/data
    restart: unless-stopped

volumes:
  mysql-data:
```

- [ ] **Step 2: 在 `scripts/dev-up.ps1` 的 `# 0) 清理旧服务进程` 之前插入 MySQL 服务检测**

```powershell
# 0) 确认本机 MySQL 服务可用(应用默认连接 127.0.0.1:3306)
$mysqlSvc = Get-Service -Name "MySQL" -ErrorAction SilentlyContinue
if (-not $mysqlSvc) {
    Write-Host "[FAIL] 未检测到 MySQL Windows 服务(服务名应为 MySQL),请先安装/启动 MySQL 8" -ForegroundColor Red
    exit 1
}
if ($mysqlSvc.Status -ne "Running") {
    Write-Host "[0/4] 启动 MySQL 服务..." -ForegroundColor Cyan
    Start-Service -Name "MySQL"
    $mysqlSvc.WaitForStatus("Running", [TimeSpan]::FromSeconds(20))
}
```

并把后续 `[1/3]`、`[2/3]`、`[3/3]` 改为 `[1/4]`、`[2/4]`、`[3/4]`。

- [ ] **Step 3: 更新 README 关键段落**

- 技术栈表：`Spring Data JPA · SQLite (Xerial JDBC)` → `Spring Data JPA · MySQL 8.4（mysql-connector-j；测试用 H2）`；
- 环境要求表：新增 `MySQL | 8.0+（本机服务名 MySQL，默认端口 3306）`；
- 3.2 配置：`.env` 示例追加 `MYSQL_HOST/MYSQL_PORT/MYSQL_DB/MYSQL_USER/MYSQL_PASSWORD`；
- 3.5 Docker：说明新增 `mysql` 服务、`mysql-data` 数据卷、backend 等待 mysql 健康后启动；
- 项目结构：追加 `scripts/migrate_sqlite_to_mysql.py`、`scripts/setup-mysql.sql`、`docs/database/schema.sql`；
- 常见问题：新增「端口 3306 被占用/服务未启动 → `net start MySQL`」。

- [ ] **Step 4: 校验 compose 语法（无需 daemon）**

Run: `docker compose config --quiet`
Expected: 退出码 0（若 daemon 未运行仍可解析）。

- [ ] **Step 5: 实测一键启动脚本**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-up.ps1`（Git Bash 下用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/dev-up.ps1`）
Expected: 输出 4 步日志；随后 `curl -s http://127.0.0.1:8080/api/health` 与 `curl -s http://127.0.0.1:8001/api/health` 均 200。

- [ ] **Step 6: 提交**

```bash
git add docker-compose.yml scripts/dev-up.ps1 README.md
git commit -m "feat(infra): Compose 增加 MySQL 服务并更新启动脚本与 README"
```

---

### Task 4: 图表资产（用例图 / E-R 图 / 架构图 / 状态机图）

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\assets\generate_diagrams.py`
- Create: `assets\{usecase,er,architecture,state}.svg` 与同名 `.png`

**Interfaces:**
- Produces: 四张 2x 分辨率 PNG，供 Task 5–8 的 docx/pptx 插入；SVG 源文件同目录保存。

- [ ] **Step 1: 编写 SVG 生成脚本** `assets/generate_diagrams.py`

```python
#!/usr/bin/env python3
"""生成实训文档图表 SVG。渲染: Chrome headless 截图(见 Task 4 Step 3)。"""
import os

OUT = os.path.dirname(os.path.abspath(__file__))
FONT = "Microsoft YaHei, SimHei, sans-serif"


def svg(w, h, body):
    defs = ('<defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="3" '
            'orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#555"/></marker></defs>')
    style = (f"<style>text{{font-family:{FONT};font-size:15px;fill:#222}}"
             ".t{font-weight:bold} .sm{font-size:12px;fill:#555}"
             ".ln{font-size:11px;fill:#444}</style>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}"><rect width="100%" height="100%" fill="#ffffff"/>'
            + defs + style + body + "</svg>")


def box(x, y, w, h, label, fill="#eef3fb", stroke="#4a6fa5", rx=8):
    lines = label.split("\n")
    start = y + h / 2 - (len(lines) - 1) * 9
    tspans = "".join(
        f'<tspan class="t" x="{x + w / 2}" y="{start + index * 18}">{text}</tspan>'
        for index, text in enumerate(lines))
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="1.5"/>'
            f'<text text-anchor="middle" dominant-baseline="middle">{tspans}</text>')


def ellipse(cx, cy, rx, ry, label):
    return (f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#fdf6ec" '
            f'stroke="#8a6d3b" stroke-width="1.5"/>'
            f'<text x="{cx}" y="{cy}" text-anchor="middle" dominant-baseline="middle">{label}</text>')


def line(x1, y1, x2, y2, arrow=True, dash=False, label=""):
    marker = ' marker-end="url(#a)"' if arrow else ""
    dash_attr = ' stroke-dasharray="6 4"' if dash else ""
    lab = (f'<text class="sm" x="{(x1 + x2) / 2}" y="{(y1 + y2) / 2 - 6}" '
           f'text-anchor="middle">{label}</text>') if label else ""
    return (f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#555" '
            f'stroke-width="1.5"{dash_attr}{marker}/>{lab}')


def actor(cx, cy, name):
    body = (f'<circle cx="{cx}" cy="{cy - 26}" r="9" fill="none" stroke="#333" stroke-width="2"/>'
            f'<line x1="{cx}" y1="{cy - 17}" x2="{cx}" y2="{cy + 8}" stroke="#333" stroke-width="2"/>'
            f'<line x1="{cx - 15}" y1="{cy - 8}" x2="{cx + 15}" y2="{cy - 8}" stroke="#333" stroke-width="2"/>'
            f'<line x1="{cx}" y1="{cy + 8}" x2="{cx - 12}" y2="{cy + 30}" stroke="#333" stroke-width="2"/>'
            f'<line x1="{cx}" y1="{cy + 8}" x2="{cx + 12}" y2="{cy + 30}" stroke="#333" stroke-width="2"/>')
    text = (f'<text class="t" x="{cx}" y="{cy + 50}" text-anchor="middle">{name}</text>')
    return body + text


def table_box(x, y, w, title, rows):
    height = 34 + 18 * len(rows) + 8
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{height}" rx="4" fill="#ffffff" '
             f'stroke="#4a6fa5" stroke-width="1.5"/>',
             f'<rect x="{x}" y="{y}" width="{w}" height="30" rx="4" fill="#4a6fa5"/>',
             f'<text class="t" x="{x + w / 2}" y="{y + 15}" text-anchor="middle" '
             f'dominant-baseline="middle" fill="#fff">{title}</text>']
    for index, row in enumerate(rows):
        parts.append(f'<text class="ln" x="{x + 10}" y="{y + 48 + 18 * index}">{row}</text>')
    return "".join(parts), height


def write(name, content, w, h):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(svg(w, h, content))
    print(f"[OK] {path}  ({w}x{h})")


def usecase():
    w, h = 1200, 820
    body = [f'<rect x="270" y="30" width="620" height="760" rx="10" fill="none" '
            f'stroke="#999" stroke-dasharray="8 6"/>',
            f'<text class="t" x="580" y="60" text-anchor="middle">CodeOffer 平台</text>',
            actor(120, 420, "求职者 / 用户"),
            actor(1080, 140, "大模型服务"),
            ellipse(580, 110, 170, 30, "生成知识产物"),
            ellipse(580, 190, 150, 28, "录入仓库项目"),
            ellipse(580, 260, 150, 28, "查看生成进度"),
            ellipse(580, 330, 150, 28, "浏览知识卡片"),
            ellipse(580, 400, 150, 28, "编辑 / 采纳卡片"),
            ellipse(580, 470, 150, 28, "自测与复习"),
            ellipse(580, 540, 170, 28, "编辑简历与面试题"),
            ellipse(580, 610, 150, 28, "确认成果入库"),
            ellipse(580, 680, 150, 28, "删除项目"),
            line(1080, 155, 760, 120, arrow=False, label="调用"),
            line(140, 420, 410, 190, arrow=False),
            line(140, 420, 410, 260, arrow=False),
            line(140, 420, 410, 330, arrow=False),
            line(140, 420, 410, 400, arrow=False),
            line(140, 420, 410, 470, arrow=False),
            line(140, 420, 430, 540, arrow=False),
            line(140, 420, 430, 610, arrow=False),
            line(140, 420, 430, 680, arrow=False)]
    write("usecase.svg", "".join(body), w, h)


def er():
    w, h = 1200, 940
    body = []
    p, ph = table_box(40, 360, 300, "project 项目", [
        "PK id  BIGINT", "repo_url  VARCHAR", "name  VARCHAR", "description  VARCHAR",
        "tech_stack  VARCHAR", "status  VARCHAR", "health_report  VARCHAR",
        "repo_info  文本", "repo_dir  VARCHAR", "created_at  DATETIME",
        "updated_at  DATETIME"])
    t, th = table_box(780, 30, 360, "gen_task 生成任务", [
        "PK id  BIGINT", "FK project_id  BIGINT", "type  VARCHAR",
        "status  VARCHAR", "progress  INT", "error_msg  VARCHAR",
        "created_at  DATETIME", "updated_at  DATETIME"])
    c, ch = table_box(780, 330, 360, "knowledge_card 知识卡片", [
        "PK id  BIGINT", "FK project_id  BIGINT", "type  VARCHAR", "title  VARCHAR",
        "content  文本", "ai_draft  文本", "user_input  文本", "edited  BIT",
        "mastery  INT", "last_reviewed  DATETIME", "next_review  DATETIME",
        "created_at  DATETIME", "updated_at  DATETIME"])
    r, rh = table_box(780, 700, 360, "resume_entry 简历条目", [
        "PK id  BIGINT", "FK project_id  BIGINT", "star_content  文本",
        "interview_qas  文本", "ai_draft  文本", "edited  BIT",
        "created_at  DATETIME", "updated_at  DATETIME"])
    body += [p, t, c, r]
    body += [line(340, 400, 780, 90, label="1 : N"),
             line(340, 440, 780, 400, label="1 : N"),
             line(340, 480, 780, 760, label="1 : N")]
    write("er.svg", "".join(body), w, h)


def architecture():
    w, h = 1200, 780
    body = [box(60, 320, 180, 80, "浏览器"),
            box(300, 310, 220, 100, "React SPA\n(生产由后端托管)"),
            box(600, 300, 260, 120, "Spring Boot 后端 :8080\n状态机 / 业务编排"),
            box(940, 320, 200, 80, "MySQL 8\n(持久化)"),
            box(600, 540, 260, 110, "FastAPI AI 服务 :8001\n仓库抓取 / LLM 网关"),
            box(940, 550, 200, 80, "大模型 API\n(OpenAI 兼容)"),
            line(240, 360, 300, 360),
            line(520, 360, 600, 360),
            line(860, 360, 940, 360, label="JPA"),
            line(730, 420, 730, 540, label="HTTP"),
            line(860, 595, 940, 590, label="HTTPS"),
            box(60, 120, 180, 60, "data/repos\n克隆缓存")]
    write("architecture.svg", "".join(body), w, h)


def state():
    w, h = 1200, 420
    stages = ["PENDING", "CLONING", "ANALYZING", "GENERATING", "REVIEW", "DONE"]
    body = []
    for index, name in enumerate(stages):
        body.append(box(30 + index * 190, 140, 150, 60, name, fill="#e8f4ea", stroke="#3c7a4a"))
    for index in range(5):
        body.append(line(180 + index * 190, 170, 220 + index * 190, 170))
    body.append(box(500, 300, 190, 60, "FAILED", fill="#fbeaea", stroke="#a94442"))
    body.append(line(675, 200, 675, 300, dash=True, label="任意失败"))
    write("state.svg", "".join(body), w, h)


if __name__ == "__main__":
    usecase()
    er()
    architecture()
    state()
```

- [ ] **Step 2: 生成 SVG 并目视检查**

Run: `python "D:\Internship Program\CodeOffer-实训材料\assets\generate_diagrams.py"`
Expected: 4 个 `[OK]`。
用 Read 工具打开每个 SVG 检查文字是否溢出/重叠；如溢出调整坐标后重新生成。

- [ ] **Step 3: 用 Chrome headless 渲染 PNG（2x）**

```bash
CHROME="/c/Program Files/Google/Chrome/Application/chrome.exe"
A="file:///D:/Internship%20Program/CodeOffer-%E5%AE%9E%E8%AE%AD%E6%9D%90%E6%96%99/assets"
OUT="D:\\Internship Program\\CodeOffer-实训材料\\assets"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=1200,820 --screenshot="$OUT\\usecase.png" "$A/usecase.svg"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=1200,940 --screenshot="$OUT\\er.png" "$A/er.svg"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=1200,780 --screenshot="$OUT\\architecture.png" "$A/architecture.svg"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=1200,420 --screenshot="$OUT\\state.png" "$A/state.svg"
```

Expected: 生成 4 个 PNG；`file "$OUT"/usecase.png` 显示约 `2400x1640`。
用 Read 工具查看 PNG 确认无空白/裁切（窗口尺寸须等于 SVG 尺寸）。

- [ ] **Step 4: 备份源文件清单**

Run: `ls -la "D:\Internship Program\CodeOffer-实训材料\assets"`
Expected: 4 SVG + 4 PNG + 1 py。

---

### Task 5: 01-需求分析说明书.docx

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\01-需求分析说明书.docx`

**Interfaces:**
- Consumes: Task 4 的 `assets/usecase.png`；控制器的 URL 白名单与校验规则（`ProjectController.validateRepoUrl`）；状态机枚举（`ProjectStatus`）。

- [ ] **Step 1: 用 office-docx 技能创建空白文档**

调用 `create_document`：`path=...01-需求分析说明书.docx`、`expectedRevision=0`，记录返回的 revision。

- [ ] **Step 2: 分批插入正文（`insert_content`，受限 HTML）**

章节结构与要点（全部为确定性内容，直接写入，不留占位符）：

1. **项目概述**：CodeOffer 面向「读懂并讲出一个开源项目」场景，输入受信任平台仓库地址，生成知识卡片、体检报告、流程图、STAR 简历与模拟面试题。
2. **选题背景与意义**：求职者项目复盘成本高、开源仓库学习门槛高；平台把「读代码 → 提炼 → 自测 → 简历表达」闭环化。意义落在工程能力（双层服务、异步编排）与学习效率。
3. **技术选型**：表格（表 1-1）：前端 React 19 + TypeScript + Ant Design；后端 Java 21 + Spring Boot 4.1 + Spring Data JPA + MySQL 8；AI 服务 Python 3.13 + FastAPI；构建 Maven / npm；部署 Docker Compose。选型理由逐项写一句话。
4. **开发计划**：表格（表 1-2）：需求分析 → 数据库设计 → 前端实现 → 后端实现 → 前后端联调 → 系统测试 → 文档整理 → 验收答辩（与指导书七环节对应，不写日期/周次）。
5. **功能需求**：模块划分（项目管理 / 生成任务 / 知识卡片 / 简历与面试 / 复习调度 / 导出）；插入图 1-1 用例图（`insert_image`，`assets/usecase.png`，宽 ≤ 14.6cm）；三个用例描述表（表 1-3 录入项目、表 1-4 编辑采纳、表 1-5 自测复习），列为「用例名 / 参与者 / 前置条件 / 主流程 / 异常流程」。
6. **非功能需求**：安全（仅 https 受信任平台、拒绝 URL 凭证、密钥只走环境变量）、性能（异步生成不阻塞接口、列表页聚合查询）、易用性（进度轮询、草稿/定稿双轨不覆盖）、可维护性（前后端分层、测试覆盖）。
7. **业务规则**：状态机（PENDING→CLONING→ANALYZING→GENERATING→REVIEW→DONE，任意失败→FAILED）；REVIEW/DONE 可确认入库；非生成中状态可整仓重生成；已编辑卡片不被覆盖；掌握度 0–5。

- [ ] **Step 3: 保存并结构检查**

`save_document`（携带最新 revision/version）→ `check_document`。
Expected: 无结构错误；回读第 1 章与用例图表确认存在。

---

### Task 6: 02-系统设计说明书.docx

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\02-系统设计说明书.docx`

**Interfaces:**
- Consumes: `assets/architecture.png`、`assets/er.png`、`docs/database/schema.sql`、README API 表、`ApiViews.java`、`GenerateService.java`。

- [ ] **Step 1: 创建文档并插入以下章节**

1. **总体架构**（图 2-1 架构图）：浏览器 → React SPA → Spring Boot（8080）→ MySQL；后端 → FastAPI（8001）→ LLM API；克隆缓存 `data/repos`。文字说明双层服务职责边界（后端管业务与状态，AI 服务不触碰业务库）。
2. **模块设计**：后端 `project`（实体/仓库/控制器/视图）、`ai`（AiServiceClient / GenerateService / GenerationWriter）、`config`（异步、Web、SPA 回退）；前端页面（HomePage / ProjectPage）与组件（CardList / QuizMode / ResumeSection）；AI 服务（routes / prompts / services.repo / llm.gateway）。
3. **数据库设计**：图 2-2 E-R 图；表 2-1~2-4 四张表的字段（列：字段 / 类型 / 约束 / 说明，类型取自 `schema.sql`）；说明 1:N 逻辑关联与「不建物理外键、删除由业务层按序清理」的设计取舍；附 `schema.sql` 全文（代码块，小五号）。
4. **接口设计**：表 2-5 后端 API 清单（方法 / 路径 / 说明 / 主要错误码：400 校验失败、404 不存在、409 状态冲突）；表 2-6 AI 服务 API 清单；说明统一响应结构与分页/轮询约定。
5. **安全设计**：仓库地址校验（https-only、host 白名单含 github/gitee/gitlab/gitcode/bitbucket、拒绝 user:pass@）、密钥环境变量化、SPA 回退配置、删除接口事务化。
6. **前端设计**：页面流转、状态轮询、Markdown/Mermaid 渲染、导出工具；不在本文重复需求。
7. **部署设计**：本地三进程启动（MySQL 服务 + 后端 + AI 服务，前端构建后由后端托管）与 Docker Compose（mysql/ai-service/backend 三服务、健康检查依赖顺序、数据卷）。

- [ ] **Step 2: 保存 + `check_document` + 回读抽查图表编号**

Expected: 无结构错误；图 2-1、2-2 与表 2-1~2-6 编号连续无缺号。

---

### Task 7: 03-测试用例与测试报告.docx（含黑盒执行）

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\03-测试用例与测试报告.docx`

**Interfaces:**
- Consumes: 运行中的后端（Task 1/2 完成后的 MySQL 实例）、迁移后的 5 个项目数据。
- Produces: 执行记录（HTTP 状态码与实际结果）写入文档。

- [ ] **Step 1: 确认服务在跑**

Run: `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/health`
Expected: `200`（未运行则按 Task 3 Step 5 启动）。

- [ ] **Step 2: 按用例表逐条执行并记录**

用例表（表 3-1，列为 编号 / 用例 / 前置 / 步骤 / 预期 / 实际 / 结论）：

| 编号 | 用例 | 步骤（curl 摘要） | 预期 |
| --- | --- | --- | --- |
| TC-01 | 健康检查 | `GET /api/health` | 200 |
| TC-02 | 空 URL 录入 | `POST /api/projects {"repoUrl":""}` | 400 |
| TC-03 | 非 https 协议 | `POST {"repoUrl":"http://github.com/a/b"}` | 400 |
| TC-04 | 非信任域名 | `POST {"repoUrl":"https://example.com/a/b"}` | 400 |
| TC-05 | URL 携带凭证 | `POST {"repoUrl":"https://u:p@github.com/a/b"}` | 400 |
| TC-06 | 重复录入 | 用已迁移项目之一的 repoUrl `POST` | 409 |
| TC-07 | 项目列表 | `GET /api/projects` | 200，5 项 |
| TC-08 | 项目详情 | `GET /api/projects/<已有id>` | 200，含 cards |
| TC-09 | 不存在项目 | `GET /api/projects/999999` | 404 |
| TC-10 | 任务查询 | `GET /api/projects/<id>/task` | 200 或空 |
| TC-11 | 编辑卡片空内容 | `PUT .../cards/<cid>/content {"content":""}` | 400 |
| TC-12 | 编辑卡片合法 | `PUT .../content {"content":"测试定稿"}` 后恢复原文 | 200，edited=true |
| TC-13 | 采纳草稿 | `POST .../cards/<cid>/adopt` | 200，content==aiDraft |
| TC-14 | 自测掌握 | `POST .../review {"known":true}` | mastery+1，nextReview 后移 |
| TC-15 | 自测生疏 | `POST .../review {"known":false}` | mastery-2（下限 0），nextReview≈+60s |
| TC-16 | 复习队列 | `GET .../review-queue` | 200，≤20 条 |
| TC-17 | 重复确认入库 | `POST .../confirm`（DONE 项目） | 200，仍 DONE |
| TC-18 | 生成中确认 | 对 RUNNING 任务项目 `confirm` | 409 |
| TC-19 | 删除临时项目 | 创建 file:// 临时项目 → `DELETE` → `GET` | DELETE 200，GET 404 |
| TC-20 | 简历编辑空内容 | `PUT .../resume/star {"starContent":""}` | 400 |

执行要点：
- 已有 id 从 `GET /api/projects` 获取；卡片 id 从 `GET /api/projects/<id>` 的 `cards` 获取。
- TC-12 编辑真实卡片后，用原内容再 `PUT` 一次恢复，并在记录中注明「已恢复」。
- TC-18 若当前无 RUNNING 任务，标注「条件不满足，未执行」并说明原因，不得伪造结果。
- TC-19 用 `file://` 本地仓库（如 `file:///D:/Internship Program/CodeOffer`）创建，若 AI 服务/密钥不可用导致项目 FAILED 也可继续执行删除验证（删除不依赖生成成功）。

- [ ] **Step 3: 整理白盒覆盖说明**

写入：三个后端测试类（`BackendApplicationTests`、`GenerateFlowIntegrationTest`、`ProjectControllerTest`）与三个 AI 服务测试文件（`test_api.py`、`test_gateway.py`、`test_repo.py`）的覆盖对象；说明「H2 建库 + MockMvc 接口路径 + 生成流程集成」的测试分层；不虚构覆盖率数字，写明覆盖的是核心路径与校验分支。

- [ ] **Step 4: 汇总测试结果**

表 3-2：用例总数 / 通过 / 未通过 / 条件未执行；列出执行中发现的缺陷（如有）与修复说明；结论段区分「白盒（自动化回归）」与「黑盒（手工用例）」。

- [ ] **Step 5: 创建文档、插入表格与结论，保存并 `check_document`**

Expected: 无结构错误；表 3-1 共 20 行数据。

---

### Task 8: 04-实训报告.docx

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\04-实训报告.docx`

**Interfaces:**
- Consumes: Task 4 图表（图 4-1 架构、图 4-2 E-R、图 4-3 状态机）、`assets/usecase.png`、Task 7 测试结果、`schema.sql`、核心源码文件。

- [ ] **Step 1: 按指导书结构创建文档**

结构（封面按指导书统一购买，文档内用一段说明替代，不设计封面样式）：
1. **目的及意义**
2. **实训任务**
3. **实训时间及地点**（仅写事实：校内专业实验室；日期留一行供填写）
4. **实训内容**
   - 4.1 开发环境（表 4-1：JDK 21 / Maven / Node.js / Python 3.13 / MySQL 8.4 / Git / IDEA / Chrome DevTools）
   - 4.2 需求分析（模块表 4-2 + 用例图复用 `usecase.png`，图 4-1）
   - 4.3 系统设计（图 4-2 架构、图 4-3 E-R、表 4-3 四表概要、状态机图 4-4）
   - 4.4 编码与实现（核心代码四段 + 逐段解释：①`GenerateService` 异步状态推进；②`ProjectController.reviewCard` 间隔复习算法；③`validateRepoUrl` 安全校验；④迁移脚本对账逻辑）
   - 4.5 系统测试（表 4-5 结果汇总 + 缺陷与修复）
   - 4.6 部署运行（三进程与 Docker Compose 两种方式）
5. **小结**（收获、问题与解决、改进方向）
6. **参考文献**（只列真实查阅的 4–6 条：Spring Boot 官方文档、Spring Data JPA 参考、FastAPI 文档、React 文档、MySQL 8.4 参考手册）

- [ ] **Step 2: 自动目录**

用 `insertToc` 操作在正文前插入 TOC 域，随后 `updateFields` 更新；说明「在 Word 中打开时如页码未刷新，按 Ctrl+A → F9 更新域」写进文档末尾备注或提交说明（不写进正文）。

- [ ] **Step 3: 图表编号与核心代码**

确保图/表编号连续（图 4-1…4-4，表 4-1…4-5）；核心代码用等宽字体、小五号，每段后跟 2–4 段解释（设计动机、关键参数、边界条件）。

- [ ] **Step 4: 保存 + `check_document` + 回读目录与代码块**

Expected: 无结构错误；目录含 6 个一级标题；四段代码存在。

---

### Task 9: 05-答辩PPT.pptx

**Files:**
- Create: `D:\Internship Program\CodeOffer-实训材料\05-答辩PPT.pptx`

**Interfaces:**
- Consumes: Task 4 图表、Task 7 测试结论、README 功能清单。

- [ ] **Step 1: 用 Presentations 技能建演示文稿（12 页）**

1. 封面（项目名、课程名；姓名/学号留空待填）
2. 选题背景与痛点（3 条）
3. 系统功能总览（录入 → 生成 → 审阅 → 复习 → 入库 流程图思路）
4. 技术架构（架构图 2-1 复用）
5. 数据库设计（E-R 图 + 4 表要点）
6. 关键实现一：异步生成状态机（状态图 + 要点）
7. 关键实现二：草稿/定稿双轨防覆盖（编辑标记机制）
8. 关键实现三：间隔复习算法（mastery 0–5 与复习间隔）
9. 安全设计（https 白名单、拒绝凭证、密钥环境变量）
10. 系统测试（20 条黑盒用例统计 + 自动化回归结果）
11. 技术难点与解决（SSRF 防护、LLM 失败重试与任务失败标记、迁移对账）
12. 总结与展望（可扩展方向 2–3 条）

- [ ] **Step 2: 保存并 `check_deck`**

Expected: 12 页、无溢出警告（如有则压缩文字）。

---

### Task 10: 总体验收

**Files:**
- Read-only verification; no new files.

- [ ] **Step 1: 代码回归**

Run: `cd backend && ./mvnw.cmd test` → 全绿；`cd ai-service && .venv/Scripts/python -m pytest -q` → 全绿；`cd frontend && npm run build && npm run lint` → 通过。

- [ ] **Step 2: 运行态验证**

`scripts/dev-up.ps1` 启动后：`/api/health` 200；`/api/projects` 5 项；任一项目详情卡片非空。
MySQL 内 `SELECT COUNT(*) FROM knowledge_card;` = 30。

- [ ] **Step 3: 文档验收**

对 4 个 docx 逐个 `check_document`（结构无错误）；PPT `check_deck`（12 页）；文件大小非 0；目录下文件齐全（01–05 + assets）。

- [ ] **Step 4: 仓库卫生**

`git status --short`：除既有 `backend/src/main/resources/static/` 变更外无未提交修改；`grep -ri sqlite README.md backend/pom.xml backend/src/main/resources/application.yml` 无输出。

- [ ] **Step 5: 汇总交付**

列出所有产物路径与验证结果；未实测项（Docker 运行、实验室环境）如实标注。
