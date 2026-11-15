"""本地 mock 服务：为测试提供问渠 v1 端点的成功样例、SSE 流样例与两种错误体形状。

各端点行为与后端契约对齐：
  - OpenAI 兼容端点（chat/responses/embeddings/models）返回裸 JSON 或 SSE，
    错误体为 ``{"error":{"message","type"}}``；
  - 平台包络端点（retrieval/kbs/workflows）返回 ``{"success":true,"data":...}``，
    错误体为 ``{"success":false,"msg":...}``；
  - 鉴权统一校验 ``X-Api-Key``，不匹配返回 401；
  - 特定入参触发限频 429（检索用 query="TRIGGER_429"，向量用 model="TRIGGER_429"）。
"""

from __future__ import annotations
from urllib.parse import unquote, urlsplit

import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple

API_KEY = "sk-test-123"

_CHAT_PATH = "/api/ai/v1/chat/completions"
_RESPONSES_PATH = "/api/ai/v1/responses"
_EMBEDDINGS_PATH = "/api/ai/v1/embeddings"
_MODELS_EMBEDDINGS_PATH = "/api/ai/v1/models/embeddings"
_RETRIEVAL_PATH = "/api/ai/v1/retrieval"
_KBS_PATH = "/api/ai/v1/kbs"


def _kb_brief(kb_id: str = "kb-demo") -> Dict[str, Any]:
    return {
        "id": kb_id,
        "name": "示例知识库",
        "description": "mock 数据",
        "embeddingRef": "dashscope/text-embedding-v3",
        "docCount": 2,
        "isDefault": 0,
        "createTime": "2026-01-01 00:00:00",
        "updateTime": "2026-01-02 00:00:00",
    }


def _doc_brief(doc_id: str = "doc-demo") -> Dict[str, Any]:
    return {
        "id": doc_id,
        "kbId": "kb-demo",
        "fileName": "手册.pdf",
        "fileType": "pdf",
        "status": 2,
        "statusText": "解析中",
        "chunkCount": 0,
        "parseProgress": 35,
        "parseDesc": "解析中",
        "failReason": None,
        "fileSize": 10240,
        "description": None,
        "tags": None,
        "version": 1,
        "createTime": "2026-01-01 00:00:00",
        "updateTime": "2026-01-01 00:00:00",
    }


def _run_obj(run_id: str = "run-demo") -> Dict[str, Any]:
    return {
        "id": run_id,
        "workflowId": "wf-demo",
        "status": "success",
        "outputs": {"answer": "工作流输出"},
        "nodeTraces": [{"nodeId": "start", "status": "success"}],
        "durationMs": 123,
        "error": None,
    }


class MockHandler(BaseHTTPRequestHandler):
    """按路径路由的极简 mock；expected_api_key 挂在 server 上。"""

    protocol_version = "HTTP/1.1"

    def log_message(self, *args: Any) -> None:  # 保持测试输出干净
        pass

    # ---------- 基础工具 ----------

    @property
    def _expected_key(self) -> str:
        return getattr(self.server, "expected_api_key", API_KEY)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: Any) -> None:
        self._send(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _openai_error(self, status: int, message: str, err_type: str) -> None:
        self._json(status, {"error": {"message": message, "type": err_type}})

    def _envelope_error(self, status: int, msg: str) -> None:
        self._json(status, {"success": False, "msg": msg})

    def _envelope_ok(self, data: Any) -> None:
        self._json(200, {"success": True, "data": data})

    def _route_path(self) -> str:
        """取请求行的 path 部分，再剥离 context-path 前缀 /ai，得到与后端路由一致的相对路径。

        请求行既可能是相对形式 ``/ai/api/...``，也可能是绝对形式
        ``http://host:port/ai/api/...``（keep-alive 连接上客户端复用时会出现）；
        后者若直接按字符串匹配会漏到「路径不存在」。
        """
        path = urlsplit(self.path).path
        if path.startswith("/ai"):
            path = path[len("/ai"):]
        return path

    def _read_json(self) -> Dict[str, Any]:
        raw = getattr(self, "_raw_body", b"")
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return {}

    def _auth_ok(self) -> bool:
        if self.headers.get("X-Api-Key") != self._expected_key:
            self._openai_error(401, "API Key 无效或缺失", "authentication_error")
            return False
        return True

    def _sse(self, frames: List[Tuple[Optional[str], str]]) -> None:
        """写 SSE 流：每帧可选 event 行 + data 行；不带 Content-Length，靠连接关闭收尾。"""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for event, data in frames:
            if event:
                self.wfile.write("event: {}\n".format(event).encode("utf-8"))
            self.wfile.write("data: {}\n\n".format(data).encode("utf-8"))
        self.wfile.flush()
        self.close_connection = True

    # ---------- GET ----------

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler 命名约定)
        if not self._auth_ok():
            return
        path = self._route_path()
        if self._handle_runs_get(path):
            return
        if path == _MODELS_EMBEDDINGS_PATH:
            self._json(200, {
                "object": "list",
                "data": [
                    {
                        "providerId": "dashscope",
                        "providerName": "阿里云百炼",
                        "models": [
                            {"ref": "dashscope/text-embedding-v3", "modelId": "text-embedding-v3", "dimension": 1024},
                        ],
                    },
                    {
                        "providerId": "bge",
                        "models": [
                            {"ref": "bge/bge-large-zh"},
                        ],
                    },
                ],
            })
            return
        if path == _KBS_PATH:
            self._envelope_ok({"knowledgeBases": [_kb_brief()], "total": 1})
            return
        if path.startswith(_KBS_PATH + "/"):
            rest = path[len(_KBS_PATH) + 1:]
            if rest.endswith("/documents"):
                kb_id = rest[: -len("/documents")]
                if kb_id == "kb-403":
                    self._envelope_error(403, "知识库不存在或不可见")
                    return
                self._envelope_ok({"documents": [_doc_brief()], "total": 1})
                return
            if "/" in rest:
                self._envelope_error(404, "路径不存在")
                return
            if rest == "kb-403":
                self._envelope_error(403, "知识库不存在或不可见")
                return
            if rest == "kb-404":
                self._envelope_error(404, "知识库不存在")
                return
            self._envelope_ok(_kb_brief(rest))
            return
        if path.startswith("/api/ai/v1/workflows/"):
            # /api/ai/v1/workflows/{id}/run/{runId}
            parts = path[len("/api/ai/v1/workflows/"):].split("/")
            if len(parts) == 3 and parts[1] == "run":
                self._envelope_ok(_run_obj(parts[2]))
                return
            self._envelope_error(404, "路径不存在")
            return
        self._envelope_error(404, "路径不存在")

    # ---------- POST ----------

    def do_POST(self) -> None:  # noqa: N802
        # 先读完请求体再鉴权，避免 HTTP/1.1 keep-alive 下残留字节串到下一个请求
        length = int(self.headers.get("Content-Length") or 0)
        self._raw_body = self.rfile.read(length) if length else b""
        if not self._auth_ok():
            return
        path = self._route_path()
        if self._handle_runs_post(path):
            return
        if path == _CHAT_PATH:
            self._handle_chat()
            return
        if path == _RESPONSES_PATH:
            self._handle_responses()
            return
        if path == _EMBEDDINGS_PATH:
            self._handle_embeddings()
            return
        if path == _RETRIEVAL_PATH:
            self._handle_retrieval()
            return
        if path == _KBS_PATH:
            body = self._read_json()
            if not body.get("name") or not body.get("embeddingRef"):
                self._envelope_error(400, "知识库名称与 embeddingRef 不能为空")
                return
            kb = _kb_brief("kb-new")
            kb["name"] = body["name"]
            kb["embeddingRef"] = body["embeddingRef"]
            kb["description"] = body.get("description")
            kb["docCount"] = 0
            self._envelope_ok(kb)
            return
        if path.startswith(_KBS_PATH + "/") and path.endswith("/documents"):
            self._handle_upload(path)
            return
        if path.startswith("/api/ai/v1/workflows/") and path.endswith("/run"):
            wf_id = path[len("/api/ai/v1/workflows/"):-len("/run")]
            if wf_id == "wf-404":
                self._envelope_error(404, "工作流不存在或未发布")
                return
            body = self._read_json()
            run = _run_obj("run-" + str(int(time.time() * 1000)))
            run["workflowId"] = wf_id
            run["inputs"] = body.get("inputs")
            self._envelope_ok(run)
            return
        self._envelope_error(404, "路径不存在")

    # ---------- 端点处理 ----------

    def _handle_chat(self) -> None:
        body = self._read_json()
        if body.get("model") == "TRIGGER_ERROR":
            self._openai_error(400, "智能体不存在", "invalid_request_error")
            return
        if body.get("stream"):
            chunk1 = {
                "id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 1,
                "model": body.get("model"),
                "choices": [{"index": 0, "delta": {"role": "assistant", "content": "你好"}, "finish_reason": None}],
            }
            chunk2 = {
                "id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 2,
                "model": body.get("model"),
                "choices": [{"index": 0, "delta": {"content": "，世界"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
            }
            self._sse([
                (None, json.dumps(chunk1, ensure_ascii=False)),
                (None, json.dumps(chunk2, ensure_ascii=False)),
                (None, "[DONE]"),
            ])
            return
        self._json(200, {
            "id": "chatcmpl-mock", "object": "chat.completion", "created": 1,
            "model": body.get("model"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "你好，世界"},
                          "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
        })

    def _handle_responses(self) -> None:
        body = self._read_json()
        if body.get("stream"):
            self._sse([
                ("response.created", json.dumps({"type": "response.created",
                                                  "response": {"id": "resp-mock", "status": "in_progress"}},
                                                 ensure_ascii=False)),
                ("response.output_text.delta", json.dumps({"type": "response.output_text.delta", "delta": "你好"},
                                                           ensure_ascii=False)),
                ("response.output_text.delta", json.dumps({"type": "response.output_text.delta", "delta": "，世界"},
                                                           ensure_ascii=False)),
                ("response.completed", json.dumps({"type": "response.completed",
                                                    "response": {"id": "resp-mock", "status": "completed"}},
                                                   ensure_ascii=False)),
            ])
            return
        self._json(200, {
            "id": "resp-mock", "object": "response", "model": body.get("model"),
            "status": "completed",
            "output": [{"type": "message", "content": [{"type": "output_text", "text": "你好，世界"}]}],
        })

    def _handle_embeddings(self) -> None:
        body = self._read_json()
        if body.get("model") == "TRIGGER_429":
            self._openai_error(429, "请求过于频繁，请稍后重试", "rate_limit_error")
            return
        if not body.get("model"):
            self._openai_error(400, "model 必填（providerId/modelId）", "invalid_request_error")
            return
        raw_input = body.get("input")
        texts = raw_input if isinstance(raw_input, list) else [raw_input]
        if len(texts) > 256:
            self._openai_error(400, "单批最多 256 条", "invalid_request_error")
            return
        base64_mode = str(body.get("encoding_format") or "").lower() == "base64"
        data = []
        for i, t in enumerate(texts):
            if base64_mode:
                vec = "Zm9vYmFy"  # 测试占位：仅验证透传形状
            else:
                vec = [0.1 * (i + 1), 0.2, 0.3]
            data.append({"object": "embedding", "index": i, "embedding": vec})
        out: Dict[str, Any] = {
            "object": "list", "data": data, "model": body.get("model"),
            "usage": {"prompt_tokens": sum(len(str(t)) for t in texts),
                       "total_tokens": sum(len(str(t)) for t in texts)},
        }
        if body.get("dimensions"):
            out["dimension"] = body["dimensions"]
        self._json(200, out)

    def _handle_retrieval(self) -> None:
        body = self._read_json()
        query = body.get("query")
        if not query or not str(query).strip():
            self._envelope_error(400, "query 不能为空")
            return
        if len(str(query)) > 8000:
            self._envelope_error(400, "query 过长（上限 8000 字）")
            return
        if query == "TRIGGER_429":
            self._envelope_error(429, "请求过于频繁，请稍后重试")
            return
        chunks = [
            {"id": "k-1", "text": "问渠是一款 AI 智能体工作台。", "score": 0.91,
             "title": "产品简介", "titlePath": ["手册", "产品简介"], "docId": "doc-1",
             "kbId": "kb-demo", "chunkIndex": 0, "images": []},
            {"id": "k-2", "text": "知识库检索支持混合召回。", "score": 0.78,
             "title": "检索配置", "titlePath": ["手册", "检索配置"], "docId": "doc-2",
             "kbId": "kb-demo", "chunkIndex": 3, "images": ["http://host/ai/img/1.png"]},
        ]
        min_score = body.get("minScore")
        if min_score is not None:
            chunks = [c for c in chunks if c["score"] >= float(min_score)]
        top_k = int(body.get("topK") or 20)
        chunks = chunks[: max(1, min(top_k, 50))]
        data: Dict[str, Any] = {"chunks": chunks, "total": len(chunks)}
        if query == "TRIGGER_WARN":
            data["warnings"] = ["关键词检索失败，仅向量结果"]
        self._envelope_ok(data)

    def _handle_upload(self, path: str) -> None:
        content_type = self.headers.get("Content-Type") or ""
        raw = getattr(self, "_raw_body", b"")
        if not content_type.startswith("multipart/form-data") or b'name="file"' not in raw:
            self._envelope_error(400, "缺少 multipart 字段 file")
            return
        doc = _doc_brief("doc-uploaded")
        if b'name="description"' in raw:
            doc["description"] = "已带描述"
        self._envelope_ok(doc)


    # ---------- v1 run 原语（A2） ----------

    @staticmethod
    def _run_detail(run_id: str, status: str = "running") -> Dict[str, Any]:
        return {
            "runId": run_id, "status": status, "sessionId": "sess-run-1",
            "agentId": "agent-1", "createdAt": "2026-11-15T18:00:00",
            "finishedAt": None if status == "running" else "2026-11-15T18:00:04",
            "errorCode": None, "errorMessage": None, "answer": None, "requestId": "rid-0",
        }

    def _sse_runs(self, frames: List[Tuple[int, str, Dict[str, Any]]]) -> None:
        """写 v1 runs 的 SSE 帧：id(seq) + event(名) + data(扁平对象)。"""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for seq, event, data in frames:
            text = "id: {}\nevent: {}\ndata: {}\n\n".format(
                seq, event, json.dumps(data, ensure_ascii=False))
            self.wfile.write(text.encode("utf-8"))
        self.wfile.flush()
        self.close_connection = True

    def _handle_runs_get(self, path: str) -> bool:
        if not path.startswith("/api/ai/v1/"):
            return False
        m = re.match(r"^/api/ai/v1/runs/([^/]+)(/[^/]+)?$", path)
        if not m:
            return False
        run_id = unquote(m.group(1))
        sub = m.group(2) or ""
        if sub == "":
            self._json(200, self._run_detail(
                run_id, "succeeded" if run_id == "run-expired" else "running"))
            return True
        if sub == "/artifacts":
            self._json(200, {"runId": run_id, "count": 1, "artifacts": [{
                "id": "art-1", "name": "报表.html", "filename": "report.html",
                "kind": "page", "preview": "page", "ext": "html", "size": 128,
                "createdAt": "2026-11-15T18:00:03",
                "downloadUrl": "/ai/artifacts/report.html?expire=1&sig=x",
                "expiresInSeconds": 3600,
            }]})
            return True
        if sub != "/events":
            return False
        last = self.headers.get("Last-Event-ID") or ""
        SEEN_LAST_EVENT_IDS.append(last)
        if run_id == "run-403":
            self._openai_error(403, "该 API Key 未开通 runs.read", "permission_error")
            return True
        from_id = int(last) if last.isdigit() else 0
        started = {"runId": run_id, "sessionId": "sess-run-1",
                   "agentId": "agent-1", "status": "running"}
        done = {"runId": run_id, "status": "succeeded", "finalContent": "收到",
                "usage": {"total": 3}, "sources": [], "artifacts": []}
        frames: List[Tuple[int, str, Dict[str, Any]]] = []
        if run_id == "run-expired":
            frames = [(5, "run.error", {"runId": run_id, "status": "failed",
                "code": "resume_expired", "message": "回放缓冲已过期，请重拉 GET /runs/{id}",
                "request_id": "rid-x"})]
        elif run_id == "run-error":
            frames = [(1, "run.started", started), (2, "token", {"runId": run_id, "delta": "收到"}),
                      (3, "run.error", {"runId": run_id, "status": "failed",
                        "code": "rate_limited", "message": "请求过于频繁", "request_id": "rid-e"})]
        elif run_id == "run-disconnect" and not last:
            # 终态前断开：SDK 应带 Last-Event-ID: 2 重连补 id=3
            frames = [(1, "run.started", started), (2, "token", {"runId": run_id, "delta": "收到"})]
        else:
            frames = [(1, "run.started", started), (2, "token", {"runId": run_id, "delta": "收到"})]
            if run_id == "run-dup" and from_id == 0:
                frames.append((2, "token", {"runId": run_id, "delta": "重复帧"}))
            if run_id == "run-stopped":
                frames.append((3, "run.done", dict(done, status="stopped")))
            else:
                frames.append((3, "run.done", done))
        self._sse_runs([f for f in frames if f[0] > from_id])
        return True

    def _handle_runs_post(self, path: str) -> bool:
        if not path.startswith("/api/ai/v1/"):
            return False
        m = re.match(r"^/api/ai/v1/agents/([^/]+)/runs$", path)
        if m:
            body = self._read_json()
            if not body.get("question"):
                self._openai_error(400, "question 必填", "invalid_request_error")
                return True
            d = self._run_detail("run-new")
            if self.headers.get("Idempotency-Key"):
                d["idempotent"] = True
            self._json(200, d)
            return True
        m = re.match(r"^/api/ai/v1/runs/([^/]+)(/[^/]+)?$", path)
        if not m:
            return False
        run_id = unquote(m.group(1))
        sub = m.group(2) or ""
        body = self._read_json()
        if sub == "/approvals":
            if not body.get("approvalId") or not body.get("decision"):
                self._openai_error(400, "approvalId / decision 必填", "invalid_request_error")
                return True
            self._json(200, {"runId": run_id, "approvalId": body["approvalId"],
                "kind": "tool_approval", "decision": body["decision"], "accepted": True})
            return True
        if sub == "/steer":
            self._json(200, {"runId": run_id, "accepted": bool(body.get("text")),
                "messageId": "m-1" if body.get("text") else ""})
            return True
        if sub == "/stop":
            self._json(200, {"runId": run_id, "stopped": True, "status": "running"})
            return True
        return False


# 每次 /events 请求收到的 Last-Event-ID（空串=没带），供续传断言
SEEN_LAST_EVENT_IDS: List[str] = []


def start_mock_server(api_key: str = API_KEY) -> Tuple[ThreadingHTTPServer, str]:
    """在随机端口启动 mock 服务，返回 (server, base_url)。base_url 含 context-path /ai。"""
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
    server.expected_api_key = api_key  # type: ignore[attr-defined]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    return server, f"http://127.0.0.1:{port}/ai"
