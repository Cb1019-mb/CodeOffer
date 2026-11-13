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
import shutil
import threading
import uuid
from pathlib import Path
from urllib.parse import urlsplit

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

# 受信任的仓库托管平台(host 精确匹配或子域),拒绝其它 http/https 目标(防 SSRF)
ALLOWED_REPO_HOSTS = {
    "github.com", "gitee.com", "gitlab.com", "gitlab.cn", "gitcode.com",
    "bitbucket.org", "raw.githubusercontent.com",
}

# 每个 URL 一把锁:避免并发克隆同一仓库时互相删除对方结果
_clone_locks: dict[str, threading.Lock] = {}
_clone_locks_guard = threading.Lock()


def _lock_for(url_key: str) -> threading.Lock:
    with _clone_locks_guard:
        lock = _clone_locks.get(url_key)
        if lock is None:
            lock = threading.Lock()
            _clone_locks[url_key] = lock
        return lock


def _normalize_url(url: str) -> str:
    """缓存 key 归一化:去尾部斜杠与 .git 后缀、去 query/fragment。"""
    url = url.strip()
    url = url.split("?", 1)[0].split("#", 1)[0]
    while url.endswith("/"):
        url = url[:-1]
    if url.endswith(".git"):
        url = url[:-4]
    return url


def validate_repo_url(repo_url: str) -> None:
    """校验仓库地址:仅允许 https(受信任平台)或本地 file 仓库;拒绝携带凭证与其它协议。"""
    parts = urlsplit(repo_url)
    if parts.scheme == "file":
        return  # 本地仓库(测试/离线场景),路径可含空格等字符
    if parts.scheme != "https":
        raise RepoError("仅支持 https 仓库地址(或本地 file 仓库),当前协议不受支持")
    if parts.username or parts.password:
        raise RepoError("仓库地址不能携带凭证(user:pass@),请在托管平台单独配置凭证")
    host = (parts.hostname or "").lower()
    if not host or not any(host == h or host.endswith("." + h) for h in ALLOWED_REPO_HOSTS):
        raise RepoError(
            "仅支持受信任的仓库托管平台: " + " / ".join(sorted(ALLOWED_REPO_HOSTS))
        )


PROXY_SCHEMES = {"http", "https", "socks5", "socks5h"}


def validate_proxy(proxy: str) -> None:
    """校验克隆代理:仅 http/https/socks5(h),需主机名,拒绝凭证与路径/参数。"""
    try:
        parts = urlsplit(proxy)
        port = parts.port
    except ValueError as e:
        raise RepoError("代理地址格式不合法") from e
    if parts.scheme.lower() not in PROXY_SCHEMES:
        raise RepoError("代理仅支持 http / https / socks5 协议")
    if not parts.hostname:
        raise RepoError("代理地址需包含主机名,如 http://127.0.0.1:7890")
    if parts.username or parts.password:
        raise RepoError("代理地址不能携带凭证(user:pass@)")
    if port is not None and not 1 <= port <= 65535:
        raise RepoError("代理端口需为 1-65535")
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        raise RepoError("代理地址不应包含路径或参数")

# 常见克隆失败原因 → 友好中文提示(按关键词匹配 stderr)
NETWORK_HINTS = {
    "could not resolve host": "DNS 解析失败,请检查网络连接,或更换 DNS(如 223.5.5.5 / 114.114.114.114)",
    "schannel": "TLS 连接被中断,github 等境外站点可能被网络策略阻断,请在 设置 → 生成偏好 配置网络代理,或改用可访问的镜像地址",
    "certificate": "SSL 证书验证失败,请检查系统时间或代理证书,也可考虑 git -c http.sslVerify=false 临时绕过",
    "unable to access": "仓库地址不可达,请在 设置 → 生成偏好 配置网络代理,或改用可访问的镜像(如 gitee)",
    "connection was reset": "连接被重置,境外站点被网络策略阻断,请在 设置 → 生成偏好 配置网络代理后重试",
    "timed out": "连接超时,请检查网络后重试",
    "authentication failed": "认证失败,私有仓库请先在托管平台/本机配置访问凭证",
    "repository not found": "仓库不存在或已被删除,请检查 URL 是否正确",
}

# 扩展名 → 展示名(技术栈主语言用)
LANG_NAMES = {
    ".py": "Python", ".go": "Go", ".js": "JavaScript", ".ts": "TypeScript",
    ".java": "Java", ".kt": "Kotlin", ".rs": "Rust", ".rb": "Ruby",
    ".php": "PHP", ".c": "C", ".cpp": "C++", ".cs": "C#", ".swift": "Swift",
    ".html": "HTML", ".css": "CSS", ".vue": "Vue", ".jsx": "React", ".tsx": "React",
    ".sql": "SQL", ".md": "Markdown", ".sh": "Shell", ".yml": "YAML", ".json": "JSON",
}

# 计入技术栈主语言的扩展名(真正的编程语言;png/txt/pdf/ipynb 等素材文件不计入)
CODE_LANG_EXTS = {".py", ".go", ".js", ".ts", ".java", ".kt", ".rs", ".rb", ".php", ".c", ".cpp", ".cs", ".swift", ".vue", ".jsx", ".tsx", ".sql", ".html", ".css", ".sh"}

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


def clone_repo(
    repo_url: str,
    dest_dir: str | os.PathLike,
    *,
    depth: int = 1,
    proxy: str | None = None,
) -> str:
    """浅克隆仓库到 dest_dir(目录名取 URL 的稳定 hash),返回实际仓库目录路径。

    已存在(缓存命中)时直接返回,不重复克隆。
    并发安全:同 URL 通过锁串行化;克隆在临时目录完成后原子改名为目标,避免半成品污染缓存。
    proxy 非空时经其克隆(git/curl 读 *_PROXY 环境变量),用于访问境外仓库。
    """
    repo_url = repo_url.strip()
    validate_repo_url(repo_url)
    proxy = (proxy or "").strip()
    if proxy:
        validate_proxy(proxy)
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    key = _normalize_url(repo_url)
    name = hashlib.sha1(key.encode("utf-8")).hexdigest()[:12]
    target = dest / name
    marker = target / ".codeoffer-ok"  # 克隆成功标记,避免把残留半成品当缓存
    if marker.exists():
        return str(target)

    lock = _lock_for(key)
    with lock:
        if marker.exists():
            return str(target)
        if target.exists():
            # 上次失败的残留,锁内清理(同 URL 才可能进入此分支)
            shutil.rmtree(target, ignore_errors=True)
        staging = dest / f"{name}.tmp-{uuid.uuid4().hex[:8]}"
        # env 是整体替换而非合并,必须带上 os.environ(Windows 上丢失 PATH/SystemRoot 会找不到 git)
        env = {
            **os.environ,
            "GIT_HTTP_LOW_SPEED_LIMIT": "1000",
            "GIT_HTTP_LOW_SPEED_TIME": "60",
        }
        if proxy:
            # git(libcurl)对 http_proxy 只认小写、https_proxy 大小写均可;统一双写确保生效
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                env[key] = proxy
            # 用户 git 全局/系统配置中的 http.proxy 或 http.<url>.proxy 优先级高于环境变量,
            # 陈旧代理会覆盖本设置;GIT_CONFIG_* 等价 -c(最高优先级),显式覆盖通用与按主机两条键。
            parts = urlsplit(repo_url)
            cfg = {"http.proxy": proxy}
            if parts.scheme in ("http", "https") and parts.hostname:
                cfg[f"http.{parts.scheme}://{parts.hostname}.proxy"] = proxy
            env["GIT_CONFIG_COUNT"] = str(len(cfg))
            for i, (k, v) in enumerate(cfg.items()):
                env[f"GIT_CONFIG_KEY_{i}"] = k
                env[f"GIT_CONFIG_VALUE_{i}"] = v
        try:
            Repo.clone_from(
                repo_url,
                str(staging),
                depth=depth,
                single_branch=True,
                env=env,
            )
            shutil.move(str(staging), str(target))
            marker.write_text("ok", encoding="utf-8")
        except Exception as e:
            shutil.rmtree(staging, ignore_errors=True)
            raise RepoError(friendly_clone_error(e)) from e
    return str(target)


def friendly_clone_error(err: Exception) -> str:
    """把 git 原始报错翻译为友好中文提示;不回显原始 stderr 与 URL(防内网探测 oracle)。"""
    raw = str(err)
    stderr = ""
    try:
        stderr = (
            err.stderr.decode("utf-8", errors="ignore")
            if isinstance(err.stderr, bytes)
            else str(err.stderr)
        )
    except Exception:
        stderr = ""
    combined = (raw + "\n" + stderr).lower()

    hints: list[str] = []
    for keyword, hint in NETWORK_HINTS.items():
        if keyword in combined and hint not in hints:
            hints.append(hint)
    hint_text = "；".join(hints) or "请检查仓库地址与网络连接后重试"
    return f"克隆失败: {hint_text}"


def _walk(root: Path) -> list[Path]:
    """收集相对路径列表,过滤 SKIP_DIRS 与符号链接(防 symlink 穿越读取宿主文件)。"""
    rels: list[Path] = []
    for path in sorted(root.rglob("*")):
        try:
            rel = path.relative_to(root)
        except ValueError:
            continue
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if path.is_symlink():
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
            child_path = root / child_rel
            if child_path.is_symlink():
                continue
            if child_rel in rels or child_path.is_dir():
                counted += 1
                if counted > MAX_TREE_ENTRIES:
                    entries.append({"name": "…(截断)"})
                    break
                if child_path.is_dir():
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
        child_path = root / child
        if child_path.is_symlink():
            continue
        if child_path.is_dir():
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
            if p.is_symlink():
                continue
            if p.is_file():
                total += p.stat().st_size
    except OSError:
        pass
    return total


def _extract_description(root: Path, rels: list[Path]) -> str:
    """从 README 提取一句话描述:第一个非空的正文段落(跳过标题/徽章/空行)。"""
    for rel in rels:
        if rel.name.upper().startswith("README"):
            try:
                with (root / rel).open("r", encoding="utf-8", errors="ignore") as fh:
                    for line in fh:
                        line = line.strip()
                        if not line or line.startswith(("#", "!", "[", "<")):
                            continue
                        # [text](url) → text,去掉多余空白
                        cleaned = re.sub(r"\[([^]]+)]\([^)]*\)", r"\1", line)
                        cleaned = re.sub(r"\s+", " ", cleaned).strip()
                        if len(cleaned) >= 15:
                            return cleaned[:200]
            except OSError:
                continue
    return ""


def _parse_deps(root: Path, rels: list[Path]) -> list[str]:
    """从常见依赖清单文件提取技术名,核心依赖在前、开发依赖在后(去重保序)。"""
    deps: list[str] = []
    for rel in rels:
        name = rel.name
        try:
            text = (root / rel).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if name == "package.json":
            import json as _json

            try:
                data = _json.loads(text)
            except Exception:
                continue
            for key in ("dependencies", "devDependencies"):
                for dep in (data.get(key) or {}):
                    deps.append(dep if key == "dependencies" else dep + "(dev)")
        elif name == "requirements.txt":
            for line in text.splitlines():
                line = line.strip()
                if not line or line.startswith(("#", "-", "git+", "http")):
                    continue
                deps.append(re.split(r"[=<>!~]", line)[0].strip())
        elif name == "pom.xml":
            for m in re.findall(r"<artifactId>([^<]+)</artifactId>", text)[:15]:
                deps.append(m)
        elif name == "go.mod":
            for line in text.splitlines():
                m = re.match(r"^\s*([\w.\-]+/)+([\w.\-]+)\s+v", line)
                if m:
                    deps.append(m.group(2))
        elif name in ("pyproject.toml", "Cargo.toml"):
            in_deps = False
            for line in text.splitlines():
                s = line.strip()
                if s.startswith("[") and "depend" in s.lower():
                    in_deps = True
                    continue
                if s.startswith("[") and in_deps:
                    in_deps = False
                if in_deps and s and not s.startswith(("#", "[")):
                    deps.append(s.split("=")[0].strip().strip('"'))
        elif name in ("build.gradle", "build.gradle.kts"):
            for m in re.findall(r"['\"]([\w.\-]+:[\w.\-]+)[:'\"]", text)[:10]:
                deps.append(m.split(":")[-1])
    seen: set[str] = set()
    ordered: list[str] = []
    for d in deps:
        if d and d not in seen:
            seen.add(d)
            ordered.append(d)
    return ordered


def _tech_stack(root: Path, rels: list[Path], languages: list[dict]) -> list[str]:
    """技术栈(按项目使用主次排列):主语言(按代码行数,仅编程语言)→ 核心依赖 → 开发依赖。"""
    langs = [
        LANG_NAMES.get(lang["ext"], lang["ext"].lstrip("."))
        for lang in languages
        if lang["ext"] in CODE_LANG_EXTS
    ][:3]
    deps = _parse_deps(root, rels)
    merged = langs + deps
    return merged[:10]


def _is_within(path: Path, base: Path) -> bool:
    """path 是否位于 base 目录内(解析符号链接、统一大小写后比较)。"""
    try:
        common = os.path.commonpath([os.path.normcase(str(path)), os.path.normcase(str(base))])
        return common == os.path.normcase(str(base))
    except ValueError:
        return False


def analyze_repo(
    repo_dir: str | os.PathLike,
    *,
    allowed_root: str | os.PathLike | None = None,
    max_files: int = MAX_CORE_FILES,
) -> dict:
    """解析仓库目录,产出结构化信息(目录树/语言/核心文件/统计)。

    allowed_root 指定后,repo_dir 必须位于该目录内(防任意目录读取)。
    """
    root = Path(repo_dir).resolve()
    if allowed_root is not None:
        base = Path(allowed_root).resolve()
        if not _is_within(root, base):
            raise RepoError("仅允许解析仓库缓存目录内的路径")
    if not root.is_dir():
        raise RepoError(f"仓库目录不存在: {root}")

    rels = _walk(root)
    size = _dir_size(root)
    languages = _language_stats(root, rels)
    return {
        "root": str(root),
        "name": root.name,
        "tree": _build_tree(root, rels),
        "languages": languages,
        "core_files": _core_files(root, rels)[:max_files],
        "stats": {
            "file_count": len(rels),
            "size_bytes": size,
            "oversize": size > MAX_REPO_BYTES,
        },
        "description": _extract_description(root, rels),
        "tech_stack": _tech_stack(root, rels, languages),
    }
