---
title: SDK（JS / Python）
group: 参考
order: 4
lead: 双端 SDK 抹平「两套响应形状」与「SSE 续传」，分包只抹一层——它不替你决定用哪条链路。
---

## 现状：仓库内分发，尚未发包

SDK 版本 0.2.0，**未发布到 npm / PyPI**（发包排在方案最后，且契约冻结前不发）。现在两种用法：

```bash
# JS：从仓库装（先构建，dist/ 不入库）
cd sdk/js && npm ci && npm run build
npm i file:/path/to/WenQu/sdk/js

# Python
pip install -e /path/to/WenQu/sdk/python
```

发包后把 `file:` 换成 `@wenqu/sdk`、`-e` 换成 `wenqu-ai`，其余代码不动。

## 建客户端

```javascript
import { WenquClient } from "@wenqu/sdk";

const client = new WenquClient({
  baseUrl: "__BASE__",       // 必带 /ai；SDK 不自动补
  apiKey: process.env.WENQU_KEY,
  headers: { "X-Trace-Id": "your-own" },   // 可选：透传自有链路头
});
```

```python
from wenqu import WenquClient

client = WenquClient(base_url="__BASE__", api_key=os.environ["WENQU_KEY"])
```

`apiKey` 可省略——只走 `client.native` 且经自有后端代理时，凭据在代理那一侧，浏览器不持有 Key。

## 命名空间

| 命名空间 | 覆盖 |
|---|---|
| `chat` | `/v1/chat/completions`（OpenAI 兼容，收集型） |
| `responses` | `/v1/responses`（OpenAI Responses 形状） |
| `embeddings` / `models` | 向量化 / 可用向量模型 |
| `retrieval` | `/v1/retrieval` 混合检索 |
| `kbs` | 知识库读写（写需 `kb.write`） |
| `workflows` | 触发 / 补查工作流 |
| `runs` | **run 原语**：建 run、事件流、审批、插话、停止、产物 |
| `native` | 前端事件协议（L1，实验性） |

## run 原语一行开跑

```javascript
const run = await client.runs.create("agent-id", "帮我核对这份报销单", {
  idempotencyKey: "order-20261115-001",   // 重试不重复建 run
});

const summary = await client.runs.stream(run.runId, {
  onEvent: (ev) => {
    if (ev.event === "token") process.stdout.write(ev.data.delta);
    if (ev.event === "approval.required") {
      // 审批走同一个 run，不是另一个接口
      client.runs.approve(run.runId, ev.data.approvalId, "approve");
    }
  },
  onFallback: (detail) => console.log("回放已过期，已降级为拉取终态", detail.status),
});
console.log(summary.status);   // succeeded / failed / stopped
```

```python
run = client.runs.create("agent-id", "帮我核对这份报销单", idempotency_key="order-20261115-001")

def on_event(ev):
    if ev.event == "token":
        print(ev.data["delta"], end="")
    elif ev.event == "approval.required":
        client.runs.approve(run["runId"], ev.data["approvalId"], "approve")

summary = client.runs.stream(run["runId"], on_event=on_event)
print(summary["status"])
```

事件名、字段与续传语义见[事件契约](#run-events)；`approve/steer/stop/artifacts/get` 都是 `runs` 上的方法。

## 自动续传做了什么

- 记住每条事件的 `id:`（= `seq`），断线后带 `Last-Event-ID` 重连，最多 3 次退避重试；
- 按 `seq` 去重，重连不会把已经处理过的事件再交一遍；
- 回放缓冲已过期（`resume_expired`）时，自动降级为 `GET /runs/{id}` 拉终态，结果经 `onFallback` 交回，**不抛异常、不留空**。

## 版本头

每个请求带 `X-Wenqu-Sdk-Version`。后端 debug 日志比对，提前发现「服务端已升级、客户端还是老版本」的错配。
四处版本号（JS 包清单 / JS 版本源 / Python 包清单 / Python 版本源）由 CI 闸门 `scripts/check-sdk-version.mjs` 对齐。

## 错误类型

```javascript
import { WenquAuthError, WenquRateLimitError, WenquApiError } from "@wenqu/sdk";
```

`WenquAuthError`（401/403，带 `code` 与 `request_id`）、`WenquRateLimitError`（429）、`WenquApiError`（其余）。
Python 侧统一 `WenquError`，字段 `message / status_code / error_type / code / request_id`。
