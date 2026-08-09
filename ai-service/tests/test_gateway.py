"""LLM 网关测试:全部使用 httpx.MockTransport,不产生真实 API 费用。"""
from __future__ import annotations

import json

import httpx
import pytest

from llm.gateway import LLMConfig, LLMError, LLMJsonError, chat_json


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    monkeypatch.setenv("LLM_BASE_URL", "https://mock.test/v1")
    monkeypatch.setenv("LLM_MODEL", "mock-model")
    monkeypatch.setattr("llm.gateway.BACKOFF_BASE_SECONDS", 0.0)  # 测试不等待退避


def _cfg() -> LLMConfig:
    return LLMConfig()


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def test_chat_json_parses_normal():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/chat/completions")
        payload = json.loads(request.content)
        assert payload["model"] == "mock-model"
        assert request.headers["Authorization"] == "Bearer test-key"
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": true}'}}]})

    result = chat_json([{"role": "user", "content": "hi"}], transport=_transport(handler), cfg=_cfg())
    assert result == {"ok": True}


def test_chat_json_strips_markdown_fence():
    def handler(request: httpx.Request) -> httpx.Response:
        content = '```json\n{"ok": 1}\n```'
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    result = chat_json([], transport=_transport(handler), cfg=_cfg())
    assert result == {"ok": 1}


def test_chat_json_repairs_bad_json_once():
    """首次输出非法 JSON 时,应带上下文做一次修复重试。"""
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        if len(calls) == 1:
            content = "抱歉,这不是 JSON"
        else:
            content = '{"fixed": true}'
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    result = chat_json([], transport=_transport(handler), cfg=_cfg())
    assert result == {"fixed": True}
    assert len(calls) == 2
    # 修复请求应携带首次失败输出与纠正指令
    assert "不是合法 JSON" in calls[1]["messages"][-1]["content"]


def test_chat_json_raises_after_two_bad_outputs():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}]})

    with pytest.raises(LLMJsonError):
        chat_json([], transport=_transport(handler), cfg=_cfg())


def test_retry_on_429_then_success():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, json={"error": "rate limited"})
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": 2}'}}]})

    result = chat_json([], transport=_transport(handler), cfg=_cfg())
    assert result == {"ok": 2}
    assert calls["n"] == 2


def test_auth_failure_raises_immediately():
    """401 鉴权失败应直接抛出,不重试。"""
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(401, json={"error": "unauthorized"})

    with pytest.raises(LLMError, match="鉴权"):
        chat_json([], transport=_transport(handler), cfg=_cfg())
    assert calls["n"] == 1


def test_missing_api_key_raises(monkeypatch):
    monkeypatch.delenv("LLM_API_KEY")
    with pytest.raises(LLMError, match="LLM_API_KEY"):
        chat_json([], cfg=_cfg())
