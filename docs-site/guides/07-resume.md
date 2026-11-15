---
title: 断线续传
group: 指南
order: 7
lead: SSE 断在长回答的中间是常态。契约给了序号和续传头，剩下的是客户端别把序号丢了。
---

## 契约给了什么

- 每帧带 `id:`，等于 `seq`，从 1 连续、无空洞；`event:` 是事件名，`data:` 是 JSON。
- 重连时带 `Last-Event-ID: <最后收到的 seq>`，服务端从下一条开始补发。
- 回放缓冲保留 **10 分钟**（Redis 短 TTL）。超出后回一帧
  `run.error(code=resume_expired)`，让调用方改走 `GET /runs/{id}`。

```bash
# 断线后从这里接着听
curl -N __BASE__/api/ai/v1/runs/<runId>/events \
  -H "X-Api-Key: sk-你的Key" \
  -H "Last-Event-ID: 42"
```

## 手写客户端要点

```javascript
let cursor = 0;
let retries = 0;

async function listen(runId) {
  while (retries < 3) {
    const res = await fetch(`${BASE}/api/ai/v1/runs/${runId}/events`, {
      headers: { "X-Api-Key": KEY, ...(cursor ? { "Last-Event-ID": String(cursor) } : {}) },
    });
    if (!res.ok) throw new Error("HTTP " + res.status);

    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += value;
      // 按空行切帧：解析 id: / event: / data:
      let at;
      while ((at = buf.indexOf("\n\n")) >= 0) {
        const frame = buf.slice(0, at);
        buf = buf.slice(at + 2);
        const ev = parseFrame(frame);            // {id, event, data}
        if (ev.id) cursor = Number(ev.id);       // 关键：先记住序号再处理
        if (ev.data?.code === "resume_expired") return fallback(runId);
        handle(ev);
        if (ev.event === "run.done" || ev.event === "run.error") return;
      }
    }
    retries++;                                  // 流被掐断：退避重连
    await sleep(500 * retries);
  }
}

async function fallback(runId) {
  // 缓冲过期：拉终态，不再假装能补齐过程事件
  const detail = await fetch(`${BASE}/api/ai/v1/runs/${runId}`, { headers: { "X-Api-Key": KEY } })
    .then(r => r.json());
  console.log("回放已过期，取终态：", detail.status, detail.output?.finalContent);
}
```

- **先记 `id` 再处理事件**：反过来的话，处理到一半崩了会重复消费。
- 重连用**退避**（500ms 起，最多 3 次），别原地紧循环。
- `resume_expired` 不是错误不是要重试，是一次**降级信号**：改用终态接口。

## SDK 里已经做了

```javascript
const summary = await client.runs.stream(run.runId, {
  onEvent: (ev) => { /* 只会收到新事件，重连不会重复 */ },
  onReconnect: (attempt, cursor) => console.warn(`第 ${attempt} 次重连，从 ${cursor} 继续`),
  onFallback: (detail) => console.warn("回放过期，已取终态", detail.status),
});
```

SDK 记 `id:`、按 `seq` 去重、断线带 `Last-Event-ID` 重连 3 次、`resume_expired` 时自动
`get(runId)` 并回 `summary.status = "resume_expired"`（不抛异常、不留空）。Python 侧行为一致。

## 什么时候必须自己处理

| 场景 | 做法 |
|---|---|
| 客户端在浏览器，用户切后台被节流 | 恢复时先 `GET /runs/{id}`：可能早就结束了，没必要重连 |
| 链路要跨进程接力（A 机断、B 机接） | `seq` 存到共享存储，重连带 `Last-Event-ID` |
| 断线超过 10 分钟 | 放弃补过程，改拉终态；这是设计上的边界，不是故障 |
| 只需要最终结果 | 根本不用事件流：建 run 后轮询 `GET /runs/{id}` 更省事 |

> 续传只覆盖**事件回放**，不覆盖审批卡：卡片超时后会以 `approval.timed_out` 事件收口，
> 别指望重连后还能补交同一张卡。
