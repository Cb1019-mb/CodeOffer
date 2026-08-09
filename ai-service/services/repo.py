"""仓库抓取与静态解析服务(GitPython)。

职责:
- clone_repo:浅克隆(--depth 1)到本地缓存目录,不触碰 LLM。
- analyze_repo:目录树、语言统计、核心文件启发式提取,作为 LLM 生成的结构化输入。

边界:本模块只做确定性工作,可独立单测;不发起任何模型调用。
"""
from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path

from git import GitCommandError, Repo

SKIP_DIRS = {
    ".git", "node_modules", ".venv", "venv", "__pycache__", "dist", "build",
    "target", ".idea", ".vscode", ".gradle", ".mvn", "site-packages",
}

CONFIG_FILES = {
    "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
    "requirements.txt", "pyproject.toml", "Cargo.toml", "go.mod",
    "composer.json", "Gemfile", "setup.py", "Dockerfile",
    "docker-compose.yml", "application.yml", "application.yaml", "application.properties",
}

ENTRY_PATTERNS = (
    r"^(src/main|main)[/\\].*\.(java|py|go|ts|js|kt|rs|rb|php|c|cpp|cs)$",
    r"^main\.(py|java|go|ts|js|kt|rs)$",
    r"^(app|server|index)\.(py|js|ts|go)$",
)

MAX_TREE_DEPTH = 4
MAX_TREE_ENTRIES = 60
MAX_STAT_FILES = 3000  # 超过则停止逐行统计,避免大仓库卡死
MAX_CORE_FILES = 20
MAX_REPO_BYTES = 100 * 1024 * 1024  # 100MB 提示阈值

# 常见克隆失败原因 → 友好中文提示(按关键词匹配 stderr)
NETWORK_HINTS = {
    "could not resolve host": "DNS 解析失败,请检查网络连接,或更换 DNS(如 223.5.5.5 / 114.114.114.114)",
    "schannel": "TLS 连接被中断,github 等境外站点可能被网络策略阻断,请配置代理或改用可访问的镜像地址",
    "certificate": "SSL 证书验证失败,请检查系统时间或代理证书,也可考虑 git -c http.sslVerify=false 临时绕过",
    "unable to access": "仓库地址不可达,请检查网络/代理,或改用可访问的镜像(如 gitee)",
    "connection was reset": "连接被重置,境外站点被网络策略阻断,请配置代理后重试",
    "timed out": "连接超时,请检查网络后重试",
    "authentication failed": "认证失败,私有仓库需要在 URL 中携带凭证",
    "repository not found": "仓库不存在或已被删除,请检查 URL 是否正确",
}

# 测试文件特征:对"核心文件"提取应降权(学习时优先看生产代码)
TEST_PATTERNS = (
    r"[/\\](test|tests|__tests__)[/\\]",
    r"(^|[/\\])(test_|.*_test)\.(py|go|js|ts)$",
    r"(^|[/\\]).*\.(spec|test)\.[jt]sx?$",
    r"src[/\\]test[/\\]",
    r".*Test\.java$",
)


class RepoError(Exception):
    """仓库抓取/解析失败。"""


def clone_repo(repo_url: str, dest_dir: str | os.PathLike, *, depth: int = 1) -> str:
    """浅克隆仓库到 dest_dir(目录名取 URL 的稳定 hash),返回实际仓库目录路径。

    已存在(缓存命中)时直接返回,不重复克隆。
    """
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    name = hashlib.sha1(repo_url.encode("utf-8")).hexdigest()[:12]
    target = dest / name
    if (target / ".git").exists():
        return str(target)
    try:
        Repo.clone_from(repo_url, str(target), depth=depth, single_branch=True)
    except GitCommandError as e:
        raise RepoError(friendly_clone_error(repo_url, e)) from e
    return str(target)


def friendly_clone_error(repo_url: str, err: GitCommandError) -> str:
    """把 git 原始报错翻译为友好中文提示(网络类问题给出解决建议)。"""
    raw = str(err)
    stderr = ""
    try:
        stderr = err.stderr.decode("utf-8", errors="ignore") if isinstance(err.stderr, bytes) else str(err.stderr)
    except Exception:
        stderr = ""
    combined = (raw + "\n" + stderr).lower()

    hints = []
    for keyword, hint in NETWORK_HINTS.items():
        if keyword in combined and hint not in hints:
            hints.append(hint)
    hint_text = "；".join(hints) or "请检查仓库地址与网络连接后重试"
    return f"克隆失败({repo_url}): {hint_text}\n原始错误: {stderr.strip()[:300]}"


def _walk(root: Path) -> list[Path]:
    """收集相对路径列表,过滤 SKIP_DIRS。"""
    rels: list[Path] = []
    for path in sorted(root.rglob("*")):
        try:
            rel = path.relative_to(root)
        except ValueError:
            continue
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if path.is_file():
            rels.append(rel)
    return rels


def _build_tree(root: Path, rels: list[Path]) -> list[dict]:
    """构建带深度限制的目录树(过滤 SKIP_DIRS,条目数截断)。"""
    tree: list[dict] = []
    counted = 0

    def walk_dir(rel: Path, depth: int) -> list[dict]:
        nonlocal counted
        entries: list[dict] = []
        for child in sorted(os.listdir(root / rel) if str(rel) != "." else os.listdir(root)):
            if child in SKIP_DIRS:
                continue
            child_rel = rel / child
            if child_rel in rels or (root / child_rel).is_dir():
                counted += 1
                if counted > MAX_TREE_ENTRIES:
                    entries.append({"name": "…(截断)"})
                    break
                if (root / child_rel).is_dir():
                    node: dict = {"name": child, "type": "dir"}
                    if depth < MAX_TREE_DEPTH:
                        node["children"] = walk_dir(child_rel, depth + 1)
                    entries.append(node)
                else:
                    entries.append({"name": child, "type": "file"})
        return entries

    for child in sorted(os.listdir(root)):
        if child in SKIP_DIRS:
            continue
        if (root / child).is_dir():
            counted += 1
            if counted > MAX_TREE_ENTRIES:
                tree.append({"name": "…(截断)"})
                break
            node = {"name": child, "type": "dir", "children": walk_dir(Path(child), 1)}
            tree.append(node)
        else:
            tree.append({"name": child, "type": "file"})
    return tree


def _language_stats(root: Path, rels: list[Path]) -> list[dict]:
    """按扩展名聚合文件数,粗略统计代码行数(有上限保护)。"""
    stats: dict[str, dict] = {}
    for rel in rels[:MAX_STAT_FILES]:
        ext = rel.suffix.lower() or "(no ext)"
        entry = stats.setdefault(ext, {"ext": ext, "files": 0, "lines": 0})
        entry["files"] += 1
        try:
            with (root / rel).open("r", encoding="utf-8", errors="ignore") as fh:
                entry["lines"] += sum(1 for _ in fh)
        except OSError:
            pass
    return sorted(stats.values(), key=lambda e: e["lines"], reverse=True)[:10]


def _core_files(root: Path, rels: list[Path]) -> list[dict]:
    """启发式挑选核心文件:README + 配置文件 + 主入口 + 行数 Top 文件。"""
    candidates: list[tuple[int, str]] = []  # (lines, rel_path)

    for rel in rels:
        name = rel.name
        lines = 0
        try:
            with (root / rel).open("r", encoding="utf-8", errors="ignore") as fh:
                lines = sum(1 for _ in fh)
        except OSError:
            continue
        candidates.append((lines, rel.as_posix()))

    scored: list[tuple[int, str]] = []
    for lines, rel in candidates:
        score = 0
        name = Path(rel).name
        if any(re.search(p, rel) for p in TEST_PATTERNS):
            score -= 150  # 测试文件大幅降权
        if name.upper().startswith("README"):
            score += 100
        if name in CONFIG_FILES:
            score += 80
        if any(re.match(p, rel) for p in ENTRY_PATTERNS):
            score += 70
        score += min(lines // 20, 50)  # 体量加分(封顶)
        scored.append((score, rel))

    scored.sort(key=lambda t: (-t[0], -len(t[1])))
    core: list[dict] = []
    for score, rel in scored[:MAX_CORE_FILES]:
        lines = next(l for l, r in candidates if r == rel)
        core.append({"path": rel, "lines": lines, "score": score})
    return core


def _dir_size(root: Path) -> int:
    total = 0
    try:
        for p in root.rglob("*"):
            if p.is_file():
                total += p.stat().st_size
    except OSError:
        pass
    return total


def analyze_repo(repo_dir: str | os.PathLike, *, max_files: int = MAX_CORE_FILES) -> dict:
    """解析仓库目录,产出结构化信息(目录树/语言/核心文件/统计)。"""
    root = Path(repo_dir)
    if not root.is_dir():
        raise RepoError(f"仓库目录不存在: {root}")

    rels = _walk(root)
    size = _dir_size(root)
    return {
        "root": str(root),
        "name": root.name,
        "tree": _build_tree(root, rels),
        "languages": _language_stats(root, rels),
        "core_files": _core_files(root, rels)[:max_files],
        "stats": {
            "file_count": len(rels),
            "size_bytes": size,
            "oversize": size > MAX_REPO_BYTES,
        },
    }
