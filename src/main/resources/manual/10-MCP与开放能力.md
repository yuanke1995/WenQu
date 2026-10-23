# MCP 与开放能力

问渠的开放能力覆盖「接进来」（MCP Client）、「送出去」（MCP Server / 游客分享 / API Key）两个方向。

## MCP Client：接入外部工具

- 在「智能体 → MCP 外部工具」登记自己的 MCP Server（streamable / sse），**按用户隔离连接池**，无全局配置
- 支持连接状态查看、整体重连、临时连通性探测；状态读取默认只查本地已知状态，管理页可显式在线校验（同步远程调用，较慢）
- 登记后把 Server 挂到智能体的工具集即可使用

**连不上怎么排查**（三层取证）：
1. 配置核对：地址、传输类型、鉴权头是否与提供方一致
2. 端点直探：用 curl 直接向 MCP 端点发 initialize（带 `Accept: application/json, text/event-stream`），看能否握手
3. 服务端日志：初始化与 tools/list 是串行两步，超时按两请求计；日志会打异常链根因

## 本地统计 MCP Server（可选，开发者取数端）

问渠自带一个只读统计 MCP Server（`scripts/mcp-stats/server.py`），给「取数助手」这类对话提供本地 `wenqu_ai` 库的真实数据端——让智能体在对话里写 SQL 查业务数据、直接出图。

- **启动**：用 Python 跑 `scripts/mcp-stats/server.py`（依赖装进 WorkBuddy 托管 venv），监听 `127.0.0.1:8765/mcp`（streamable HTTP）
- **登记**：在「智能体 → MCP 外部工具」自行登记（每用户各自登记）：地址填 `http://127.0.0.1:8765/mcp`、类型选 streamable；挂到智能体工具集即可使用
- **能力**：`sql_query`（执行一条只读 SQL，返回 `{columns, rows, row_count}`，不带 `LIMIT` 自动补 `LIMIT 200`）+ `db_overview`（核心表行数与时段摸底）；白名单仅 `SELECT/SHOW/DESC/EXPLAIN`，禁一切写关键字，结果强制限量、强制 JSON 可序列化——只碰本地库、不触任何线上写入
- **范围**：连接是本机回环，只适合开发机/单机部署，不要对外暴露

## MCP Server：把智能体发布为工具

- 智能体发布时开启 **MCP 端点**：`/ai/mcp/{token}`（Streamable HTTP，无状态），Claude Desktop / Cursor 等客户端粘贴地址即可调用该智能体
- token 即凭据（与网页分享同源），停用/撤销立即失效
- 按发布者身份检索，能力收窄同游客模式
- 总开关 `mcp.server.enabled` 默认关

## 平台级 MCP 入口

`/ai/mcp` 一个地址暴露整套能力（检索知识库 / 提问 / 列可见知识库 / 列可用智能体）。凭据为 API Key，且需在「API 密钥」中打开该 Key 的「MCP」开关。可见范围与 Key 创建者在网页上看到的完全一致。

## API Key 与游客分享

- **API Key**：仅存 SHA-256 哈希 + 前 8 位前缀，明文仅签发时返回一次；权限固定为问答链路——即便持有 Key 也访问不了管理端点
- **游客分享**：智能体公开发布生成 `/s/{token}` 免登录对话；会话只读分享 `/shared/{token}` 可把一段对话发给他人查看（不能续聊）
- 游客链路 IP 限频、工具白名单收窄、不携带个人记忆
