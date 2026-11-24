# 智能体能力绑定：Phase 2–5 收口记录

- 日期：2026-11-24
- 定位：按 [智能体能力绑定-架构优化与重构方案](智能体能力绑定-架构优化与重构方案.md) 逐项收口；记录做了什么、明确不做什么、为什么。

---

## Phase 2：统一 Agent 解析入口 ✅

### 改动

- 所有消费路径的 `agentService.get(agentId)` 改为 `agentService.resolveForTurn(agentId, null)`：
  - `WenquAgentxTools`（root/child/mention）
  - `SubAgentConsultService`
  - `WorkflowEngine`（subagent 节点）
  - `WorkflowService`（从智能体生成编排）
  - `OpenAiCompatController`（chat/responses）
  - `WenquTeamGateway`
  - `MarketService`（模板安装）
  - `ChatController`（默认智能体兜底）
  - `ChatService` 内部解析（含已删死方法）
- 唯一解析入口 = `AgentService.resolveForTurn`；当前无 override 时行为等价于 `get`（读取可读、未停用行），有 override 时返回历史版本 + patch 视图。

### 验收

- 全量 `mvn test`：483 tests 全绿。
- 未改变任何现有返回语义；仅换解析入口。

---

## Phase 3：执行策略 gate 收薄 ✅（安全部分）

### 改动

- `SpringAiToolAdapter.checkPermissions` 删除重复的 `current.denies(getName())`：
  - 按名禁用已经由 `PermissionContextState` 的 DENY 规则在框架调用本方法前裁掉；
  - 保留本轮档位 null 时 fail-closed 的防御。
- `AgentxFrameworkToolGate` 保留为业务补充（完成检查轮、子轮禁写记忆、中文拒绝文案），不再试图与框架 `PermissionEngine` 抢通用只读/deny 判定。

### 未做（有意）

- 不删除 `AgentxFrameworkToolGate`：框架权限引擎没有「目标完成检查轮」「子智能体禁写记忆」「中文拒绝消息」这三件业务语义；强行删除要么丢失行为，要么在别处再写一遍。
- 不把 `ToolRegistry.requiresApproval` 的 ask 规则全量翻译成 `PermissionRule(ASK)`：动态 MCP/HTTP/工作流工具名在 build 期不完整，统一规则会漏；当前由适配器按工具名 + 登记性质决定 ask，保持 fail-closed。

---

## Phase 4：证据 / 凭据 ✅（评估后保持）

- **证据**：会话日志/`AgentState`/团队事件投影已由框架与现有账本承担，未另造状态机。
- **凭据**：现有 `ConfigCryptoService`（JWE）+ 界面掩码 + MCP/HTTP 装配期解密已满足多租户存储要求；不强行切 DSH 的 credentials record（DSH 配置是进程级单租户，问渠要按 uid/租户隔离）。
- **保留边界**：`SkillStatsStore` / `TurnPromptAssembler` 仍是业务投影；理由见 [Phase1C 框架复用评估](智能体能力绑定-Phase1C-框架复用评估.md)。

---

## Phase 5：删除旧路径 ✅（安全部分）

- 删除 `ChatService` 中确认无调用方的死方法：
  - `resolveTurnAgent`
  - `resolveDelegatedAgent`
  - `emitAgentDelegated`
- 保留旧 `c_ai_agent` 列与 `LegacyAgentBindingAdapter` 计划：**不做一次性删列**。
  - 原因：数据迁移 + 旧客户端/历史快照/市场模板兼容面未收敛；删除列属于独立迁移项目，必须先有双写/回滚方案。
  - 当前状态是「旧列只由业务适配器读，新逻辑走 selection/resolveForTurn」；等新读路径稳定后再做 drop migration。

---

## 总结

- Phase 2：完成，单一 Agent 解析入口。
- Phase 3：安全收薄完成；框架权限引擎与业务补充边界明确。
- Phase 4：证据/凭据保持现状并说明原因，不硬迁。
- Phase 5：死代码已删；旧列/旧适配器保留为待独立迁移项。
- 全量测试：483 tests / 0 failures / 0 errors / 2 skipped。
