"""L2 后端集成样例：FastAPI + wenqu-ai（Python SDK）。

演示知识检索与工作流触发。两个端点都是平台包络成功体，SDK 的
``_request_envelope`` 已经解包，这里拿到的就是 ``data``。
"""

from __future__ import annotations

import os
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from wenqu import WenquAPIError, WenquClient


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


BASE_URL = env("WENQU_BASE_URL", "http://127.0.0.1:8090/ai").rstrip("/")
API_KEY = env("WENQU_API_KEY")
WORKFLOW_ID = env("WENQU_WORKFLOW_ID")
if not API_KEY:
    raise RuntimeError("缺少 WENQU_API_KEY：先在 samples/ 下跑 node seed/seed.mjs")

client = WenquClient(BASE_URL, API_KEY, timeout=60.0)
app = FastAPI(title="问渠样例 · backend-python")


def api_error(exc: WenquAPIError) -> HTTPException:
    return HTTPException(
        status_code=exc.status_code or 500,
        detail={"message": exc.message, "code": exc.code, "request_id": exc.request_id},
    )


@app.get("/health")
def health() -> Dict[str, Any]:
    return {"baseUrl": BASE_URL, "workflowConfigured": bool(WORKFLOW_ID)}


@app.get("/search")
def search(q: str, top_k: int = 5, min_score: Optional[float] = None) -> Dict[str, Any]:
    """知识检索：需要 kb.read。空库返回 chunks=[]，不是故障。"""
    try:
        return client.retrieval.search(q, top_k=top_k, min_score=min_score)
    except WenquAPIError as exc:
        raise api_error(exc)


class WorkflowRequest(BaseModel):
    inputs: Dict[str, Any] = {}


@app.post("/workflow")
def run_workflow(body: WorkflowRequest) -> Dict[str, Any]:
    """触发已发布工作流：需要 workflow.run；draft 会被后端 400 拒绝。"""
    if not WORKFLOW_ID:
        raise HTTPException(status_code=400, detail="未配置 WENQU_WORKFLOW_ID（需已发布的工作流）")
    try:
        return client.workflows.run(WORKFLOW_ID, body.inputs)
    except WenquAPIError as exc:
        raise api_error(exc)


@app.get("/workflow/{run_id}")
def get_workflow_run(run_id: str) -> Dict[str, Any]:
    if not WORKFLOW_ID:
        raise HTTPException(status_code=400, detail="未配置 WENQU_WORKFLOW_ID")
    try:
        return client.workflows.get_run(WORKFLOW_ID, run_id)
    except WenquAPIError as exc:
        raise api_error(exc)
