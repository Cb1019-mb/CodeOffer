"""请求级 LLM 覆盖与 prefs 注入。"""
import httpx
from fastapi.testclient import TestClient

import main
from llm.gateway import LLMConfig


def test_from_values_and_empty_key_skips_auth():
    cfg = LLMConfig.from_values("https://x.test/v1", "", "m", False)
    assert cfg.configured  # base_url 非空即可(本地 vLLM 无鉴权)
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": 1}'}}]})

    from llm.gateway import chat_json
    chat_json([{"role": "user", "content": "hi"}], cfg=cfg, transport=httpx.MockTransport(handler))
    assert captured["auth"] is None


def test_endpoint_llm_override_used(monkeypatch):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.read().decode()
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"cards": []}'}}]})

    monkeypatch.setattr(
        "llm.gateway._client",
        lambda timeout, transport, verify=True: httpx.Client(transport=httpx.MockTransport(handler)),
    )
    client = TestClient(main.app)
    r = client.post("/api/ai/knowledge-cards", json={
        "repo_info": {"name": "x"}, "types": ["architecture"],
        "llm": {"base_url": "https://override.test/v1", "api_key": "k2", "model": "m2", "verify_ssl": True},
        "prefs": {"language": "en", "verbosity": "concise"},
    })
    assert r.status_code == 200
    assert seen["url"].startswith("https://override.test/v1/")
    assert '"model": "m2"' in seen["body"] or '"model":"m2"' in seen["body"]


def test_prefs_injected_into_prompts():
    from prompts import build_knowledge_cards_messages
    msgs = build_knowledge_cards_messages(
        {"name": "x"}, ["architecture"], prefs={"language": "en", "verbosity": "concise"})
    system = " ".join(m["content"] for m in msgs if m["role"] == "system")
    assert "English" in system
    assert "精简" in system


def test_test_connection_endpoint(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": "pong"}}], "model": "m-echo"})

    monkeypatch.setattr(
        "llm.gateway._client",
        lambda timeout, transport, verify=True: httpx.Client(transport=httpx.MockTransport(handler)),
    )
    client = TestClient(main.app)
    r = client.post("/api/ai/test-connection", json={
        "llm": {"base_url": "https://x.test/v1", "api_key": "k", "model": "m", "verify_ssl": True}})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["model"] == "m-echo"
    assert body["latency_ms"] >= 0


def test_cache_info_and_clear(tmp_path, monkeypatch):
    (tmp_path / "repo1").mkdir()
    (tmp_path / "repo1" / "f.txt").write_text("abc")
    (tmp_path / "repo2").mkdir()
    monkeypatch.setattr(main, "REPO_DATA_DIR", tmp_path)
    client = TestClient(main.app)
    info = client.get("/api/repos/cache-info").json()
    assert info["repo_count"] == 2
    assert info["size_bytes"] >= 3
    cleared = client.post("/api/repos/clear-cache").json()
    assert cleared["ok"] is True
    assert cleared["removed"] == 2
    assert list(tmp_path.iterdir()) == []