#!/usr/bin/env python3
"""假上游：只在请求带 tools 时把它打回来（P5 §六 的换路重试用具）。

为什么需要它：平台对 chat/omni 模型的 toolCapable 是**默认放行**（管理员没登记就按"能调工具"发 tools），
私有化网关与 vLLM/Ollama 裸部署常不支持 tools，真实表现是上游对带 tools 的请求回 4xx。
本机所有真模型都放行 tools，造不出这个触发条件，于是"上游拒 tools → 本轮换路不挂工具重跑 + 可见降级"
这条链路只能代码走查、无法实测。本脚本就是把那类上游摆到 localhost 上。

用法：
    python3 scripts/fake_upstream_no_tools.py            # 监听 127.0.0.1:7999
    python3 scripts/fake_upstream_no_tools.py 7999

然后把它登记成一个供应商（OpenAI 兼容，地址填 http://127.0.0.1:7999，Key 随意），登记两个 chat 模型：
    fake-no-tools           带 tools → 400；不带 tools → 正常流式回答（验"换路后仍能答 + 降级可见"）
    fake-no-tools-then-500  带 tools → 400；不带 tools → 500（验"换路只重试一次"，不许无限重建死流）

`GET /requests` 回吐收到过的每次请求 [{model, withTools, toolCount}]——换路是否真发生，看这条序列最省事。

判定入口在后端 RagService.looksLikeToolsRejected：错误文本要同时出现 tool/function 与
not support/unsupported/invalid/unknown/unrecognized/unexpected 才会换路，这里按此口径造文案。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_OK = "fake-no-tools"
MODEL_ALWAYS_FAIL = "fake-no-tools-then-500"
TOOLS_REJECT_MSG = ("This model does not support tools / function calling: "
                    "invalid request: unknown parameter 'tools'")
GENERIC_500_MSG = "upstream internal error (not a tools problem)"

REQUESTS = []  # 记录每次请求是否带 tools，供事后核对链路真的换过路


def _sse_chunks(text):
    def chunk(delta, finish=None):
        return "data: " + json.dumps({
            "id": "chatcmpl-fake",
            "object": "chat.completion.chunk",
            "created": 0,
            "model": MODEL_OK,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
        }, ensure_ascii=False) + "\n\n"
    yield chunk({"role": "assistant"})
    step = 24
    for i in range(0, len(text), step):
        yield chunk({"content": text[i:i + step]})
    yield chunk({}, finish="stop")
    yield "data: [DONE]\n\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._json(200, {"object": "list", "data": [
                {"id": MODEL_OK, "object": "model"},
                {"id": MODEL_ALWAYS_FAIL, "object": "model"}]})
        elif self.path.startswith("/requests"):
            self._json(200, REQUESTS)
        elif self.path.startswith("/health"):
            self._json(200, {"available": True})
        else:
            self._json(404, {"error": {"message": "no such path"}})

    def do_POST(self):
        if not self.path.startswith("/v1/chat/completions"):
            self._json(404, {"error": {"message": "no such path"}})
            return
        length = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except Exception as e:  # 上游收到坏请求也要有个交代，别让客户端挂在连接上
            self._json(400, {"error": {"message": "unparseable body: %s" % e}})
            return
        model = str(req.get("model") or "")
        tools = req.get("tools") or []
        REQUESTS.append({"model": model, "withTools": bool(tools),
                         "toolCount": len(tools)})
        # 带 tools 一律打回：这就是"登记为可用、上游不认"的那一类真实故障
        if tools:
            self._json(400, {"error": {"message": TOOLS_REJECT_MSG,
                                       "type": "invalid_request_error"}})
            return
        if model == MODEL_ALWAYS_FAIL:
            self._json(500, {"error": {"message": GENERIC_500_MSG, "type": "server_error"}})
            return
        answer = ("【假上游】本轮没挂工具也照常作答：这一句由不带 tools 的重试流产生，"
                  "用来确认换路后回答仍能到手。")
        body = "".join(_sse_chunks(answer)).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7999
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("fake upstream on http://127.0.0.1:%d  (%s | %s)" % (port, MODEL_OK, MODEL_ALWAYS_FAIL),
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
