"""CodeOffer AI 服务入口(M0 骨架)。

职责边界:本服务只负责「仓库 + 大模型」相关能力,不触碰业务数据库;
Java 后端通过 HTTP 调用本服务,见 docs/superpowers/plans/2026-08-09-codeoffer-implementation-plan.md §3。
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="CodeOffer AI Service",
    description="仓库抓取/解析与 LLM 生成服务(双层架构的 Python 层)",
    version="0.0.1",
)

# 开发模式允许前端(5173)与 Java 后端(8080)跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:5173", "http://localhost:5173", "http://127.0.0.1:8080"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict:
    """M0 健康检查端点:验证 Python 层存活。"""
    return {"status": "ok", "service": "ai-service", "version": "0.0.1"}
