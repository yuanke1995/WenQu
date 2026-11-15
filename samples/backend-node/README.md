# backend-node · 建 run + 收 webhook（验签 + 幂等）

Express + `@wenqu/sdk`。浏览器不参与，Key 只在这个进程里。

```bash
cd ../../sdk/js && npm ci && npm run build          # JS SDK 的 dist/ 不入库，必须先构建
cd ../../samples/backend-node && npm install       # file: 引用本地 SDK
npm start                                          # http://127.0.0.1:3010
```

## 让 webhook 真的送得到（仅本地验证）

生产里 `callback_url` 必须是**公网可达**地址：保存期会过 `SsrfGuard`，
`127.0.0.1` / `localhost` / 内网段一律拒（「回调地址不允许指向内网」）。
本地单实例想收回调，绕过保存期校验直写库（投递期不再校验）：

```bash
# 仅本地验证，生产绝对不要这么做 —— 生产请在智能体设置页配置公网 URL 与密钥
mysql -uroot wenqu_ai -e "UPDATE c_ai_agent \
  SET callback_url='http://127.0.0.1:3010/webhook', callback_secret='local-dev-secret' \
  WHERE id='<WENQU_AGENT_ID>';"
```

`callback_secret` 这里写明文是可行的：服务端 `ConfigCryptoService.decrypt` 对非 `RSA:`
前缀原样返回，而管理界面保存时会加密。`.env` 里的 `WENQU_CALLBACK_SECRET` 必须与它一致。
**验证完记得把 `callback_url` 置回 NULL**，免得本地实例停了以后一直重试投递。

## 三条 curl 跑通

```bash
# 1) 建一次 run（真调 v1）
curl -s -X POST http://127.0.0.1:3010/runs -H 'Content-Type: application/json' \
  -d '{"question":"只回复两个字：收到","idempotencyKey":"demo-1"}'

# 2) 等 2~4 秒，看宿主收到了几条 run.finished
curl -s http://127.0.0.1:3010/deliveries

# 3) 重投同一 runId：应回 duplicate:true，deliveries.count 不变
RUN=<上一步的 runId>; BODY="{\"event\":\"run.finished\",\"runId\":\"$RUN\",\"status\":\"succeeded\"}"
SIG="sha256=$(printf %s "$BODY" | openssl dgst -sha256 -hmac local-dev-secret -hex | awk '{print $NF}')"
curl -s -X POST http://127.0.0.1:3010/webhook -H "X-Wenqu-Event: run.finished" \
  -H "X-Wenqu-Run-Id: $RUN" -H "X-Wenqu-Signature: $SIG" -H 'Content-Type: application/json' -d "$BODY"
```

验签用的是 **raw body 原文**：`express.raw` 保留 Buffer，
`createHmac('sha256', secret).update(raw).digest('hex')`，再 `timingSafeEqual` 常量时间比较。
先 `JSON.parse` 再 `stringify` 会因键序/空格变化验不过。幂等按 `X-Wenqu-Run-Id`
（内存 Map；生产换数据库唯一键，服务端投递表已有 `uk_wh_run_id`）。

## 失败路径

- Key 没开 `runs.write` → `POST /runs` 返回 403 + 「该 API Key 未开通「runs.write」能力；
  请在问渠「个人设置 → API 密钥」编辑该 Key 的权限范围后重试」。
- 签名不对 / 密钥不一致 → `/webhook` 401 `{"error":"invalid signature"}`，请求被丢弃。
- 回调一直送不到 → 服务端走 60s / 5min / 30min / 2h 退避重试，重试耗尽会给智能体主人发站内通知；
  检查 `c_ai_webhook_delivery` 的 `status` / `last_error`。
