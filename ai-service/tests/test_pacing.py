"""LLM 调用间隔 pacing:低 RPM/TPM 配额提供商靠间隔避开限流窗口。"""
import llm.gateway as gw


def _cfg():
    cfg = gw.LLMConfig()
    cfg.base_url = "https://example.test/v1"
    cfg.api_key = "test-key"
    cfg.model = "test-model"
    cfg.verify_ssl = True
    return cfg


def _ok(request):
    import httpx
    return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok": 1}'}}]})


def test_pacing_sleeps_between_calls(monkeypatch):
    import httpx
    monkeypatch.setenv("LLM_MIN_INTERVAL_SECONDS", "65")
    sleeps = []
    monkeypatch.setattr(gw.time, "sleep", lambda s: sleeps.append(s))
    clock = {"t": 1000.0}
    monkeypatch.setattr(gw.time, "monotonic", lambda: clock["t"])
    gw._last_call_ts = None

    transport = httpx.MockTransport(_ok)
    gw._post([{"role": "user", "content": "a"}], _cfg(), temperature=0.1, transport=transport)
    assert sleeps == []  # 首次调用不等待
    clock["t"] += 10.0  # 距上次调用仅 10s
    gw._post([{"role": "user", "content": "b"}], _cfg(), temperature=0.1, transport=transport)
    assert sleeps and abs(sleeps[0] - 55.0) < 0.01  # 补足到 65s


def test_pacing_disabled_by_default(monkeypatch):
    import httpx
    monkeypatch.delenv("LLM_MIN_INTERVAL_SECONDS", raising=False)
    sleeps = []
    monkeypatch.setattr(gw.time, "sleep", lambda s: sleeps.append(s))
    gw._last_call_ts = None
    transport = httpx.MockTransport(_ok)
    gw._post([{"role": "user", "content": "a"}], _cfg(), temperature=0.1, transport=transport)
    gw._post([{"role": "user", "content": "b"}], _cfg(), temperature=0.1, transport=transport)
    assert sleeps == []