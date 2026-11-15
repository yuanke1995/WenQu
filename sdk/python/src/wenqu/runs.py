"""v1 run 原语（A2）：建轮 / 状态 / 事件流 / 审批 / 产物 / 插话 / 停止。

与 JS SDK ``Runs`` 同构。**v1 成功响应是裸对象**（不走 ``{success, data}`` 包络），
所以这里统一用 ``client._request(...).json()``；SSE 帧带 ``id:``（seq）、事件名在
``event:`` 行，断线用 ``Last-Event-ID`` 只补增量，缓冲过期收到
``run.error(code=resume_expired)`` 时由 :meth:`RunsNamespace.stream` 自动降级重拉
``GET /runs/{id}``。

服务端真源：``OpenRunEmitter.EVENTS``（事件名与 payload）、``OpenRunErrorCode``（稳定码）、
``contract/openapi.v1.json``（7 个端点）。
"""

from __future__ import annotations

from typing import Any, Callable, Dict, Generator, List, NamedTuple, Optional
from urllib.parse import quote

import requests

from .sse import iter_sse

_ROOT = "/api/ai/v1"

# 冻结的事件名与稳定错误码（与服务端 OpenRunEmitter / OpenRunErrorCode 对齐）
RUN_EVENT_NAMES = (
    "run.started", "token", "tool.status", "plan.updated",
    "approval.required", "artifact.created", "approval.timed_out",
    "run.done", "run.error",
)
RUN_ERROR_CODES = (
    "internal_error", "resume_expired", "approval_timeout",
    "rate_limited", "agent_not_found", "invalid_request",
)


class RunEvent(NamedTuple):
    """一条 v1 事件：``id`` 是服务端 seq（从 1 起），``data`` 是带 runId 的扁平对象。"""

    id: int
    event: str
    data: Dict[str, Any]


def _retryable(exc: BaseException) -> bool:
    """4xx 中重试也没用的一类直接抛；网络错误、5xx、流中断可重连。"""
    from .client import WenquAPIError  # 局部导入避免模块级循环

    if isinstance(exc, WenquAPIError):
        return exc.status_code not in (401, 403, 404, 429)
    return True


def _q(value: str) -> str:
    return quote(str(value), safe="")


class RunsNamespace:
    """挂在 ``WenquClient.runs``。"""

    def __init__(self, client: Any) -> None:
        self._client = client

    # ==================== 7 个端点（裸 JSON） ====================

    def create(self, agent_id: str, question: str, *,
               session_id: Optional[str] = None,
               idempotency_key: Optional[str] = None) -> Dict[str, Any]:
        if not isinstance(question, str) or not question.strip():
            raise ValueError("question 不能为空")
        headers = {"Idempotency-Key": idempotency_key} if idempotency_key else None
        body: Dict[str, Any] = {"question": question}
        if session_id:
            body["sessionId"] = session_id
        return self._client._request(
            "POST", f"{_ROOT}/agents/{_q(agent_id)}/runs",
            json_body=body, headers=headers).json()

    def get(self, run_id: str) -> Dict[str, Any]:
        return self._client._request("GET", f"{_ROOT}/runs/{_q(run_id)}").json()

    def artifacts(self, run_id: str) -> Dict[str, Any]:
        return self._client._request("GET", f"{_ROOT}/runs/{_q(run_id)}/artifacts").json()

    def approve(self, run_id: str, approval_id: str, decision: str, **fields: Any) -> Dict[str, Any]:
        if not approval_id:
            raise ValueError("approval_id 不能为空")
        if not decision:
            raise ValueError("decision 不能为空")
        body: Dict[str, Any] = {"approvalId": approval_id, "decision": decision}
        for key in ("reason", "scope", "plan", "answers"):
            if fields.get(key) is not None:
                body[key] = fields[key]
        return self._client._request(
            "POST", f"{_ROOT}/runs/{_q(run_id)}/approvals", json_body=body).json()

    def steer(self, run_id: str, text: str) -> Dict[str, Any]:
        if not isinstance(text, str) or not text.strip():
            raise ValueError("text 不能为空")
        return self._client._request(
            "POST", f"{_ROOT}/runs/{_q(run_id)}/steer", json_body={"text": text}).json()

    def stop(self, run_id: str) -> Dict[str, Any]:
        return self._client._request("POST", f"{_ROOT}/runs/{_q(run_id)}/stop").json()

    # ==================== 事件流 ====================

    def iter_events(self, run_id: str, *, last_event_id: int = 0,
                    max_reconnects: int = 3,
                    reconnect_delay: float = 0.2,
                    on_reconnect: Optional[Callable[[int, int], None]] = None,
                    ) -> Generator[RunEvent, None, None]:
        """逐帧产出服务端事件；断线自动带 ``Last-Event-ID`` 续传、按 seq 去重。

        终态（``run.done`` / ``run.error``）后停止。``resume_expired`` 帧**会原样产出**，
        由 :meth:`stream` 负责降级；直接用本生成器时可自行处理。
        """
        import time
        from .client import WenquError  # 局部导入：client 顶部已 import runs，避免模块级循环

        cursor = max(0, int(last_event_id or 0))
        reconnects = 0
        path = f"{_ROOT}/runs/{_q(run_id)}/events"
        while True:
            headers: Dict[str, str] = {"Accept": "text/event-stream"}
            if cursor > 0:
                headers["Last-Event-ID"] = str(cursor)
            broken: Optional[BaseException] = None
            response: Optional[requests.Response] = None
            try:
                response = self._client._request("GET", path, headers=headers, stream=True)
                for frame in iter_sse(response):
                    seq = int(frame.id) if frame.id and str(frame.id).isdigit() else 0
                    # 服务端续传只回 id > afterId；这里再做一次单调去重防边界重复
                    if seq > 0 and seq <= cursor:
                        continue
                    if seq > 0:
                        cursor = seq
                    name = frame.event or ""
                    try:
                        import json
                        data = json.loads(frame.data)
                    except ValueError as exc:
                        raise ValueError(f"v1 事件帧不是合法 JSON：{frame.data[:200]}") from exc
                    if not isinstance(data, dict):
                        raise ValueError(f"v1 事件帧不是对象：{frame.data[:200]}")
                    yield RunEvent(id=seq, event=name, data=data)
                    if name in ("run.done", "run.error"):
                        return
            except (WenquError, requests.RequestException, ValueError, OSError) as exc:
                if not _retryable(exc):
                    raise
                broken = exc
            finally:
                # 显式释放流式连接，避免连接池被长连接占住
                if response is not None:
                    try:
                        response.close()
                    except Exception:
                        pass
            if reconnects >= max_reconnects:
                raise RuntimeError(
                    f"事件流在终态前断开，重连 {reconnects} 次仍未完成：run={run_id}")
            if reconnect_delay > 0:
                time.sleep(reconnect_delay * (reconnects + 1))
            reconnects += 1
            if on_reconnect:
                on_reconnect(reconnects, cursor)
            # 下一轮带更新后的 Last-Event-ID 重连；broken 只用于排障留痕

    def stream(self, run_id: str, *, last_event_id: int = 0,
               max_reconnects: int = 3, reconnect_delay: float = 0.2,
               on_event: Optional[Callable[[RunEvent], None]] = None,
               on_token: Optional[Callable[[str], None]] = None,
               on_started: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_tool_status: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_plan: Optional[Callable[[Any], None]] = None,
               on_approval_required: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_artifact: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_approval_timed_out: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_done: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_error: Optional[Callable[[Dict[str, Any]], None]] = None,
               on_fallback: Optional[Callable[[Dict[str, Any]], None]] = None,
               ) -> Dict[str, Any]:
        """订阅到终态；``resume_expired`` 时自动 ``get(run_id)`` 降级，返回摘要 dict。

        摘要 ``status`` ∈ succeeded / failed / stopped / resume_expired，
        并带 ``lastEventId`` 与 ``reconnects``。未知事件仍会交给 ``on_event``，
        不因契约只增不删的新增事件而中断。
        """
        summary: Dict[str, Any] = {"status": "failed", "lastEventId": last_event_id,
                                  "reconnects": 0}

        def note_reconnect(attempt: int, cursor: int) -> None:
            summary["reconnects"] = attempt
            summary["lastEventId"] = cursor

        for ev in self.iter_events(run_id, last_event_id=last_event_id,
                                   max_reconnects=max_reconnects,
                                   reconnect_delay=reconnect_delay,
                                   on_reconnect=note_reconnect):
            if on_event:
                on_event(ev)
            summary["lastEventId"] = ev.id or summary["lastEventId"]
            d = ev.data
            if ev.event == "run.started" and on_started:
                on_started(d)
            elif ev.event == "token" and on_token:
                on_token(str(d.get("delta") or ""))
            elif ev.event == "tool.status" and on_tool_status:
                on_tool_status(d)
            elif ev.event == "plan.updated" and on_plan:
                on_plan(d.get("plan"))
            elif ev.event == "approval.required" and on_approval_required:
                on_approval_required(d)
            elif ev.event == "artifact.created" and on_artifact:
                on_artifact(d)
            elif ev.event == "approval.timed_out" and on_approval_timed_out:
                on_approval_timed_out(d)
            elif ev.event == "run.done":
                if on_done:
                    on_done(d)
                summary["status"] = "stopped" if d.get("status") == "stopped" else "succeeded"
                return summary
            elif ev.event == "run.error":
                if on_error:
                    on_error(d)
                if d.get("code") == "resume_expired":
                    detail = self.get(run_id)
                    if on_fallback:
                        on_fallback(detail)
                    summary.update(status="resume_expired", detail=detail)
                else:
                    summary.update(status="failed", error={
                        "code": str(d.get("code") or "internal_error"),
                        "message": str(d.get("message") or ""),
                        "requestId": d.get("request_id"),
                    })
                return summary
        return summary
