"""LangChain 可选适配层：把问渠向量与问答端点封装为 LangChain 标准接口。

本模块是薄封装，运行时依赖 ``langchain-core``（安装：``pip install 'wenqu-ai[langchain]'``）。
未安装 langchain-core 时导入本模块会抛出带安装指引的 ImportError；
SDK 核心功能不依赖此处任何内容。
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

try:
    from langchain_core.callbacks import (
        AsyncCallbackManagerForLLMRun,
        CallbackManagerForLLMRun,
    )
    from langchain_core.embeddings import Embeddings as _LCEmbeddings
    from langchain_core.language_models.chat_models import BaseChatModel as _LCBaseChatModel
    from langchain_core.messages import AIMessage, BaseMessage
    from langchain_core.outputs import ChatGeneration, ChatResult
except ImportError as exc:  # pragma: no cover - 可选依赖缺失路径
    raise ImportError(
        "wenqu.langchain_integration 需要 langchain-core，请先安装："
        "pip install 'wenqu-ai[langchain]'（或 pip install langchain-core）"
    ) from exc

from .client import WenquClient

# langchain 消息类型 → OpenAI role
_ROLE_MAP = {"system": "system", "human": "user", "ai": "assistant"}


class WenquEmbeddings(_LCEmbeddings):
    """问渠向量模型适配为 LangChain ``Embeddings`` 接口。

    ``model`` 为 ``providerId/modelId``（清单见 ``client.models.list_embeddings()``）。
    """

    def __init__(
        self,
        client: WenquClient,
        model: str,
        dimensions: Optional[int] = None,
    ) -> None:
        self.client = client
        self.model = model
        self.dimensions = dimensions

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """批量向量化。后端限制单批 ≤256 条，超出请自行分批。"""
        payload: Dict[str, Any] = {"input": list(texts), "model": self.model}
        if self.dimensions is not None:
            payload["dimensions"] = self.dimensions
        resp = self.client.embeddings.create(**payload)
        items = sorted(resp.get("data", []), key=lambda d: d.get("index", 0))
        return [item["embedding"] for item in items]

    def embed_query(self, text: str) -> List[float]:
        return self.embed_documents([text])[0]


class WenquChat(_LCBaseChatModel):
    """问渠智能体问答适配为 LangChain ``BaseChatModel``（非流式）。

    字段 ``model`` 为智能体 id；``client`` 以 Any 声明，兼容 pydantic 校验。
    """

    model: str
    client: Any = None

    def __init__(self, client: WenquClient, model: str, **kwargs: Any) -> None:
        super().__init__(client=client, model=model, **kwargs)

    @property
    def _llm_type(self) -> str:
        return "wenqu-chat"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        payload = [
            {"role": _ROLE_MAP.get(m.type, m.type), "content": m.content}
            for m in messages
        ]
        resp = self.client.chat.completions.create(model=self.model, messages=payload, **kwargs)
        choice = resp["choices"][0]
        message = AIMessage(content=choice["message"]["content"])
        generation = ChatGeneration(message=message)
        llm_output: Dict[str, Any] = {"model_name": resp.get("model", self.model)}
        if resp.get("usage") is not None:
            llm_output["token_usage"] = resp["usage"]
        return ChatResult(generations=[generation], llm_output=llm_output)

    async def _agenerate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[AsyncCallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        import asyncio

        # 底层 requests 为同步实现，异步路径直接委托线程执行，避免伪异步
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, lambda: self._generate(messages, stop, None, **kwargs))
