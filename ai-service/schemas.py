"""API 请求/响应模型(pydantic)。"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class CloneRequest(BaseModel):
    repo_url: str = Field(
        ...,
        min_length=1,
        max_length=500,
        description="GitHub 仓库地址,如 https://github.com/octocat/Hello-World",
    )


class CloneResponse(BaseModel):
    ok: bool
    repo_dir: str


class AnalyzeRequest(BaseModel):
    repo_dir: str = Field(..., min_length=1, max_length=1000)


class LlmOverride(BaseModel):
    """请求级 LLM 配置覆盖(Java 后端按用户档案注入);缺省回退 env。"""
    base_url: str
    api_key: str = ""
    model: str
    verify_ssl: bool = True


class GenerationPrefs(BaseModel):
    """生成偏好:语言与篇幅,注入 prompt system 指令。"""
    language: str = "zh"
    verbosity: str = "standard"


class TestConnectionRequest(BaseModel):
    llm: LlmOverride


class HealthReportRequest(BaseModel):
    repo_url: str
    meta: dict[str, Any] | None = None
    llm: LlmOverride | None = None
    prefs: GenerationPrefs | None = None


class KnowledgeCardsRequest(BaseModel):
    repo_info: dict[str, Any]
    types: list[str] = Field(
        default_factory=lambda: ["architecture", "difficulty", "highlight", "takeaway"],
        description="需要的卡片类型子集",
    )
    llm: LlmOverride | None = None
    prefs: GenerationPrefs | None = None


class FlowDiagramRequest(BaseModel):
    repo_info: dict[str, Any]
    llm: LlmOverride | None = None
    prefs: GenerationPrefs | None = None


class ResumeRequest(BaseModel):
    repo_info: dict[str, Any]
    llm: LlmOverride | None = None
    prefs: GenerationPrefs | None = None


class InterviewQuestionsRequest(BaseModel):
    repo_info: dict[str, Any]
    llm: LlmOverride | None = None
    prefs: GenerationPrefs | None = None
