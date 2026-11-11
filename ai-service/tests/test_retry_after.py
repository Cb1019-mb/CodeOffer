"""429 限流退避:尊重 Retry-After 响应头,无头时用保守默认间隔。"""
import httpx

import llm.gateway as gw


def _cfg():
    cfg = gw.LLMConfig()
    cfg.base_url = "https://example.test/v1"
    cfg.api_key = "test-key"
    cfg.model = "test-model"
    cfg.verify_ssl = True
    return cfg


def _ok_response():
    return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": 1}'}}]})


def test_429_honors_retry_after(monkeypatch):
    sleeps = []
    monkeypatch.setattr(gw.time, "sleep", lambda s: sleeps.append(s))
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"Retry-After": "7"}, json={"error": "rate limited"})
        return _ok_response()

    out = gw._post(
        [{"role": "user", "content": "hi"}], _cfg(),
        temperature=0.1, transport=httpx.MockTransport(handler),
    )
    assert out == '{"ok": 1}'
    assert calls["n"] == 2
    assert sleeps and sleeps[0] >= 7


def test_429_without_retry_after_uses_conservative_default(monkeypatch):
    sleeps = []
    monkeypatch.setattr(gw.time, "sleep", lambda s: sleeps.append(s))
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, json={"error": "rate limited"})
        return _ok_response()

    out = gw._post(
        [{"role": "user", "content": "hi"}], _cfg(),
        temperature=0.1, transport=httpx.MockTransport(handler),
    )
    assert out == '{"ok": 1}'
    assert sleeps and sleeps[0] >= 20


def test_429_retry_after_capped(monkeypatch):
    sleeps = []
    monkeypatch.setattr(gw.time, "sleep", lambda s: sleeps.append(s))
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"Retry-After": "600"}, json={"error": "rate limited"})
        return _ok_response()

    gw._post(
        [{"role": "user", "content": "hi"}], _cfg(),
        temperature=0.1, transport=httpx.MockTransport(handler),
    )
    assert sleeps and sleeps[0] <= 45