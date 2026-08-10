"""仓库解析测试:构造临时目录验证解析逻辑(不涉及真实网络克隆)。"""
from __future__ import annotations

from pathlib import Path

import pytest

from services.repo import RepoError, analyze_repo


@pytest.fixture
def sample_repo(tmp_path: Path) -> Path:
    root = tmp_path / "sample-proj"
    (root / "src" / "main" / "java" / "com" / "x").mkdir(parents=True)
    (root / ".git").mkdir()
    (root / "node_modules" / "pkg").mkdir(parents=True)

    (root / "README.md").write_text("# Sample Project\n\n这是一个示例项目,用于演示仓库解析逻辑。\n", encoding="utf-8")
    (root / "pom.xml").write_text(
        "<project><dependencies><dependency><groupId>x</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>",
        encoding="utf-8",
    )
    app_java = "\n".join(f"// line {i}" for i in range(100))
    (root / "src" / "main" / "java" / "com" / "x" / "App.java").write_text(app_java, encoding="utf-8")
    util_java = "\n".join(f"// line {i}" for i in range(50))
    (root / "src" / "main" / "java" / "com" / "x" / "Util.java").write_text(util_java, encoding="utf-8")
    (root / "node_modules" / "pkg" / "index.js").write_text("// should be skipped", encoding="utf-8")
    (root / ".git" / "config").write_text("[core]", encoding="utf-8")
    return root


def test_analyze_basic_structure(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    assert info["name"] == "sample-proj"
    assert info["stats"]["file_count"] == 4  # README/pom/App/Util(跳过 .git 与 node_modules)


def test_languages_detected(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    langs = {lang["ext"]: lang for lang in info["languages"]}
    assert ".java" in langs
    assert ".md" in langs
    assert langs[".java"]["files"] == 2


def test_core_files_prioritize_readme_and_config(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    paths = {c["path"] for c in info["core_files"]}
    assert "README.md" in paths
    assert "pom.xml" in paths
    # 主入口(src/main/...)应入选
    assert any(p.startswith("src/main/") for p in paths)


def test_skips_ignored_dirs(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    tree_str = str(info["tree"])
    assert "node_modules" not in tree_str
    assert ".git" not in tree_str


def test_description_extracted_from_readme(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    assert "示例项目" in info["description"]


def test_tech_stack_primary_language_first(sample_repo: Path):
    info = analyze_repo(str(sample_repo))
    assert info["tech_stack"][0] == "Java"  # 主语言(行数最多)在前
    assert any("spring-boot" in t for t in info["tech_stack"])


def test_missing_dir_raises(tmp_path: Path):
    with pytest.raises(RepoError):
        analyze_repo(str(tmp_path / "nope"))


def test_clone_rejects_unsafe_url(tmp_path: Path):
    """不安全 URL(非 https/非受信任平台/携带凭证)应在克隆前被拒绝。"""
    from services.repo import clone_repo

    for bad in (
        "http://169.254.169.254/latest/meta-data",
        "ssh://git@github.com/octocat/Hello-World.git",
        "git://github.com/octocat/Hello-World.git",
        "https://user:token@github.com/octocat/Hello-World",
        "https://evil.example.com/repo",
        "ftp://github.com/x/y",
    ):
        with pytest.raises(RepoError):
            clone_repo(bad, str(tmp_path))


def test_clone_accepts_https_and_file(monkeypatch, tmp_path: Path):
    """https(受信任平台)与本地 file 仓库应放行到克隆阶段。"""
    from git import Repo

    import services.repo as repo_module

    def fake_clone(url, target, *args, **kwargs):
        # 模拟真实克隆:创建目标目录,供后续 move/标记流程使用
        from pathlib import Path as _Path

        _Path(target).mkdir(parents=True, exist_ok=True)
        return None

    monkeypatch.setattr(Repo, "clone_from", fake_clone)
    repo_module.clone_repo("https://github.com/octocat/Hello-World", str(tmp_path))
    repo_module.clone_repo("https://gitee.com/octocat/Hello-World.git", str(tmp_path))
    repo_module.clone_repo("file:///c:/temp/local-repo", str(tmp_path))


def test_clone_error_does_not_echo_raw_stderr(monkeypatch, tmp_path: Path):
    """克隆错误不应回显原始 stderr(防内网探测 oracle)。"""
    from git import GitCommandError, Repo

    from services.repo import clone_repo

    def boom(*args, **kwargs):
        raise GitCommandError(
            "git clone",
            128,
            stderr=b"fatal: unable to access 'http://192.168.1.1:8080/': connection refused",
        )

    monkeypatch.setattr(Repo, "clone_from", boom)
    with pytest.raises(RepoError) as exc_info:
        clone_repo("https://github.com/x/y", str(tmp_path))
    msg = str(exc_info.value)
    assert "192.168.1.1" not in msg
    assert "原始错误" not in msg


def test_clone_failure_cleans_remnant_dir(monkeypatch, tmp_path: Path):
    """克隆失败后应清理残留半成品目录,避免下次重试报 already exists。"""
    from git import GitCommandError, Repo

    import hashlib

    url = "https://gitee.com/x/y"
    name = hashlib.sha1(url.encode("utf-8")).hexdigest()[:12]
    remnant = tmp_path / name
    remnant.mkdir()
    (remnant / "partial").write_text("x", encoding="utf-8")

    def boom(*args, **kwargs):
        raise GitCommandError("git clone", 128, stderr=b"fatal: could not resolve host")

    monkeypatch.setattr(Repo, "clone_from", boom)
    from services.repo import clone_repo

    with pytest.raises(RepoError):
        clone_repo(url, str(tmp_path))
    assert not remnant.exists(), "残留目录应被清理"
