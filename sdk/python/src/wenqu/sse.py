"""SSE（Server-Sent Events）解析：基于 requests 流式响应的 iter_lines。

后端 OpenAI 兼容端点的流式返回遵循标准 SSE 帧格式：
``data: {...}`` 行 + 空行分隔，结束标记为 ``data: [DONE]``；
Responses API 额外携带 ``event: <事件名>`` 行。
"""

from __future__ import annotations

from typing import Iterator, NamedTuple, Optional

import requests


class SSEEvent(NamedTuple):
    """一条已派发的 SSE 事件：event 名、data 原文、可选的 SSE ``id``（v1 runs 的 seq）。"""

    event: Optional[str]
    data: str
    # 放最后并给默认值：chat/responses 等既有消费方按位置或按 event/data 构造都不受影响
    id: Optional[str] = None


def iter_sse(response: requests.Response) -> Iterator[SSEEvent]:
    """按 SSE 规范逐条产出事件；``[DONE]`` 原样透传，由调用方决定终止。"""
    # text/event-stream 响应头通常不带 charset，requests 会退化为 latin-1，
    # 中文内容将乱码，这里强制按 UTF-8 解码。
    response.encoding = "utf-8"
    event: Optional[str] = None
    event_id: Optional[str] = None
    data_lines: list = []
    for line in response.iter_lines(decode_unicode=True):
        if line is None:
            continue
        if line == "":
            # 空行 = 一帧结束，派发
            if data_lines:
                yield SSEEvent(event=event, data="\n".join(data_lines), id=event_id)
            event = None
            event_id = None
            data_lines = []
            continue
        if line.startswith(":"):
            # 注释行（keep-alive 心跳），忽略
            continue
        if line.startswith("data:"):
            value = line[len("data:"):]
            if value.startswith(" "):
                value = value[1:]
            data_lines.append(value)
        elif line.startswith("event:"):
            name = line[len("event:"):].strip()
            event = name or event
        elif line.startswith("id:"):
            # v1 runs 的 seq：客户端据此回带 Last-Event-ID，断线后只补增量
            event_id = line[len("id:"):].strip()
        # 其余字段（retry）当前 SDK 不使用：服务端没下发，SDK 自己有固定退避
    # 连接关闭时仍有未派发的负载（末帧不带空行的容错）
    if data_lines:
        yield SSEEvent(event=event, data="\n".join(data_lines), id=event_id)
