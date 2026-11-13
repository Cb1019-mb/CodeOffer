"""API 端点测试:monkeypatch LLM 网关,验证端点契约与错误处理。"""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import main as main_module
from main import app

client = TestClient(app)


def test_health():
    resp = client.get("/api/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    assert resp.json()["service"] == "ai-service"


def test_analyze_endpoint(tmp_path: Path, monkeypatch):
    # analyze 仅允许缓存目录内路径,把 REPO_DATA_DIR 指到临时目录以通过校验
    monkeypatch.setattr(main_module, "REPO_DATA_DIR", str(tmp_path))
    repo = tmp_path / "sample"
    repo.mkdir()
    (repo / "README.md").write_text("hello", encoding="utf-8")
    resp = client.post("/api/repos/analyze", json={"repo_dir": str(repo)})
    assert resp.status_code == 200
    assert resp.json()["name"] == repo.name


def test_analyze_outside_cache_dir_rejected(tmp_path: Path, monkeypatch):
    # 任意目录(缓存目录之外)应被拒绝,防任意文件读取
    monkeypatch.setattr(main_module, "REPO_DATA_DIR", str(tmp_path / "cache"))
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "README.md").write_text("secret", encoding="utf-8")
    resp = client.post("/api/repos/analyze", json={"repo_dir": str(outside)})
    assert resp.status_code == 422


def test_analyze_missing_dir():
    resp = client.post("/api/repos/analyze", json={"repo_dir": "C:/definitely/not/exist"})
    assert resp.status_code == 422


@pytest.mark.parametrize(
    "path,payload,fake_response,expected",
    [
        (
            "/api/ai/health-report",
            {"repo_url": "https://github.com/a/b", "meta": {"stars": 100}},
            {"verdict": "值得学", "score": 8, "reasons": [], "learning_path": [], "risk": ""},
            "verdict",
        ),
        (
            "/api/ai/knowledge-cards",
            {"repo_info": {"name": "x"}, "types": ["architecture"]},
            {"cards": [{"type": "architecture", "title": "t", "content": "c"}]},
            "cards",
        ),
        (
            "/api/ai/flow-diagram",
            {"repo_info": {"name": "x"}},
            {"title": "t", "description": "d", "mermaid": "graph LR"},
            "mermaid",
        ),
        (
            "/api/ai/resume",
            {"repo_info": {"name": "x"}},
            {"star": {"situation": "s", "task": "t", "action": "a", "result": "r", "summary": "s"}},
            "star",
        ),
        (
            "/api/ai/interview-questions",
            {"repo_info": {"name": "x"}},
            {"questions": [{"level": "base", "question": "q", "answer": "a"}]},
            "questions",
        ),
    ],
)
def test_ai_endpoints(monkeypatch, path, payload, fake_response, expected):
    captured = {}

    def fake_chat_json(messages, **kwargs):
        captured["messages"] = messages
        return fake_response

    monkeypatch.setattr(main_module, "chat_json", fake_chat_json)
    resp = client.post(path, json=payload)
    assert resp.status_code == 200
    assert expected in resp.json()
    assert isinstance(captured["messages"], list)
    assert captured["messages"][0]["role"] == "system"


def test_ai_endpoint_llm_error(monkeypatch):
    def boom(messages, **kwargs):
        raise main_module.LLMError("网络超时")

    monkeypatch.setattr(main_module, "chat_json", boom)
    resp = client.post("/api/ai/resume", json={"repo_info": {"name": "x"}})
    assert resp.status_code == 502
    assert "网络超时" in resp.json()["detail"]


def test_clone_endpoint_cached(monkeypatch, tmp_path: Path):
    repo_dir = tmp_path / "cached-repo"
    repo_dir.mkdir()
    (repo_dir / ".git").mkdir()

    def fake_clone(repo_url, dest_dir, **kwargs):
        return str(repo_dir)

    monkeypatch.setattr(main_module, "clone_repo", fake_clone)
    monkeypatch.setattr(main_module, "REPO_DATA_DIR", tmp_path / "cache")
    resp = client.post("/api/repos/clone", json={"repo_url": "https://github.com/a/b"})
    assert resp.status_code == 200
    assert resp.json()["ok"] is True


def test_clone_endpoint_passes_proxy(monkeypatch, tmp_path: Path):
    """clone 端点应把 proxy 透传给 clone_repo。"""
    captured = {}

    def fake_clone(repo_url, dest_dir, **kwargs):
        captured["proxy"] = kwargs.get("proxy")
        return str(tmp_path / "r")

    monkeypatch.setattr(main_module, "clone_repo", fake_clone)
    monkeypatch.setattr(main_module, "REPO_DATA_DIR", tmp_path / "cache")
    resp = client.post(
        "/api/repos/clone",
        json={"repo_url": "https://github.com/a/b", "proxy": "http://127.0.0.1:7890"},
    )
    assert resp.status_code == 200
    assert captured["proxy"] == "http://127.0.0.1:7890"
