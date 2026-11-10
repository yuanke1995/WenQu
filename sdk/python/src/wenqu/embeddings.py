"""向量化与可用向量模型清单（OpenAI 兼容 embeddings 端点）。"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Union

_EMBEDDINGS_PATH = "/api/ai/v1/embeddings"
_MODELS_EMBEDDINGS_PATH = "/api/ai/v1/models/embeddings"


class Embeddings:
    """``client.embeddings`` 命名空间。"""

    def __init__(self, client: Any) -> None:
        # client 为 WenquClient，以 Any 标注避免循环导入
        self._client = client

    def create(
        self,
        input: Union[str, List[str]],
        model: str,
        encoding_format: Optional[str] = None,
        dimensions: Optional[int] = None,
    ) -> Dict[str, Any]:
        """批量向量化。

        ``model`` 必填，形如 ``providerId/modelId``（清单见 ``client.models.list_embeddings()``）。
        后端限制：单批 ≤256 条、单条 ≤8192 token。
        返回标准 embeddings 响应：``{object, data:[{object,index,embedding}], model, usage, dimension?}``。
        """
        body: Dict[str, Any] = {"input": input, "model": model}
        if encoding_format is not None:
            body["encoding_format"] = encoding_format
        if dimensions is not None:
            body["dimensions"] = dimensions
        return self._client._request("POST", _EMBEDDINGS_PATH, json_body=body).json()


class Models:
    """``client.models`` 命名空间。"""

    def __init__(self, client: Any) -> None:
        self._client = client

    def list_embeddings(self) -> Dict[str, Any]:
        """当前身份可用的向量模型清单（按供应商分组，模型项含 ``ref`` 即 ``providerId/modelId``）。

        返回 ``{object: "list", data: [...]}``；分组内字段按存在性取值，不保证恒在。
        """
        return self._client._request("GET", _MODELS_EMBEDDINGS_PATH).json()
