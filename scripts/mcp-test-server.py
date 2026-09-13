#!/usr/bin/env python3
"""
最小 MCP Server（streamable HTTP）——用于本地端到端验证「MCP 外部工具接入」：
  python3 scripts/mcp-test-server.py            # 默认端口 8931
  python3 scripts/mcp-test-server.py 8932      # 指定端口

提供两个测试工具：
  - get_current_time: 获取当前日期时间
  - add: 两数相加

配套验证步骤（后端启动后）：
  1. 设置页 → MCP 外部工具 → 开总开关，Server 列表填：
     [{"name":"测试工具箱","url":"http://127.0.0.1:8931","type":"streamable"}]
  2. 聊天提问："现在几点了？用工具查一下" / "用工具算一下 17+25"
  3. 预期：回答下方出现工具调用状态行（转圈→对勾+耗时），回答内容包含工具返回值；
     后端日志出现 [MCP] server 测试工具箱 (streamable) 连接成功。
"""
import json
import sys
import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {
        "name": "get_current_time",
        "description": "获取当前的日期时间（本地时区）。当用户询问现在几点/今天日期时调用。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "add",
        "description": "计算两个数字的和。",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
            "required": ["a", "b"],
        },
    },
]


def call_tool(name, args):
    if name == "get_current_time":
        text = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        return [{"type": "text", "text": "当前时间：" + text}]
    if name == "add":
        total = float(args.get("a", 0)) + float(args.get("b", 0))
        total = int(total) if total.is_integer() else total
        return [{"type": "text", "text": "%s + %s = %s" % (args.get("a"), args.get("b"), total)}]
    return [{"type": "text", "text": "unknown tool: %s" % name}]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        print("[mini-mcp]", fmt % a, flush=True)

    def _send(self, code, obj=None):
        self.send_response(code)
        self.send_header("Mcp-Session-Id", "mini-session-1")
        if obj is not None:
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_header("Content-Length", "0")
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        try:
            msg = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            self._send(400, {"jsonrpc": "2.0", "id": None,
                             "error": {"code": -32700, "message": "parse error"}})
            return
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            # 回显客户端请求的协议版本，最大化兼容
            pv = (msg.get("params") or {}).get("protocolVersion", "2025-03-26")
            self._send(200, {"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": pv,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "mini-mcp", "version": "0.1.0"}}})
        elif method and method.startswith("notifications/"):
            self._send(202)
        elif method == "tools/list":
            self._send(200, {"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            p = msg.get("params") or {}
            self._send(200, {"jsonrpc": "2.0", "id": mid, "result": {
                "content": call_tool(p.get("name"), p.get("arguments") or {}),
                "isError": False}})
        else:
            self._send(200, {"jsonrpc": "2.0", "id": mid,
                             "error": {"code": -32601, "message": "method not found: %s" % method}})

    def do_DELETE(self):  # 客户端结束会话
        self._send(200)

    def do_GET(self):
        self._send(405)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8931
    print("mini MCP server (streamable) listening on 127.0.0.1:%d" % port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
