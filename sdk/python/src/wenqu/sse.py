"""SSE（Server-Sent Events）解析：基于 requests 流式响应的 iter_lines。

后端 OpenAI 兼容端点的流式返回遵循标准 SSE 帧格式：
``data: {...}`` 行 + 空行分隔，结束标记为 ``data: [DONE]``；
Responses API 额外携带 ``event: <事件名>`` 行。
"""

from __future__ import annotations

from typing import Iterator, NamedTuple, Optional

import requests


class SSEEvent(NamedTuple):
    """一条已派发的 SSE 事件：event 名（无则为 None）与 data 负载原文。"""

    event: Optional[str]
    data: str


def iter_sse(response: requests.Response) -> Iterator[SSEEvent]:
    """按 SSE 规范逐条产出事件；``[DONE]`` 原样透传，由调用方决定终止。"""
    # text/event-stream 响应头通常不带 charset，requests 会退化为 latin-1，
    # 中文内容将乱码，这里强制按 UTF-8 解码。
    response.encoding = "utf-8"
    event: Optional[str] = None
    data_lines: list = []
    for line in response.iter_lines(decode_unicode=True):
        if line is None:
            continue
        if line == "":
            # 空行 = 一帧结束，派发
            if data_lines:
                yield SSEEvent(event=event, data="\n".join(data_lines))
            event = None
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
        # 其余字段（id、retry）当前 SDK 不使用
    # 连接关闭时仍有未派发的负载（末帧不带空行的容错）
    if data_lines:
        yield SSEEvent(event=event, data="\n".join(data_lines))
