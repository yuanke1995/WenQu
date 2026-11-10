"""知识检索（混合检索直通，平台包络响应）。"""

from __future__ import annotations

from typing import Any, Dict, Optional, Sequence

_RETRIEVAL_PATH = "/api/ai/v1/retrieval"


class Retrieval:
    """``client.retrieval`` 命名空间。"""

    def __init__(self, client: Any) -> None:
        # client 为 WenquClient，以 Any 标注避免循环导入
        self._client = client

    def search(
        self,
        query: str,
        knowledge_base_ids: Optional[Sequence[str]] = None,
        top_k: int = 20,
        min_score: Optional[float] = None,
    ) -> Dict[str, Any]:
        """检索知识库，返回包络内的 ``data``：
        ``{"chunks": [{id,text,score,title,titlePath,docId,kbId,chunkIndex,images}], "total": int, "warnings"?: [str]}``。

        ``query`` 上限 8000 字；``knowledge_base_ids`` 为空时检索该身份可见的全部库；
        ``top_k`` 取值 1-50（默认 20）；``min_score`` 按融合分过滤（0-1）。
        """
        body: Dict[str, Any] = {"query": query}
        if knowledge_base_ids:
            body["knowledgeBaseIds"] = list(knowledge_base_ids)
        if top_k is not None:
            body["topK"] = top_k
        if min_score is not None:
            body["minScore"] = min_score
        return self._client._request_envelope("POST", _RETRIEVAL_PATH, json_body=body)
