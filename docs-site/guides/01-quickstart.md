---
title: 快速开始
group: 开始
order: 1
lead: 签一把 Key、跑一次自检、拿到第一个成功响应。三步，五分钟。
---

## 三步走到第一个成功响应

1. **签 Key** —— 问渠「个人设置 → API 密钥」新建。选用途模板（问答 / 检索 / 工作流 / 全量）会自动预选 scope；
   默认集里**没有** `runs.read`、`runs.write`、`kb.write`，要跑 run 原语必须显式勾选。
2. **自检** —— 拿 Key 打一次 `GET /v1/me`：能力范围、发问身份、限流、近 30 天用量一次看清。
3. **首响** —— 复制下面的代码，只改 `baseUrl` 与 Key。

> Key 恒以**签发人本人**身份发问，不存在机器号，也不能改绑别人。给哪个系统对接，就用那个系统归属的账号签发。

## 1. 自检：这把 Key 能干什么

```bash
curl __BASE__/api/ai/v1/me \
  -H "X-Api-Key: sk-你的Key"
```

```json
{
  "key": "客服系统对接",
  "keyPrefix": "sk-3f8a…",
  "scopes": ["chat", "kb.read"],
  "actAs": "u_10086",
  "actAsBinding": "issuer",
  "limits": { "enabled": true, "windowSeconds": 60, "chatPerMinute": 10, "embeddingPerMinute": 60 },
  "usage30d": { "available": true, "calls": 128, "tokens": 96240 },
  "expiresAt": null,
  "contract": "/api/ai/v1/openapi.json"
}
```

- `actAs` 恒为签发人（`actAsBinding=issuer`）；平台没有「代表账号」这一说。
- `limits` 是平台按「桶 + 租户 + 账号/IP」分桶的**当前配置**，不是这把 Key 的独占配额。
- `usage30d.available=false` 表示台账暂不可查，**不等于** 0 次调用。

## 2. 第一条问答（OpenAI 兼容）

```bash
curl -X POST __BASE__/api/ai/v1/chat/completions \
  -H "X-Api-Key: sk-你的Key" \
  -H "Content-Type: application/json" \
  -d '{"model":"<智能体id>","messages":[{"role":"user","content":"你好"}]}'
```

```javascript
// SDK 尚未发布到公共 registry（B1 排在方案最后）。现在从仓库装：
//   npm i file:/path/to/WenQu/sdk/js
import { WenquClient } from "@wenqu/sdk";

const client = new WenquClient({
  baseUrl: "__BASE__",   // 带 context-path /ai，SDK 不自动补
  apiKey: process.env.WENQU_KEY,
});

const r = await client.chat.completions.create({
  model: "<智能体id>",
  messages: [{ role: "user", content: "你好" }],
});
console.log(r.choices[0].message.content);
```

```python
# pip install -e path/to/WenQu/sdk/python
import os
from wenqu import WenquClient

c = WenquClient(base_url="__BASE__", api_key=os.environ["WENQU_KEY"])
r = c.chat.completions.create(
    model="<智能体id>",
    messages=[{"role": "user", "content": "你好"}],
)
print(r.choices[0].message.content)
```

`model` 填**问渠智能体 id**（智能体页可见）。多轮靠重发完整 `messages`——这条链路是无状态的。

> `chat/completions` 是**收集型单块下发**（stream 也只是把结果分块推回，不是逐 token 真增量），
> 长任务、审批、产物、插话都不在这条链路上。要这些能力走 [run 原语](#run-events)。

## 3. 想清楚要去哪一层

| 你想要的 | 去哪 |
|---|---|
| 只在自己页面放一个问答框 | [L0 零代码嵌入](#cap-embed) |
| 自家前端要审批卡 / 产物 / 计划过程 | [L1 前端深度集成](#choose) + [run 原语](#run-events) |
| 业务后端调用、结果要回调 | [L2 后端集成](#choose) + [webhook](#webhook) |
| 在 Claude / Cursor 里直接用问渠 | [MCP 接入](#cap-mcp) |
| 把问渠当检索引擎 | [知识库 API](#cap-kb) |

## 首跑失败对照

| 现象 | 原因 | 怎么办 |
|---|---|---|
| 401 `authentication_error` | 没带或带错 `X-Api-Key` | 头名是 `X-Api-Key`；也接受 `Authorization: Bearer sk-…` |
| 403 `permission_error` | 缺 scope | 报错原文会点名缺哪一项、去「个人设置 → API 密钥」哪一列勾 |
| 404 `not_found_error` | 智能体 id 不存在，或不在签发人可见范围 | 用 `GET /api/ai/agent/available` 拉可用清单 |
| 429 `rate_limit_error` | 限频（问答默认 10 次/分钟） | 退避重试，或让管理员调 `ratelimit.chatPerMinute` |
| 连接被拒 / 404 页面 | `baseUrl` 少了 `/ai` | 完整地址 = 服务地址 + `/ai` + 接口路径 |
