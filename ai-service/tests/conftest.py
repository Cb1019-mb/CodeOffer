"""测试全局 fixture:禁用调用间隔 pacing,避免测试真睡 LLM_MIN_INTERVAL_SECONDS。"""
import pytest

import llm.gateway as gw


@pytest.fixture(autouse=True)
def _no_pacing(monkeypatch):
    monkeypatch.delenv("LLM_MIN_INTERVAL_SECONDS", raising=False)
    gw._last_call_ts = None