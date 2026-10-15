# Phase 0 探针结论（AgentScope Java 平移可行性·实测账）

> 本篇只记**跑出来的事实**，不记文档承诺。每条都能按 §0 的命令复现。探针代码与原始日志在 `/tmp/as-probe`（2.0.4）、`/tmp/as-probe21`（2.1.0-BETA1），未入库。
> 上游方案：[wenqu-agentscope-parallel-plan.md](wenqu-agentscope-parallel-plan.md)。本篇对它的 §6、§9.2、§9.3、§9.4 有实质修正。

## 0. 探针环境与跑法

独立 Maven 工程，**不进问渠 pom**（探完再决定要不要引入，避免探针污染主线构建）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" -q -B -DskipTests package
./run.sh DeepSeek probe.SessionProbe        # 主类可换 probe.Probe / probe.ModelSwitch
```

`run.sh` 从本机 MySQL 的 `c_ai_provider`+`c_ai_model` 取一个启用的 chat 模型，`base_url`/`api_key`（RSA 密文）原样传下去，**解密在探针 Java 侧完成**（与 `ConfigCryptoService` 同一把尺：RSA-2048 + OAEP(SHA-256)、256 字节分块、私钥读 `data/secret/config-rsa.key`），密钥不落终端、不落盘。走的是 `OpenAIChatModel.builder().baseUrl(...)`——问渠所有供应商都是 OpenAI 兼容网关，不需要 DashScope 原生 SDK。

## 1. 版本事实：AgentSession 只在 BETA 版里有

| 能力 | `2.0.4`（release） | `2.1.0-BETA1`（latest） |
| --- | --- | --- |
| `HarnessAgent` + `streamEvents()` → `AgentEvent` | ✅ 17 类事件实测出字 | ✅ |
| `AgentSession`（submit/steer/respond/resume/interrupt/pending） | ❌ **jar 里 0 个类** | ✅ `io.agentscope.harness.agent.session.AgentSession` |
| `SessionLog`（seq 游标 + writer 租约 + checkpoint） | 部分（transcript/state store） | ✅ 实测 30 类事件 |
| Central 上可解析的稳定线 | 2.0.1–2.0.4 | 仅 BETA1；另有 `2.0.2-subagent-bugfix` 这种版本名，说明子智能体路径出过线上级 bugfix |

**上游方案 §9.2 那一行「`AgentSession` 几乎全原生替代 TurnRegistry」在稳定版上不成立。** 选 2.0.4 就意味着后台续跑、排队、插话、HITL 待办持久化这几件事仍然要问渠自己写——那正是平移里最贵的部分。

## 2. per-request 模型覆盖：靠「每请求 build 新实例」，不是靠 RuntimeContext

四条实测（2.0.4，`probe.ModelSwitch`）：

1. **同进程两实例各用自己的模型并发跑，不串味**：`AgentTraceMiddleware` 的 `PRE_REASONING` 日志实测分别打 `model=deepseek-flash`（4 次）与 `model=deepseek-v4-pro`（2 次），两路答案各自正确。
2. **`close(a)` 不会带走共用的 `HttpTransport`**：关掉 A 之后 B 再跑仍出字（1327ms）。这条本来有风险——`HttpTransportFactory` 是进程级单例管理器。
3. **`builder.model(String)` 是 build 期静态解析**：源码 `HarnessAgent.java:1536` 直接 `ModelRegistry.resolve(modelId)`，key 只从环境变量读。
4. **`builder.modelResolver(Function<String,Model>)` 只服务子智能体**：源码 javadoc 原文「Sets a resolver for model name strings to Model instances **for subagents**」（`HarnessAgent.java:2004-2007`），不是按请求换模型的口子。

→ **问渠 `resolveChatModel`（per-user 覆盖 + 自带 key）的等价写法**：每请求用该用户的 `baseUrl/apiKey/modelName` 构造 `OpenAIChatModel`，再 `build()` 一个 agent、跑完 close。`ModelRegistry` 的 `DEFAULT` 缓存按模型 id 字符串，自带 key 场景要避开它（用显式 builder，或 `resolve(id, ModelCreationContext)`——它**默认不缓存**，官方理由正是「避免不同租户的 API key、base URL 或 stream 配置复用到同一个模型实例」）。

## 3. AgentSession 覆盖面（2.1.0-BETA1 实测，三场景）

**场景 1 后台执行 + 游标续流 + 运行中插话：全绿。**

- `submit()` 立即返回 `Task(commandId, turnId, runId, status=starting)`，主线程不等；
- 旁观者用 `session.log().readAfter(cursor, n)` 按 `seq` 增量拉事件，实测拿到 **30 类事件**（两场景并集，机器可复跑核对）：`run/start run/end turn/start turn/output turn/completed turn/interrupted inbox/started inbox/applied input/received input/applied step/start step/end context/build request/prepared model/dispatch model/chunk model/end action/start action/end message/user message/assistant tool/requested tool/decision tool/dispatch tool/result state/checkpoint task/changed plan/changed permission/changed migration/baseline`——问渠账本 `c_ai_session_event` 需要的「按发生顺序可枚举」在这里是原生形状，且 `plan/changed`、`permission/changed`、`turn/interrupted` 白送；
- `steer("…回答末尾必须原样加上【关注运费】")` 在工具执行期间送达，**最终回答真的带上了【关注运费】**，且插话以一条 `USER` 消息进了 transcript。这与问渠「随下一个工具步送达」的实测语义一致。

**场景 2 中断与续跑：三条硬缺口，都会咬问渠的现有语义。**

| 实测现象 | 对问渠的后果 |
| --- | --- |
| `interrupt()` 返回 true 之后，**正在跑的工具仍然执行到结束**（9325ms 中断，12424ms 才打「工具执行完毕」） | 中断是协作式的。问渠现在的「停止本轮」会 dispose emitter + revoke 沙盒凭据 + terminate HITL waits，比这更硬；照搬框架语义用户会看到「点了停止、工具还在跑」 |
| 中断那一刻框架往会话写了一条**英文兜底助手消息**：`I noticed that you have interrupted me. What can I do for you?` | 中文产品里冒英文，且它进的是正式 transcript，不是可丢弃的留痕。问渠的「本轮已停止」留痕口径必须自己覆盖掉它 |
| `resume(turnId)` 两次抛错：`Reconcile uncertain tool outcomes first`（工具在飞时被中断）、`Only the latest unfinished task can continue`（同会话已有更新 turn） | 「中断后按 turnId 续跑」不是免费的。`SessionProjection.uncertainToolCalls()` 是对账入口，但**得问渠自己写对账策略**——恰好是「用户在工具跑着时点停止」这个高频动作 |

**场景 3 跨实例接续：全绿。** 关掉旧 `HarnessAgent`、`build()` 一个全新实例、沿用同一 `(userId, sessionId)`：读到 2 条历史消息（`asOfSeq=75`），提问「我的代号是什么」答出「青竹」。

⚠️ 但落盘位置是 **`~/.agentscope/state/<agentId>/<userId>/<sessionId>/agent_state.json`**——在问渠的数据目录之外。容器化要么挂卷，要么显式换 store；多副本必须 `distributedStore(RedisDistributedStore.fromJedis(...))`，否则源码里的 fail-fast 检查会直接 `IllegalStateException`（`HarnessAgent.java:2432-2438`：remote filesystem + 本地 state store 的组合被拒）。顺带一条：`builder.disableSessionPersistence()` 在 2.0.4 主链路上**没有读取点**（源码里只有字段声明与 setter），实测 STRICT 模式关掉它之后跨实例照样续上——想真关得自己传 `stateStore`。

## 4. 工具平移：一处 adapter，不重写 9 族

`AgentTool` 只有 5 个成员，其中 `callAsync` 入参 `ToolCallParam.getInput()` **已经是 `Map<String,Object>`**：

```java
String getName(); String getDescription();
Map<String,Object> getParameters();                     // JSON Schema
Mono<ToolResultBlock> callAsync(ToolCallParam param);
default boolean isReadOnly(); default boolean isReturnDirect();
```

问渠 `ToolFamilyProvider.callbacks(...)` 返回 Spring AI `ToolCallback`（`getToolDefinition()` 给 name/description/inputSchema JSON，`call(String)` 收参数 JSON）。**一个 `ToolCallback → AgentTool` 适配器即可覆盖 9 族**，不需要按 §9.3① 逐族重写 `@Tool`；`ToolRegistry` 的三层过滤（`masterOn`/`agentFlag`/`gateOn`、`guestAllowed`、`policyAllows`+`deniesTool`+`viewerRequired`）继续做唯一真源。

要逐工具深挖时继承 `ToolBase`：它自带 `checkPermissions(Map, PermissionContextState)`、`matchRule`、`generateSuggestions`——问渠 `permission/*` 的三态判定有原生挂载点，不必塞进 middleware。

请求级工具视图也有对口东西：`RuntimeContext.toolRequestConfig(ToolRequestConfig)`，`Toolkit.getToolSchemas(Collection<String>, ToolRequestConfig)` 支持按请求裁剪可见工具。

## 5. 一条必须写进施工口径的坑：框架默认装备很肥

非 STRICT 配置下（只关了 filesystem/shell/web/memory 四类），探针问「换个 sessionId 我的代号是什么」，agent **自主 spawn 了 `general-purpose-subagent`** 去翻工作区和技能，连打 `web_fetch`、`load_skill_through_path` 失败日志，最后回一段 400 词英文报告。加上 STRICT 的 12 个 `disable*` 之后：子智能体日志 0 条，回答变成「不知道——我这里没有任何代号记录可查」，跨会话隔离正确。

→ 平移时**必须显式关干净**，否则框架自带的 subagent / skill / workspace context / tools.json 会与问渠自己的子智能体、技能库、知识库体系打架。`HarnessAgentBuilderSupport` 里子智能体拿的是独立 `maxIters`，主链路 `maxIters` 管不住它。

另外两条白送的对照：`deepseek-flash`（问渠登记的恒思考模型）在框架下自动出 `THINKING_BLOCK_START/DELTA/END`，对得上问渠的 `thinking` 事件；文本增量是 `TEXT_BLOCK_DELTA`，对得上 `token`。

## 6. 对上游方案的修正

| 方案原文 | 实测结论 |
| --- | --- |
| §6 风险1「60+ 事件点」 | **29 个事件名 / 58 处发送点**，全部收口在 `ChatRunRuntime.java:452`；前端 `web/src/api.js:93-104` 27 名 + done/error 正好一一对应，无孤儿 |
| §1 表格与 §9.2「框架无 RAG」 | Central 上有 `agentscope-extensions-rag{,-simple,-bailian,-dify,-ragflow,-haystack}`；只是**不用它**——`RetrievalPipeline` 是问渠资产，包成检索 `@Tool` 才是对的 |
| §6 风险2「模型页未抓取，per-user 覆盖列开放项」 | 已答：无框架级按请求换模型入口，走「每请求 build 新实例」（§2） |
| §6 风险3「会话状态权威已被 AgentSession 大幅消解」 | 稳定版无 AgentSession；BETA1 有且**续跑/插话/跨实例三项实测通过**，但中断语义、英文兜底句、`resume` 两条前置约束需要问渠补（§3） |
| §9.2 `AgentSession` 行 | 仅 2.1.0-BETA1 成立 |
| §9.3①「领域工具重写为 @Tool」 | 一处 `ToolCallback→AgentTool` 适配器即可（§4） |
| §9.4 待验证「guest/deadline/heartbeat」 | 仍未答，探针未覆盖；框架侧无 deadline 概念，guest 靠 `RuntimeContext.userId` 可表达 |
| §9.4 待验证「分布式存储对接」 | 已答：`DistributedStore`/`RedisAgentStateStore`（Jedis/Lettuce/Redisson，单机/集群/哨兵皆可），且默认状态目录在 `~/.agentscope`（§3） |

## 7. 版本选型：**已拍板 2.1.0-BETA1 + AgentSession**（2026-10-14）

理由：方案 §0 的硬要求是「框架完整拥有控制流」，而稳定版帮不到的恰好是问渠最贵的那层。选 BETA 的代价用两道闸兜住——`chat.engine.mode` 默认 `legacy`（BETA 代码不参与执行），Phase 5 双跑数据出来之前不讨论切默认值。

三条实测缺口进 Phase 2 施工清单，不做「顺手兼容」：

| 缺口 | 实测现象 | Phase 2 要做动作 |
| --- | --- | --- |
| 协作式中断 | `interrupt()` 返回 true 后，在飞工具仍跑完（9325ms 中断 → 12424ms 才结束） | 对齐问渠「停止本轮」：dispose emitter + revoke 沙盒凭据 + terminate HITL waits，不能只调 `interrupt()` |
| 英文兜底句 | 中断时框架往 transcript 写 `I noticed that you have interrupted me. What can I do for you?` | 覆盖或剔除该消息，保住中文「本轮已停止」留痕口径 |
| resume 前置约束 | `Reconcile uncertain tool outcomes first` / `Only the latest unfinished task can continue` | 自己写 `SessionProjection.uncertainToolCalls()` 的对账策略；「工具跑着时点停止」是高频动作，必须能续 |

其余两条备选记录在案：走 2.0.4＝在框架外重写后台续跑/排队/插话/待办持久化（等于把 `ChatRunBus`+`c_ai_session_event`+`TurnRegistry` 再实现一遍）；拆多模块把 BETA 隔离在 agentx 里＝依赖不脏，但要先动单模块的构建结构。

## 8. Phase 0 依赖清单（引入时用）

`${as.version}` = **`2.1.0-BETA1`**（§7 已定）。

```
io.agentscope:agentscope-bom:${as.version}            (import)
io.agentscope:agentscope-harness                      核心运行时，HarnessAgent/AgentSession
io.agentscope:agentscope-extensions-model-openai      问渠全部供应商走这条
io.agentscope:agentscope-extensions-redis             多副本 state store（RedisAgentStateStore / RedisDistributedStore）
```

`agentscope-extensions-model-dashscope` 不需要（问渠用的是 compatible-mode OpenAI 网关，不是 DashScope 原生 SDK）。硬依赖 Reactor（`Flux<ChatResponse>`/`Mono<Msg>`），问渠已在 WebFlux/SSE 栈上有 Reactor。Spring Boot 兼容要求文档未写，靠探针实测：2.0.4/2.1.0-BETA1 在无 Spring 上下文的裸 Maven 工程里跑通，说明不绑 Spring 版本。
