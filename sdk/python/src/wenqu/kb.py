"""知识库管理（平台包络响应）：库的读、建与文档上传。"""

from __future__ import annotations

import os
from typing import Any, BinaryIO, Dict, Optional, Union

_KBS_PATH = "/api/ai/v1/kbs"


def _kb_path(kb_id: str) -> str:
    return f"{_KBS_PATH}/{kb_id}"


class KnowledgeBases:
    """``client.kbs`` 命名空间。

    scope 约定：读端点需 ``kb.read``；``create`` 与 ``upload_document`` 需 ``kb.write``
    （API Key 默认不放开 kb.write，需在 Key 编辑里显式勾选）。
    """

    def __init__(self, client: Any) -> None:
        # client 为 WenquClient，以 Any 标注避免循环导入
        self._client = client

    def list(self) -> Dict[str, Any]:
        """本人可见的知识库列表，返回包络内 ``data``：``{"knowledgeBases": [...], "total": int}``。"""
        return self._client._request_envelope("GET", _KBS_PATH)

    def get(self, kb_id: str) -> Dict[str, Any]:
        """知识库详情（对外精简视图：id/name/description/embeddingRef/docCount/isDefault/时间戳）。"""
        return self._client._request_envelope("GET", _kb_path(kb_id))

    def list_documents(self, kb_id: str) -> Dict[str, Any]:
        """库内文档列表，返回 ``{"documents": [...], "total": int}``。

        文档 ``status``：0 生效 / 1 弃用 / 2 解析中 / 3 解析失败；
        解析进度看 ``parseProgress``/``parseDesc``，失败原因看 ``failReason``。
        """
        return self._client._request_envelope("GET", f"{_kb_path(kb_id)}/documents")

    def create(
        self,
        name: str,
        embedding_ref: str,
        description: Optional[str] = None,
        **extra: Any,
    ) -> Dict[str, Any]:
        """创建知识库（需 kb.write）。

        ``embedding_ref`` 必填，形如 ``providerId/modelId``（清单见 ``client.models.list_embeddings()``）；
        ``extra`` 透传其余可选字段（如 ``icon``/``queryParams``/``parseParams``）。
        返回新建库的精简视图。
        """
        body: Dict[str, Any] = {"name": name, "embeddingRef": embedding_ref}
        if description is not None:
            body["description"] = description
        body.update(extra)
        return self._client._request_envelope("POST", _KBS_PATH, json_body=body)

    def upload_document(
        self,
        kb_id: str,
        file: Union[str, "os.PathLike[str]", BinaryIO],
        description: Optional[str] = None,
    ) -> Dict[str, Any]:
        """上传文档到知识库（需 kb.write，multipart 字段名 ``file``）。

        ``file`` 可为本地路径或已打开的二进制文件对象；``description`` 可选（≤500 字）。
        同步返回文档行（``status=2`` 解析中），解析与向量化走后台队列，
        进度轮询 ``list_documents``。
        """
        if isinstance(file, (str, os.PathLike)):
            with open(file, "rb") as fh:
                files = {"file": (os.path.basename(str(file)), fh, "application/octet-stream")}
                return self._post_document(kb_id, files, description)
        files = {"file": (getattr(file, "name", "upload.bin"), file, "application/octet-stream")}
        return self._post_document(kb_id, files, description)

    def _post_document(
        self,
        kb_id: str,
        files: Dict[str, Any],
        description: Optional[str],
    ) -> Dict[str, Any]:
        data = {"description": description} if description is not None else None
        return self._client._request_envelope(
            "POST", f"{_kb_path(kb_id)}/documents", files=files, data=data
        )
