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
import threading
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

    @classmethod
    def from_values(cls, base_url: str, api_key: str, model: str, verify_ssl: bool) -> "LLMConfig":
        """请求级覆盖构造(用户档案);空 api_key 合法(本地 vLLM 无鉴权)。"""
        cfg = cls.__new__(cls)
        cfg.base_url = base_url.rstrip("/")
        cfg.api_key = api_key or ""
        cfg.model = model
        cfg.verify_ssl = verify_ssl
        return cfg

    @property
    def configured(self) -> bool:
        return bool(self.base_url)


def _client(
    timeout: float, transport: httpx.BaseTransport | None, verify: bool = True
) -> httpx.Client:
    if transport is not None:
        return httpx.Client(timeout=timeout, transport=transport, verify=verify)
    # 不读系统代理环境变量:NO_PROXY 的畸形条目(如 IPv6 字面量 [::1])会触发
    # httpx InvalidURL,使所有 LLM 调用在客户端构造期崩溃。需要代理时显式配置 LLM_PROXY。
    proxy = os.environ.get("LLM_PROXY") or None
    return httpx.Client(timeout=timeout, verify=verify, trust_env=False, proxy=proxy)


def _post(
    messages: list[dict[str, str]],
    cfg: LLMConfig,
    *,
    temperature: float,
    timeout: float = 60.0,
    transport: httpx.BaseTransport | None = None,
    attempts: int = MAX_RETRIES,
    max_tokens: int = 8000,
    raw: bool = False,
) -> Any:
    """调用 chat/completions 并返回 content 文本(raw=True 返回解析后的 dict);
    限流(429)/5xx/网络抖动指数退避重试。
    单次 60s、最多 3 次尝试(2 次重试)≈ 185s,与 Java 端 220s 读超时对齐;
    注意:JSON 修复重试路径会再发起一次调用,最坏约 370s,超出部分由 Java 端 220s 有界截断。"""
    url = f"{cfg.base_url}/chat/completions"
    _pace()
    payload = {
        "model": cfg.model,
        "messages": messages,
        "temperature": temperature,
        # 输出长度上限:5 张知识卡片(每张 300-800 字)加 JSON 结构约需 6-7k tokens,给足余量
        "max_tokens": max_tokens,
    }
    headers = {"Content-Type": "application/json"}
    if cfg.api_key:
        headers["Authorization"] = f"Bearer {cfg.api_key}"

    last_err: Exception | None = None
    next_sleep: float | None = None
    for attempt in range(1, attempts + 1):
        try:
            with _client(timeout, transport, verify=cfg.verify_ssl) as client:
                resp = client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            return data if raw else _content_text(data)
        except httpx.HTTPStatusError as e:
            status = e.response.status_code
            if status in (401, 403):
                raise LLMError(
                    f"LLM 鉴权失败(HTTP {status}):请检查 config/.env 中的 LLM_API_KEY 与 LLM_BASE_URL"
                ) from e
            if status == 429 or 500 <= status < 600:
                last_err = e  # 限流/服务端错误:可重试
                if status == 429:
                    next_sleep = _retry_after_seconds(e.response.headers.get("Retry-After"))
            else:
                # 其它 4xx(400/404/422 等)是客户端错误,重试无意义
                raise LLMError(f"LLM 调用被拒绝(HTTP {status}):请检查 LLM_BASE_URL 与请求内容") from e
        except (httpx.TimeoutException, httpx.NetworkError) as e:
            last_err = e
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as e:
            raise LLMError(f"LLM 响应结构异常: {e}") from e

        if attempt < attempts:
            sleep = next_sleep if next_sleep is not None else BACKOFF_BASE_SECONDS**attempt
            next_sleep = None
            logger.warning("LLM 调用失败(第 %d/%d 次):%s,%.1fs 后重试", attempt, attempts, last_err, sleep)
            time.sleep(sleep)

    raise LLMError(
        f"LLM 调用失败(已重试 {attempts} 次): {last_err}{_certificate_hint(last_err)}"
    ) from last_err


def chat_test(cfg: LLMConfig) -> dict[str, Any]:
    """连接测试:单次最小调用(不重试、15s、16 tokens),返回 ok/延迟/模型回显/错误。"""
    t0 = time.monotonic()
    try:
        data = _post(
            [{"role": "user", "content": "ping"}], cfg,
            temperature=0.0, timeout=15.0, attempts=1, max_tokens=16, raw=True,
        )
        return {
            "ok": True,
            "latency_ms": int((time.monotonic() - t0) * 1000),
            "model": data.get("model") or cfg.model,
            "error": None,
        }
    except LLMError as e:
        return {
            "ok": False,
            "latency_ms": int((time.monotonic() - t0) * 1000),
            "model": cfg.model,
            "error": str(e)[:300],
        }


def _retry_after_seconds(value: str | None) -> float:
    """429 退避间隔:优先 Retry-After(秒),缺省保守 30s;上限 45s 以守住 Java 端 220s 读超时预算。"""
    try:
        seconds = float(value) if value else 30.0
    except ValueError:
        seconds = 30.0
    return max(1.0, min(seconds, 45.0))


_pace_lock = threading.Lock()
_last_call_ts: float | None = None


def _pace() -> None:
    """低 RPM/TPM 配额提供商的调用间隔 pacing(LLM_MIN_INTERVAL_SECONDS,默认 0 关闭)。"""
    global _last_call_ts
    try:
        interval = float(os.environ.get("LLM_MIN_INTERVAL_SECONDS", "0") or 0)
    except ValueError:
        interval = 0.0
    if interval <= 0:
        return
    with _pace_lock:
        now = time.monotonic()
        if _last_call_ts is not None:
            wait = interval - (now - _last_call_ts)
            if wait > 0:
                time.sleep(wait)
        _last_call_ts = time.monotonic()


def _content_text(data: dict[str, Any]) -> str:
    """从 OpenAI 兼容响应提取 content;兼容 content 为字符串或分段列表的返回。"""
    content = data["choices"][0]["message"]["content"]
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            str(part.get("text", "")) if isinstance(part, dict) else str(part)
            for part in content
        )
    return str(content)


def _certificate_hint(err: Exception | None) -> str:
    """SSL 证书验证失败时给出可操作的排查提示(不诱导关闭证书校验)。"""
    if err is None:
        return ""
    if "certificate verify failed" in str(err).lower() or "certificate_verify_failed" in str(err).lower():
        return (
            ". 提示:当前环境无法验证 LLM API 的证书(常见于代理/自签名证书/防火墙拦截)。"
            "请检查系统时间、为请求配置可信 CA 或正确配置代理"
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
        raise LLMError("未配置 LLM:请在 config/.env 填写 LLM_BASE_URL,或在设置页配置并激活提供商档案")

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
        raise LLMError("未配置 LLM:请在 config/.env 填写 LLM_BASE_URL,或在设置页配置并激活提供商档案")
    return _post(messages, cfg, temperature=temperature, transport=transport)
