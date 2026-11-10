"""OpenAI 兼容问答：Chat Completions 与 Responses API。"""

from __future__ import annotations

import json
from typing import Any, Dict, Iterator, List, Mapping, Optional, Union

from .sse import iter_sse

_CHAT_COMPLETIONS_PATH = "/api/ai/v1/chat/completions"
_RESPONSES_PATH = "/api/ai/v1/responses"
_DONE = "[DONE]"


class Completions:
    """``client.chat.completions`` 命名空间。"""

    def __init__(self, client: Any) -> None:
        # client 为 WenquClient，此处以 Any 标注避免与 client.py 形成循环导入
        self._client = client

    def create(
        self,
        model: str,
        messages: List[Mapping[str, Any]],
        stream: bool = False,
        **extra: Any,
    ) -> Union[Dict[str, Any], Iterator[Dict[str, Any]]]:
        """发起问答。``model`` 为问渠智能体 id。

        stream=False 返回标准 chat.completion 字典；
        stream=True 返回迭代器，逐条产出 chat.completion.chunk 字典（含 usage 尾帧），
        收到 ``data: [DONE]`` 后自然结束。
        """
        body: Dict[str, Any] = {"model": model, "messages": list(messages), "stream": stream}
        body.update(extra)
        if stream:
            response = self._client._request("POST", _CHAT_COMPLETIONS_PATH, json_body=body, stream=True)
            return _iter_completion_chunks(response)
        return self._client._request("POST", _CHAT_COMPLETIONS_PATH, json_body=body).json()


def _iter_completion_chunks(response: Any) -> Iterator[Dict[str, Any]]:
    for sse in iter_sse(response):
        if sse.data == _DONE:
            return
        yield json.loads(sse.data)


class Responses:
    """``client.responses`` 命名空间（Responses API）。"""

    def __init__(self, client: Any) -> None:
        self._client = client

    def create(
        self,
        model: str,
        input: Union[str, List[Mapping[str, Any]]],
        instructions: Optional[str] = None,
        stream: bool = False,
        **extra: Any,
    ) -> Union[Dict[str, Any], Iterator[Dict[str, Any]]]:
        """发起 Responses 调用。``input`` 为字符串或消息数组。

        stream=True 返回事件迭代器，事件类型含 response.created /
        response.output_text.delta / response.completed / response.failed；
        每个产出 dict 优先取 data 负载内的字段，负载缺 ``type`` 时以 ``event:`` 行名补齐。
        """
        body: Dict[str, Any] = {"model": model, "input": input, "stream": stream}
        if instructions is not None:
            body["instructions"] = instructions
        body.update(extra)
        if stream:
            response = self._client._request("POST", _RESPONSES_PATH, json_body=body, stream=True)
            return _iter_response_events(response)
        return self._client._request("POST", _RESPONSES_PATH, json_body=body).json()


def _iter_response_events(response: Any) -> Iterator[Dict[str, Any]]:
    for sse in iter_sse(response):
        if sse.data == _DONE:
            return
        payload = json.loads(sse.data)
        if isinstance(payload, dict):
            if "type" not in payload and sse.event:
                payload["type"] = sse.event
            yield payload
        else:
            yield {"event": sse.event, "data": payload}


class ChatNamespace:
    """``client.chat`` 命名空间，聚合 completions。"""

    def __init__(self, client: Any) -> None:
        self.completions = Completions(client)
