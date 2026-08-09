"""LLM 网关:统一 OpenAI 兼容端点调用,含 JSON 校验与指数退避重试。

设计要点:
- 国内各家模型(DeepSeek/通义/智谱)均提供 OpenAI 兼容 /chat/completions,
  通过 base_url + api_key + model 三个配置即可切换,不绑定具体厂商。
- 生成类调用统一走 chat_json:响应必须是合法 JSON;格式非法时自动做一次
  "修复重试",仍失败抛出 LLMJsonError(调用方决定是否降级为纯文本)。
- 网络抖动/限流/5xx 走指数退避重试;鉴权失败(401/403)直接抛出,不浪费重试。
"""
from __future__ import annotations

import json
import logging
import os
import time
from typing import Any

import httpx

logger = logging.getLogger(__name__)

DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
DEFAULT_MODEL = "deepseek-chat"
MAX_RETRIES = 3
BACKOFF_BASE_SECONDS = 1.5


class LLMError(Exception):
    """LLM 调用失败(网络/鉴权/未知错误)。"""


class LLMJsonError(LLMError):
    """LLM 响应无法解析为 JSON(修复重试后仍失败)。"""


class LLMConfig:
    """从环境变量(或 config/.env,经 python-dotenv 加载)读取 LLM 配置。"""

    def __init__(self) -> None:
        self.base_url = (os.environ.get("LLM_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.api_key = os.environ.get("LLM_API_KEY") or ""
        self.model = os.environ.get("LLM_MODEL") or DEFAULT_MODEL
        # 代理/自签名证书环境可设 LLM_VERIFY_SSL=false 关闭校验(默认开启,保持安全)
        self.verify_ssl = os.environ.get("LLM_VERIFY_SSL", "true").lower() != "false"

    @property
    def configured(self) -> bool:
        return bool(self.api_key)


def _client(
    timeout: float, transport: httpx.BaseTransport | None, verify: bool = True
) -> httpx.Client:
    if transport is not None:
        return httpx.Client(timeout=timeout, transport=transport, verify=verify)
    return httpx.Client(timeout=timeout, verify=verify)


def _post(
    messages: list[dict[str, str]],
    cfg: LLMConfig,
    *,
    temperature: float,
    timeout: float = 120.0,
    transport: httpx.BaseTransport | None = None,
) -> str:
    """调用 chat/completions 并返回 content 文本;网络/限流/5xx 指数退避重试。"""
    url = f"{cfg.base_url}/chat/completions"
    payload = {
        "model": cfg.model,
        "messages": messages,
        "temperature": temperature,
    }
    headers = {"Authorization": f"Bearer {cfg.api_key}", "Content-Type": "application/json"}

    last_err: Exception | None = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            with _client(timeout, transport, verify=cfg.verify_ssl) as client:
                resp = client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"]
        except httpx.HTTPStatusError as e:
            status = e.response.status_code
            if status in (401, 403):
                raise LLMError(
                    f"LLM 鉴权失败(HTTP {status}):请检查 config/.env 中的 LLM_API_KEY 与 LLM_BASE_URL"
                ) from e
            last_err = e
        except (httpx.TimeoutException, httpx.NetworkError) as e:
            last_err = e
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as e:
            raise LLMError(f"LLM 响应结构异常: {e}") from e

        if attempt < MAX_RETRIES:
            sleep = BACKOFF_BASE_SECONDS**attempt
            logger.warning("LLM 调用失败(第 %d/%d 次):%s,%.1fs 后重试", attempt, MAX_RETRIES, last_err, sleep)
            time.sleep(sleep)

    raise LLMError(
        f"LLM 调用失败(已重试 {MAX_RETRIES} 次): {last_err}{_certificate_hint(last_err)}"
    ) from last_err


def _certificate_hint(err: Exception | None) -> str:
    """SSL 证书验证失败时给出可操作的绕过提示。"""
    if err is None:
        return ""
    if "certificate verify failed" in str(err).lower() or "certificate_verify_failed" in str(err).lower():
        return (
            "。提示:当前环境无法验证 LLM API 的证书(常见于代理/抓包/防火墙拦截)。"
            "如确认服务可靠,可在 config/.env 中设置 LLM_VERIFY_SSL=false 绕过证书验证后重启"
        )
    return ""


def _parse_json(content: str) -> dict[str, Any]:
    """解析 LLM 输出为 JSON;容忍 markdown 代码块围栏。"""
    text = content.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return json.loads(text)


def chat_json(
    messages: list[dict[str, str]],
    *,
    temperature: float = 0.3,
    transport: httpx.BaseTransport | None = None,
    cfg: LLMConfig | None = None,
) -> dict[str, Any]:
    """返回 LLM 的 JSON 响应;格式非法时带上下文自动修复重试一次。"""
    cfg = cfg or LLMConfig()
    if not cfg.configured:
        raise LLMError("未配置 LLM_API_KEY:请复制 config/.env.example 为 config/.env 并填写")

    content = _post(messages, cfg, temperature=temperature, transport=transport)
    try:
        return _parse_json(content)
    except (json.JSONDecodeError, TypeError):
        logger.warning("LLM 首次输出非合法 JSON,尝试修复...")
        fix_messages = [
            *messages,
            {"role": "assistant", "content": content},
            {
                "role": "user",
                "content": "你的上一条输出不是合法 JSON。请只输出修正后的合法 JSON,不要任何解释、不要代码块围栏。",
            },
        ]
        content2 = _post(fix_messages, cfg, temperature=0.0, transport=transport)
        try:
            return _parse_json(content2)
        except (json.JSONDecodeError, TypeError) as e:
            raise LLMJsonError(f"LLM 响应无法解析为 JSON: {content2[:200]}") from e


def chat_text(
    messages: list[dict[str, str]],
    *,
    temperature: float = 0.7,
    transport: httpx.BaseTransport | None = None,
    cfg: LLMConfig | None = None,
) -> str:
    """普通文本调用(降级路径:LLMJsonError 时由调用方决定)。"""
    cfg = cfg or LLMConfig()
    if not cfg.configured:
        raise LLMError("未配置 LLM_API_KEY:请复制 config/.env.example 为 config/.env 并填写")
    return _post(messages, cfg, temperature=temperature, transport=transport)
