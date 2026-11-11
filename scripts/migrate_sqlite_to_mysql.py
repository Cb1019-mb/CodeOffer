#!/usr/bin/env python3
"""SQLite(codeoffer.db) -> MySQL 一次性数据迁移。

用法:
  scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --dry-run
  scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --schema-out docs/database/schema.sql
  scripts/.venv/Scripts/python scripts/migrate_sqlite_to_mysql.py --force

约定:
  - 源库 data/codeoffer.db 只读打开,不做任何修改(回滚依据)
  - 目标四表须已由后端首次启动(ddl-auto=update)创建,缺表即失败退出
  - 目标四表任一非空时默认拒绝执行(避免误覆盖线上数据),确认要以 SQLite 快照
    覆盖现有数据时显式加 --force;--dry-run 不写入,不受此限制
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
    parser.add_argument("--force", action="store_true", help="目标表已有数据时仍以 SQLite 快照覆盖")
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

        # 破坏性写前置守卫: 目标表非空时默认拒绝,须显式 --force 才覆盖
        # (干跑不写入,保持原有校验行为,不受此守卫影响)
        if not args.dry_run and not args.force:
            with conn.cursor() as cur:
                for table in TABLES:
                    cur.execute(f"SELECT COUNT(*) FROM `{table}`")
                    if cur.fetchone()[0] > 0:
                        print(
                            f"[FAIL] 目标表 {table} 已有数据，如需覆盖请加 --force"
                            "（将以 SQLite 快照覆盖现有数据）"
                        )
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
