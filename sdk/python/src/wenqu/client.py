"""WenquClient：requests.Session 封装、鉴权头、超时与错误映射。"""

from __future__ import annotations

from typing import Any, Dict, Optional

import requests

from .chat import ChatNamespace, Responses
from .embeddings import Embeddings, Models
from .kb import KnowledgeBases
from .retrieval import Retrieval
from .workflow import Workflows


class WenquError(Exception):
    """SDK 错误基类。"""


class WenquAPIError(WenquError):
    """后端返回非 2xx，或平台包络 ``success=false`` 时抛出。

    两种错误体形状（OpenAI 风格 ``{"error":{"message","type"}}`` 与
    平台包络 ``{"success":false,"msg":...}``）统一解析进 ``message``/``error_type``。
    """

    def __init__(
        self,
        message: str,
        status_code: Optional[int] = None,
        error_type: Optional[str] = None,
        payload: Optional[Any] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.error_type = error_type
        self.payload = payload

    def __str__(self) -> str:
        if self.status_code is not None:
            return f"[{self.status_code}] {self.message}"
        return self.message


class WenquAuthError(WenquAPIError):
    """HTTP 401/403：API Key 缺失、无效，或该 Key 未开通所需 scope。"""


class WenquRateLimitError(WenquAPIError):
    """HTTP 429：触发限频，调用方应退避后重试。"""


class WenquClient:
    """问渠对外 API 客户端。

    参数：
      - ``base_url``：形如 ``http://host:8090/ai``（后端 context-path 是 ``/ai``，
        SDK 不自动补全，请连同 ``/ai`` 一起传入）；
      - ``api_key``：对外 API Key（``sk-`` 开头），随每个请求以 ``X-Api-Key`` 头下发；
      - ``timeout``：秒级超时（连接与读取共用），默认 60。流式请求下为两次收包间的
        最大空闲时间。

    能力入口：``chat.completions`` / ``responses`` / ``embeddings`` / ``models`` /
    ``retrieval`` / ``kbs`` / ``workflows``。
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        timeout: float = 60.0,
        session: Optional[requests.Session] = None,
    ) -> None:
        if not base_url:
            raise ValueError("base_url 不能为空")
        if not api_key:
            raise ValueError("api_key 不能为空")
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._session = session if session is not None else requests.Session()
        self._session.headers["X-Api-Key"] = api_key

        self.chat = ChatNamespace(self)
        self.responses = Responses(self)
        self.embeddings = Embeddings(self)
        self.models = Models(self)
        self.retrieval = Retrieval(self)
        self.kbs = KnowledgeBases(self)
        self.workflows = Workflows(self)

    def close(self) -> None:
        """释放底层连接池；客户端不再使用时调用。"""
        self._session.close()

    def __enter__(self) -> "WenquClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # ==================== 内部请求原语 ====================

    def _request(
        self,
        method: str,
        path: str,
        json_body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        files: Optional[Dict[str, Any]] = None,
        data: Optional[Dict[str, Any]] = None,
        stream: bool = False,
    ) -> requests.Response:
        """发起请求并做统一错误映射；成功时返回原始 Response。"""
        url = f"{self.base_url}{path}"
        response = self._session.request(
            method,
            url,
            json=json_body,
            params=params,
            files=files,
            data=data,
            timeout=self.timeout,
            stream=stream,
        )
        if not response.ok:
            # 流式响应出错时错误体尚未读取，先取出再映射
            _ = response.content
            raise self._map_error(response)
        return response

    def _request_envelope(
        self,
        method: str,
        path: str,
        json_body: Optional[Dict[str, Any]] = None,
        files: Optional[Dict[str, Any]] = None,
        data: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """请求平台包络端点（``{success, data}``），成功时返回 ``data``。"""
        payload = self._request(method, path, json_body=json_body, files=files, data=data).json()
        if isinstance(payload, dict) and payload.get("success") is False:
            # HTTP 200 但业务失败的兜底（正常路径由全局异常处理器映射为非 200）
            raise WenquAPIError(
                message=str(payload.get("msg") or payload.get("message") or "调用失败"),
                status_code=None,
                payload=payload,
            )
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            raise WenquAPIError(
                message=f"响应包络缺少 data 对象: {path}",
                payload=payload,
            )
        return data

    def _map_error(self, response: requests.Response) -> WenquAPIError:
        """把非 2xx 响应映射为异常，兼容两种错误体形状。"""
        status = response.status_code
        payload: Any = None
        try:
            payload = response.json()
        except ValueError:
            pass

        message: Optional[str] = None
        error_type: Optional[str] = None
        if isinstance(payload, dict):
            err = payload.get("error")
            if isinstance(err, dict):
                # OpenAI 风格：{"error": {"message", "type"}}
                message = err.get("message")
                error_type = err.get("type")
            elif payload.get("success") is False:
                # 平台包络：{"success": false, "msg": ...}
                message = str(payload.get("msg") or payload.get("message") or "")
                error_type = str(payload.get("code")) if payload.get("code") is not None else None

        if not message:
            text = (response.text or "").strip()
            message = text[:500] if text else f"HTTP {status}"

        kwargs: Dict[str, Any] = {"message": message, "status_code": status, "error_type": error_type, "payload": payload}
        if status in (401, 403):
            return WenquAuthError(**kwargs)
        if status == 429:
            return WenquRateLimitError(**kwargs)
        return WenquAPIError(**kwargs)
