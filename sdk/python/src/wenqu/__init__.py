"""问渠 WenQu 对外 Python SDK。

几行代码调用问渠的问答、向量、知识库检索、知识库管理与工作流能力::

    from wenqu import WenquClient

    client = WenquClient(base_url="http://host:8090/ai", api_key="sk-xxx")
    reply = client.chat.completions.create(
        model="<智能体id>", messages=[{"role": "user", "content": "你好"}]
    )
"""

from .client import (
    WenquAPIError,
    WenquAuthError,
    WenquClient,
    WenquError,
    WenquRateLimitError,
)

__version__ = "0.1.0"

__all__ = [
    "WenquClient",
    "WenquError",
    "WenquAPIError",
    "WenquAuthError",
    "WenquRateLimitError",
    "__version__",
]
