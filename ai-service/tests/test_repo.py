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

    (root / "README.md").write_text("# Sample Project\n", encoding="utf-8")
    (root / "pom.xml").write_text("<project></project>", encoding="utf-8")
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


def test_missing_dir_raises(tmp_path: Path):
    with pytest.raises(RepoError):
        analyze_repo(str(tmp_path / "nope"))
