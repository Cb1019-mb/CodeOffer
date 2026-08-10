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


def test_clone_error_translated_to_friendly_hint(monkeypatch, tmp_path: Path):
    """网络类克隆错误应翻译为友好中文提示(而非原始英文报错)。"""
    from git import GitCommandError, Repo

    def boom(*args, **kwargs):
        err = GitCommandError(
            "git clone",
            128,
            stderr=b"fatal: unable to access 'https://github.com/x/y/': schannel: server closed abruptly (missing close_notify)",
        )
        raise err

    monkeypatch.setattr(Repo, "clone_from", boom)
    from services.repo import clone_repo

    with pytest.raises(RepoError) as exc_info:
        clone_repo("https://github.com/x/y", str(tmp_path))
    msg = str(exc_info.value)
    # 应包含友好提示(网络阻断/不可达),而不是只有原始英文
    assert "克隆失败" in msg
    assert "schannel" not in msg.split("原始错误")[0].lower() or "TLS" in msg or "代理" in msg
    assert "代理" in msg or "DNS" in msg or "不可达" in msg


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
