#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""沙盒 runtime 契约桩（**本工程自建，非参考实现照搬**）。

用途：在没有容器运行时、拿不到真沙盒镜像（all-in-one-sandbox）的机器上，按
`agent_sandbox` SDK 0.0.30 的 wire 契约应答，让 `AgentSandboxRuntimeClient` 的
file / shell 端点链路可端到端跑通，便于本地开发与回归。

**它不是真沙盒**：不执行真实命令、不落真实文件，所有响应都是固定样例。
真正需要沙盒能力（读写工作区、跑脚本、OCR/技能安装）时，必须用
`PROVISIONER_BACKEND=docker` + 容器运行时 + 沙盒镜像。

用法：
    python3 tools/sandbox-contract-stub.py --port 8899
    # 然后让 provisioner 把沙盒地址指到它：
    MEMORY_SANDBOX_URL_TEMPLATE=http://127.0.0.1:8899 bash deploy/sandbox-provisioner/run.sh restart

已覆盖端点（与 SDK 的 RawFileClient / RawShellClient 对位）：
    POST v1/file/read              POST v1/file/write          POST v1/file/str_replace_editor
    POST v1/file/list              POST v1/file/find           POST v1/file/upload
    GET  v1/file/download?path=    POST v1/shell/exec
"""
from __future__ import annotations

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = "wenqu-local-dev-sandbox-provisioner-token"
WORKSPACE = "/home/gem/user-data/shared/uid/workspace"


def envelope(data, message: str = "ok"):
    """对应 SDK 的统一响应包装 {success, message, data, hint}。"""
    return {"success": True, "message": message, "data": data, "hint": None}


class StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音访问日志
        pass

    def _authorized(self) -> bool:
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self._json(401, {"detail": "invalid provisioner credentials"})
            return False
        return True

    def _json(self, code: int, payload) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_GET(self):  # noqa: N802
        if not self._authorized():
            return
        match = re.match(r"^/v1/file/download\?path=(.*)$", self.path)
        if not match:
            self._json(404, {"detail": "not found: " + self.path})
            return
        # FileResponse 语义：直接回字节流。
        from urllib.parse import unquote
        payload = b"stub-download:" + unquote(match.group(1)).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):  # noqa: N802
        if not self._authorized():
            return
        path = self.path.split("?")[0]
        raw = self._body()
        if path == "/v1/file/read":
            body = json.loads(raw or b"{}")
            target = body.get("file")
            if target == "/missing":
                self._json(404, {"detail": "file does not exist"})
                return
            self._json(200, envelope({"content": "stub line-1\nstub line-2\n", "file": target}))
        elif path == "/v1/file/write":
            body = json.loads(raw or b"{}")
            self._json(200, envelope({
                "file": body.get("file"),
                "bytes_written": len(body.get("content") or ""),
            }))
        elif path == "/v1/file/str_replace_editor":
            body = json.loads(raw or b"{}")
            self._json(200, envelope({
                "output": "stub edited",
                "path": body.get("path"),
                "prev_exist": True,
                "old_content": "",
                "new_content": "",
            }))
        elif path == "/v1/file/list":
            body = json.loads(raw or b"{}")
            self._json(200, envelope({
                "path": body.get("path"),
                "files": [
                    {"name": "notes.md", "path": WORKSPACE + "/notes.md", "is_directory": False,
                     "size": 128, "modified_time": "2026-09-21T12:00:00Z"},
                    {"name": "sub", "path": WORKSPACE + "/sub", "is_directory": True,
                     "size": None, "modified_time": None},
                ],
                "total_count": 2, "directory_count": 1, "file_count": 1,
            }))
        elif path == "/v1/file/find":
            body = json.loads(raw or b"{}")
            self._json(200, envelope({
                "path": body.get("path"),
                "files": [WORKSPACE + "/notes.md", WORKSPACE + "/a.txt"],
            }))
        elif path == "/v1/shell/exec":
            body = json.loads(raw or b"{}")
            command = body.get("command")
            self._json(200, envelope({
                "session_id": "stub-session", "command": command, "status": "completed",
                "output": "stub exec: " + str(command), "console": None, "exit_code": 0,
            }))
        elif path == "/v1/file/upload":
            text = raw.decode("utf-8", errors="replace")
            name = re.search(r'filename="([^"]+)"', text)
            form_path = re.search(r'name="path"\r\n\r\n([^\r]*)\r\n', text)
            filename = name.group(1) if name else "unknown"
            parent = form_path.group(1) if form_path else ""
            self._json(200, envelope({
                "file_path": (parent + "/" + filename) if parent else "/" + filename,
                "file_size": len(raw),
                "success": True,
            }))
        else:
            self._json(404, {"detail": "not found: " + path})


def main() -> None:
    parser = argparse.ArgumentParser(description="沙盒 runtime 契约桩（非真沙盒）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8899)
    parser.add_argument("--token", default=TOKEN, help="与 SANDBOX_PROVISIONER_TOKEN 一致")
    args = parser.parse_args()
    global TOKEN
    TOKEN = args.token
    print("sandbox contract stub on http://%s:%d (token=%s)" % (args.host, args.port, TOKEN))
    HTTPServer((args.host, args.port), StubHandler).serve_forever()


if __name__ == "__main__":
    main()
