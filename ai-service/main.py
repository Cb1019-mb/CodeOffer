"""CodeOffer AI 服务入口。

职责边界:本服务只负责「仓库 + 大模型」相关能力,不触碰业务数据库;
Java 后端通过 HTTP 调用本服务,契约见 docs/design/plans/2026-08-09-codeoffer-implementation-plan.md §3。
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from llm import LLMConfig, LLMError, LLMJsonError, chat_json, chat_test
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
    TestConnectionRequest,
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
        repo_dir = clone_repo(req.repo_url, REPO_DATA_DIR, proxy=req.proxy)
    except RepoError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return CloneResponse(ok=True, repo_dir=repo_dir)


@app.post("/api/repos/analyze")
def analyze(req: AnalyzeRequest) -> dict[str, Any]:
    """静态解析仓库:目录树/语言统计/核心文件(仅限缓存目录内)。"""
    try:
        return analyze_repo(req.repo_dir, allowed_root=REPO_DATA_DIR)
    except RepoError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e


# ---------------------------------------------------------------- AI 生成
def _cfg_for(req) -> LLMConfig | None:
    """请求级 LLM 覆盖(用户档案);缺省 None 回退 env 配置。"""
    override = getattr(req, "llm", None)
    if override is None:
        return None
    return LLMConfig.from_values(override.base_url, override.api_key, override.model, override.verify_ssl)


def _prefs_for(req) -> dict[str, Any] | None:
    prefs = getattr(req, "prefs", None)
    return prefs.model_dump() if prefs is not None else None


def _run_json(messages: list[dict[str, str]], what: str, cfg: LLMConfig | None = None) -> dict[str, Any]:
    try:
        return chat_json(messages, cfg=cfg)
    except LLMJsonError as e:
        logger.warning("生成 %s 失败(JSON 格式):%s", what, e)
        raise HTTPException(status_code=502, detail=f"AI 输出格式异常: {_truncate(str(e), 200)}") from e
    except LLMError as e:
        logger.warning("生成 %s 失败:%s", what, e)
        raise HTTPException(status_code=502, detail=_truncate(str(e), 300)) from e


def _truncate(s: str, limit: int) -> str:
    """截断错误信息,避免把模型原始输出/内部细节回显给调用方。"""
    return s if len(s) <= limit else s[:limit] + "…"


@app.post("/api/ai/test-connection")
def test_connection(req: TestConnectionRequest) -> dict[str, Any]:
    """连接测试:单次最小调用,返回 ok/延迟/模型回显/错误。"""
    cfg = LLMConfig.from_values(req.llm.base_url, req.llm.api_key, req.llm.model, req.llm.verify_ssl)
    return chat_test(cfg)


@app.post("/api/ai/health-report")
def health_report(req: HealthReportRequest) -> dict[str, Any]:
    """项目体检报告:值不值得学 + 学习路线。"""
    return _run_json(
        build_health_report_messages(req.repo_url, req.meta, prefs=_prefs_for(req)),
        "体检报告", _cfg_for(req))


@app.post("/api/ai/knowledge-cards")
def knowledge_cards(req: KnowledgeCardsRequest) -> dict[str, Any]:
    """生成知识卡片(基础 4 类 + 运行指南)。"""
    return _run_json(
        build_knowledge_cards_messages(req.repo_info, req.types, prefs=_prefs_for(req)),
        "知识卡片", _cfg_for(req))


@app.post("/api/ai/flow-diagram")
def flow_diagram(req: FlowDiagramRequest) -> dict[str, Any]:
    """生成核心流程 Mermaid 图。"""
    return _run_json(
        build_flow_diagram_messages(req.repo_info, prefs=_prefs_for(req)),
        "流程图", _cfg_for(req))


@app.post("/api/ai/resume")
def resume(req: ResumeRequest) -> dict[str, Any]:
    """生成 STAR 简历项目描述。"""
    return _run_json(
        build_resume_messages(req.repo_info, prefs=_prefs_for(req)),
        "简历描述", _cfg_for(req))


@app.post("/api/ai/interview-questions")
def interview_questions(req: InterviewQuestionsRequest) -> dict[str, Any]:
    """生成模拟面试题(基础/进阶/深挖)。"""
    return _run_json(
        build_interview_messages(req.repo_info, prefs=_prefs_for(req)),
        "面试题", _cfg_for(req))


# ---------------------------------------------------------------- 克隆缓存管理
@app.get("/api/repos/cache-info")
def cache_info() -> dict[str, Any]:
    """克隆缓存占用:目录数与总字节数。"""
    if not REPO_DATA_DIR.exists():
        return {"size_bytes": 0, "repo_count": 0}
    entries = list(REPO_DATA_DIR.iterdir())
    total = 0
    for entry in entries:
        if entry.is_dir():
            total += sum(f.stat().st_size for f in entry.rglob("*") if f.is_file())
        else:
            total += entry.stat().st_size
    return {"size_bytes": total, "repo_count": len(entries)}


@app.post("/api/repos/clear-cache")
def clear_cache() -> dict[str, Any]:
    """清理克隆缓存:仅删 REPO_DATA_DIR 直接子项;不影响已生成产物。"""
    import shutil

    if not REPO_DATA_DIR.exists():
        return {"ok": True, "removed": 0}
    removed = 0
    for entry in REPO_DATA_DIR.iterdir():
        resolved = entry.resolve()
        if not resolved.is_relative_to(REPO_DATA_DIR.resolve()):
            continue  # 越界防护:符号链接等指向根外的一律跳过
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()
        removed += 1
    return {"ok": True, "removed": removed}
