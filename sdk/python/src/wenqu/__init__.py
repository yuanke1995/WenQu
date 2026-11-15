"""问渠 WenQu 对外 Python SDK。

几行代码调用问渠的问答、向量、知识库检索、知识库管理与工作流能力::

    from wenqu import WenquClient

    client = WenquClient(base_url="http://host:8090/ai", api_key="sk-xxx")
    reply = client.chat.completions.create(
        model="<智能体id>", messages=[{"role": "user", "content": "你好"}]
    )
"""

from ._version import __version__

from .runs import RUN_ERROR_CODES, RUN_EVENT_NAMES, RunEvent, RunsNamespace
from .client import (
    WenquAPIError,
    WenquAuthError,
    WenquClient,
    WenquError,
    WenquRateLimitError,
)


__all__ = [
    "WenquClient",
    "WenquError",
    "WenquAPIError",
    "WenquAuthError",
    "WenquRateLimitError",
    "__version__",
    "RunsNamespace",
    "RunEvent",
    "RUN_EVENT_NAMES",
    "RUN_ERROR_CODES",
]
