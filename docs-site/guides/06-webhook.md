---
title: webhook 验签与幂等
group: 指南
order: 6
lead: 回调是「至少一次」投递。验签防伪造，runId 幂等防重复——两件事都要做，缺一件都会出事。
---

## 什么时候发

智能体运行结束（成功 / 失败 / 停止）后，问渠向该智能体配置的**回调地址** POST 一条事件：

| 事件 | 触发 | 幂等键 |
|---|---|---|
| `run.finished` | v1 run 收口（本页） | `runId`（`uk_wh_run_id` 唯一键） |
| `agent.turn.finished` | 站内会话一轮结束（不在 run 链路上） | `turnId` |

两条事件的边界是**来源**：v1 run 只发 `run.finished`，站内会话只发 `agent.turn.finished`，
不会为同一轮发两次。

## 载荷

```json
{
  "event": "run.finished",
  "runId": "9f2c7a4b8e1d4f6ba0c3d5e7f9012345",
  "sessionId": "…",
  "agentId": "…",
  "agentName": "订单核对助手",
  "apiKeyId": "…",
  "status": "succeeded",
  "finalContent": "已核对，差异 2 处：…",
  "elapsedMs": 28431,
  "contract": "/api/ai/v1/openapi.json"
}
```

- `status` ∈ `succeeded | failed | stopped`；失败时带 `errorCode` / `errorMessage`，**不含堆栈**。
- `finalContent` 超长会截断并注明「请按 runId 回查」——完整正文用 `GET /v1/runs/{id}`。
- 载荷与 `GET /v1/runs/{id}` 同口径，回调里能看到的字段在补查接口里一定有。

## 请求头与验签

```text
POST /your/callback
X-Wenqu-Event: run.finished
X-Wenqu-Run-Id: 9f2c…
X-Wenqu-Signature: sha256=<hex>
Content-Type: application/json
```

签名 = `HMAC-SHA256(secret, 原始请求体)`，十六进制，前缀 `sha256=`。`secret` 是智能体回调配置里的密钥。

```javascript
// Express：必须用 raw body 验签，不能先用 express.json() 解析
import crypto from "node:crypto";

app.post("/callback", express.raw({ type: "application/json" }), (req, res) => {
  const expected = "sha256=" + crypto
    .createHmac("sha256", process.env.WENQU_WEBHOOK_SECRET)
    .update(req.body)                       // Buffer，字节级一致
    .digest("hex");

  const given = req.get("X-Wenqu-Signature") || "";
  const ok = given.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
  if (!ok) return res.status(401).end("bad signature");

  const payload = JSON.parse(req.body.toString("utf8"));
  if (payload.event !== "run.finished") return res.status(204).end();

  // 幂等：runId 唯一。重投不产生第二条业务记录。
  if (db.seen(payload.runId)) return res.status(200).end();
  db.saveRun(payload);
  res.status(200).end();
});
```

```python
# FastAPI：请求体用 bytes 拿，别用 Pydantic 模型先解析
import hmac, hashlib
from fastapi import FastAPI, Request, Response

@app.post("/callback")
async def callback(request: Request):
    raw = await request.body()
    expected = "sha256=" + hmac.new(SECRET.encode(), raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, request.headers.get("X-Wenqu-Signature", "")):
        return Response(status_code=401)
    payload = json.loads(raw)
    if not store.seen(payload["runId"]):
        store.save(payload)
    return Response(status_code=200)
```

**验签三个坑**：① 必须用原始字节（框架若先解析 JSON 再序列化，哈希就对不上）；
② 用恒时比较，别用 `==`；③ 逐字节比较长度，缺失头直接拒绝。

## 重试与幂等

- 失败重试：`60s → 5min → 30min → 2h` 退避，耗尽后停止并留痕（不强投到天荒地老）。
- 只认 **2xx** 为送达；3xx / 4xx / 5xx 都算失败。
- 重投可能带**相同 `runId`**，所以幂等一定要做在 `runId` 上（不是消息体哈希）。
- 建议：先落库（唯一键冲突即忽略）再触发副作用；不要「先发消息再存库」。

## 回调地址的现实约束

- 保存回调地址时过 SSRF 校验：`127.0.0.1` / `localhost` / 内网段一律拒绝，生产必须公网可达。
- 本地验证要绕开保存期校验（投递期不校验），`samples/backend-node/README.md` 给了具体做法——**生产不要用**。
- 回调地址按智能体配置，不是按 Key。同一智能体的所有 run 都回同一个地址。

## 与事件流的关系

webhook 是**兜底**，不是替代：

| | 事件流 SSE | webhook |
|---|---|---|
| 谁主动 | 客户端连着想听多久听多久 | 服务端投给业务后端 |
| 断线 | `Last-Event-ID` 续传（10 分钟缓冲） | 重试 + 幂等 |
| 适合 | 前端要展示过程、审批要交互 | 后端流程推进、跨系统编排 |

两者可以同时用：前端看流，后端收回调，同一个 `runId` 串起来。
