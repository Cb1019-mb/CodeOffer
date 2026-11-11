"""网关 HTTP 客户端构造:系统代理环境变量畸形(如 NO_PROXY 含 [::1])不得导致崩溃。"""
import httpx

from llm.gateway import _client


def test_client_ignores_malformed_system_proxy_env(monkeypatch):
    monkeypatch.setenv("NO_PROXY", "localhost,.localhost,127.0.0.1,127.0.0.0/8,[::1],::1")
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:7897")
    client = _client(5.0, None, verify=False)
    assert isinstance(client, httpx.Client)
    client.close()


def test_client_honors_explicit_llm_proxy(monkeypatch):
    monkeypatch.setenv("LLM_PROXY", "http://127.0.0.1:7897")
    client = _client(5.0, None)
    assert isinstance(client, httpx.Client)
    client.close()


def test_client_without_proxy_env(monkeypatch):
    monkeypatch.delenv("LLM_PROXY", raising=False)
    client = _client(5.0, None)
    assert isinstance(client, httpx.Client)
    client.close()