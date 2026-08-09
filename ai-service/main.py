"""CodeOffer AI 服务入口。

职责边界:本服务只负责「仓库 + 大模型」相关能力,不触碰业务数据库;
Java 后端通过 HTTP 调用本服务,契约见 docs/superpowers/plans/2026-08-09-codeoffer-implementation-plan.md §3。
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from llm import LLMError, LLMJsonError, chat_json
from prompts import (
    build_flow_diagram_messages,
    build_health_report_messages,
    build_interview_messages,
    build_knowledge_cards_messages,
    build_resume_messages,
)
from schemas import (
    AnalyzeRequest,
    CloneRequest,
    CloneResponse,
    FlowDiagramRequest,
    HealthReportRequest,
    InterviewQuestionsRequest,
    KnowledgeCardsRequest,
    ResumeRequest,
)
from services import RepoError, analyze_repo, clone_repo

# 加载根目录 config/.env(LLM key 等),本地开发默认路径
load_dotenv(Path(__file__).resolve().parent.parent / "config" / ".env")

logger = logging.getLogger(__name__)

REPO_DATA_DIR = Path(
    os.environ.get("REPO_DATA_DIR", str(Path(__file__).resolve().parent.parent / "data" / "repos"))
)

app = FastAPI(
    title="CodeOffer AI Service",
    description="仓库抓取/解析与 LLM 生成服务(双层架构的 Python 层)",
    version="0.0.1",
)

# 开发模式允许前端(5173)与 Java 后端(8080)跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:5173", "http://localhost:5173",
        "http://127.0.0.1:8080", "http://localhost:8080",
    ],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict:
    """健康检查端点:验证 Python 层存活。"""
    return {"status": "ok", "service": "ai-service", "version": "0.0.1"}


# ---------------------------------------------------------------- 仓库抓取/解析
@app.post("/api/repos/clone", response_model=CloneResponse)
def clone(req: CloneRequest) -> CloneResponse:
    """浅克隆仓库到本地缓存目录(缓存命中直接返回)。"""
    try:
        repo_dir = clone_repo(req.repo_url, REPO_DATA_DIR)
    except RepoError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return CloneResponse(ok=True, repo_dir=repo_dir)


@app.post("/api/repos/analyze")
def analyze(req: AnalyzeRequest) -> dict[str, Any]:
    """静态解析仓库:目录树/语言统计/核心文件。"""
    try:
        return analyze_repo(req.repo_dir)
    except RepoError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e


# ---------------------------------------------------------------- AI 生成
def _run_json(messages: list[dict[str, str]], what: str) -> dict[str, Any]:
    try:
        return chat_json(messages)
    except LLMJsonError as e:
        logger.warning("生成 %s 失败(JSON 格式):%s", what, e)
        raise HTTPException(status_code=502, detail=f"AI 输出格式异常: {e}") from e
    except LLMError as e:
        logger.warning("生成 %s 失败:%s", what, e)
        raise HTTPException(status_code=502, detail=str(e)) from e


@app.post("/api/ai/health-report")
def health_report(req: HealthReportRequest) -> dict[str, Any]:
    """项目体检报告:值不值得学 + 学习路线。"""
    return _run_json(build_health_report_messages(req.repo_url, req.meta), "体检报告")


@app.post("/api/ai/knowledge-cards")
def knowledge_cards(req: KnowledgeCardsRequest) -> dict[str, Any]:
    """生成知识卡片(基础 4 类 + 运行指南)。"""
    return _run_json(build_knowledge_cards_messages(req.repo_info, req.types), "知识卡片")


@app.post("/api/ai/flow-diagram")
def flow_diagram(req: FlowDiagramRequest) -> dict[str, Any]:
    """生成核心流程 Mermaid 图。"""
    return _run_json(build_flow_diagram_messages(req.repo_info), "流程图")


@app.post("/api/ai/resume")
def resume(req: ResumeRequest) -> dict[str, Any]:
    """生成 STAR 简历项目描述。"""
    return _run_json(build_resume_messages(req.repo_info), "简历描述")


@app.post("/api/ai/interview-questions")
def interview_questions(req: InterviewQuestionsRequest) -> dict[str, Any]:
    """生成模拟面试题(基础/进阶/深挖)。"""
    return _run_json(build_interview_messages(req.repo_info), "面试题")
