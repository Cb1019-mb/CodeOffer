"""API 请求/响应模型(pydantic)。"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class CloneRequest(BaseModel):
    repo_url: str = Field(..., description="GitHub 仓库地址,如 https://github.com/octocat/Hello-World")


class CloneResponse(BaseModel):
    ok: bool
    repo_dir: str


class AnalyzeRequest(BaseModel):
    repo_dir: str


class HealthReportRequest(BaseModel):
    repo_url: str
    meta: dict[str, Any] | None = None


class KnowledgeCardsRequest(BaseModel):
    repo_info: dict[str, Any]
    types: list[str] = Field(
        default_factory=lambda: ["architecture", "difficulty", "highlight", "takeaway"],
        description="需要的卡片类型子集",
    )


class FlowDiagramRequest(BaseModel):
    repo_info: dict[str, Any]


class ResumeRequest(BaseModel):
    repo_info: dict[str, Any]


class InterviewQuestionsRequest(BaseModel):
    repo_info: dict[str, Any]
