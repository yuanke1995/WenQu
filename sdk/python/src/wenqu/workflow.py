"""工作流对外触发（平台包络响应，仅可跑已发布版本，需 workflow.run scope）。"""

from __future__ import annotations

from typing import Any, Dict, Optional

_WORKFLOW_RUN_PATH = "/api/ai/v1/workflows"


class Workflows:
    """``client.workflows`` 命名空间。"""

    def __init__(self, client: Any) -> None:
        # client 为 WenquClient，以 Any 标注避免循环导入
        self._client = client

    def run(self, workflow_id: str, inputs: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """同步触发运行，返回包络内的 run 对象（status/outputs/nodeTraces/durationMs/error）。

        ``inputs`` 为开始节点入参（对应模板里的 ``{{start.key}}``）。
        """
        body: Dict[str, Any] = {"inputs": inputs or {}}
        return self._client._request_envelope("POST", f"{_WORKFLOW_RUN_PATH}/{workflow_id}/run", json_body=body)

    def get_run(self, workflow_id: str, run_id: str) -> Dict[str, Any]:
        """按 runId 查询运行结果，返回包络内的 run 对象。"""
        return self._client._request_envelope("GET", f"{_WORKFLOW_RUN_PATH}/{workflow_id}/run/{run_id}")
