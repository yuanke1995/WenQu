package com.wenqu.ai.agent.runcode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.chat.TurnState;
import com.wenqu.ai.agent.tool.ToolEnrichment;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolPipelineFactory;
import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SandboxTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run_code 的子调用通道与实时面板（P6 刀 3a，自 {@code ChatService} 抽出）。
 * <p>两件事长在一起是有原因的：脚本里那一路子调用<b>必须走与模型直调同一条守卫链</b>
 * （叫停、权限档位、引用登记），而它绕开了模型的工具循环，于是"这一次运行调了哪些工具、
 * 各自多久、额度还剩多少"这份账只能在这里记——面板就是这本账的展示面。分开两处必漂移。
 * <p><b>它能做成 bean 而 {@code BranchRunner} 不能</b>：这一族不需要"跑一整轮问答"，
 * 依赖只有工具管线与投递通道，方向天然单向；而分支那条要回头调问答入口，只能按轮回传函数
 * （见 {@code SubAgentOrchestrator} 与刀 3b）。这正是卡里拦路点 2 的答案怎么用。
 * <p>面板只走实时通道：不进 {@code toolCalls}、不进事件账本——它的价值在"当场看得见"，
 * 跑完后终态那一份挂在 run_code 那张卡片上随消息落库，两套真源只会对不上。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RunCodeSubCallRuntime {

    private final ConfigService configService;
    private final ToolRegistry toolRegistry;
    private final ToolPipelineFactory toolPipeline;
    private final ChatRunBus chatRunBus;

    public RunCodeSubCallRuntime(ConfigService configService,
                                 ToolRegistry toolRegistry,
                                 ToolPipelineFactory toolPipeline,
                                 ChatRunBus chatRunBus) {
        this.configService = configService;
        this.toolRegistry = toolRegistry;
        this.toolPipeline = toolPipeline;
        this.chatRunBus = chatRunBus;
    }

    /**
     * 为本轮准备「脚本子调用」的管线（P3b）。
     * <p>
     * 只在真挂了 {@code run_code} 时构建，其余轮次一个对象都不多造（默认关的功能不该给每轮加开销）。
     * <p><b>为什么要再包一条</b>：脚本里的子调用必须与模型直调走<b>同一条守卫链</b>（叫停、权限档位、
     * 引用登记、工具卡片），绕过去就不是"少几次模型往返"而是"少一层管控"。但<b>不能共用同一份步数预算</b>：
     * 一段扇出 30 份资料的脚本会把 15 步的模型预算一次吃光，主模型随后就没工具可用了——那正是 P3b
     * 要解决的问题本身。所以这条管线与本轮同源，只换预算那一项（{@code maxToolSteps}/{@code takeStep}
     * 走本轮的子调用计数，上限是 {@code tool.runcode.maxSubCalls}）。
     */
    public void prepareSubCallPipeline(TurnState st,
                                        java.util.List<org.springframework.ai.tool.ToolCallback> rawTools,
                                        ToolTurnContext turnCtx) {
        boolean runCodeMounted = rawTools.stream().anyMatch(
                cb -> cb.getToolDefinition() != null
                        && SandboxTools.RUN_CODE_TOOL_NAME.equals(cb.getToolDefinition().name()));
        if (!runCodeMounted) return;
        java.util.List<org.springframework.ai.tool.ToolCallback> subRaw = new ArrayList<>();
        for (org.springframework.ai.tool.ToolCallback cb : rawTools) {
            String n = cb.getToolDefinition() == null ? null : cb.getToolDefinition().name();
            if (n == null || SandboxTools.RUN_CODE_TOOL_NAME.equals(n)) continue;
            // 脚本里不给 execute（嵌套命令行）：刀4 用「平台侧回执日志」定位过——四路并发时
            // 平台把四条响应都组装好并写回，容器里的脚本只收到部分，剩下的连接永远等不到返回；
            // 改成流式响应 + 每 2s 写一个心跳空格更糟（一条都送不到）。丢在容器→宿主这条回程上，
            // 本机环境无法从平台侧修好，所以切断入口：命令行请用工具本身（模型直调，看得见且走
            // 平台自己的流式通道）。文件类（读写列目录交付）走文件 API、不碰那条会话，实测正常。
            if (SandboxTools.STREAMING_TOOL_NAME.equals(n)) continue;
            // 「要有人在场」的工具不给脚本：askUser 在脚本里既没人答、又会把整段运行挂住——
            // 判据与本轮装配同一条（描述符 viewerRequired），不在这里再抄一份名字表
            com.wenqu.ai.agent.tool.ToolDescriptor d = toolRegistry.find(n).orElse(null);
            if (d != null && d.viewerRequired()) continue;
            subRaw.add(cb);
        }
        if (subRaw.isEmpty()) {
            log.info("[P3B] 本轮挂了 run_code 但没有可子调用的工具，脚本只能用 Python 自己算");
            return;
        }
        st.tools.subCallTools = new java.util.LinkedHashMap<>();
        for (org.springframework.ai.tool.ToolCallback cb : toolPipeline.wrap(subRaw, subCallTurnContext(st, turnCtx))) {
            String n = cb.getToolDefinition() == null ? null : cb.getToolDefinition().name();
            if (n != null) st.tools.subCallTools.put(n, cb);
        }
        log.info("[P3B] 本轮子调用通道就绪：{} 个工具，上限 {} 次",
                st.tools.subCallTools.size(), configService.getInt("tool.runcode.maxSubCalls", 50));
    }

    /** 本轮 run_code 的子调用入口（按轮闭包；容器侧只看到一次 HTTP 回调） */
    public com.wenqu.ai.agent.runcode.RunCodeBridge runCodeBridge(TurnState st) {
        return new com.wenqu.ai.agent.runcode.RunCodeBridge() {
            @Override
            public String sessionId() {
                return st.sessionId;
            }

            @Override
            public String turnId() {
                return st.turnId;
            }

            @Override
            public String userId() {
                return st.userId;
            }

            @Override
            public java.util.List<com.wenqu.ai.agent.runcode.RunCodeBridge.Spec> callableSpecs() {
                java.util.Map<String, org.springframework.ai.tool.ToolCallback> map = st.tools.subCallTools;
                if (map == null) return List.of();
                List<Spec> out = new ArrayList<>();
                for (org.springframework.ai.tool.ToolCallback cb : map.values()) {
                    out.add(specOf(cb));
                }
                return out;
            }

            @Override
            public String invoke(String tool, String argsJson) {
                java.util.Map<String, org.springframework.ai.tool.ToolCallback> map = st.tools.subCallTools;
                org.springframework.ai.tool.ToolCallback cb = map == null ? null : map.get(tool);
                if (cb == null) {
                    throw new com.wenqu.ai.agent.runcode.RunLimitException(
                            "本轮没有挂载工具「" + tool + "」，脚本调不到模型也用不了的能力");
                }
                String args = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
                // 守卫在包装回调内部：这一句就是「再过一遍叫停闸/权限闸/引用登记」的地方。
                // 工具自己抛的业务异常照常向上传，由回调控制器转成 ok=false（脚本看得懂字段）
                return cb.call(args);
            }

            @Override
            public int maxSubCalls() {
                return Math.max(1, configService.getInt("tool.runcode.maxSubCalls", 50));
            }

            @Override
            public long subCallTimeoutMs() {
                return Math.max(5_000L, configService.getLong("tool.runcode.subCallTimeoutMs", 60_000L));
            }

            @Override
            public int callsUsed() {
                return st.board.subCallCount.get();
            }

            @Override
            public void recordRunOutcome(com.wenqu.ai.agent.runcode.RunCodeService.Outcome outcome) {
                st.board.runOutcome = outcome == null ? null : outcome.asCard();
            }
        };
    }

    /** 工具回调 → Python 客户端的一节（参数名与说明都取自它自己的 inputSchema，脚本侧不必猜） */
    private com.wenqu.ai.agent.runcode.RunCodeBridge.Spec specOf(
            org.springframework.ai.tool.ToolCallback cb) {
        org.springframework.ai.tool.definition.ToolDefinition def = cb.getToolDefinition();
        String name = def == null ? "" : def.name();
        String label = toolRegistry.find(name).map(com.wenqu.ai.agent.tool.ToolDescriptor::displayLabel)
                .orElse(name);
        List<String> params = new ArrayList<>();
        Map<String, String> docs = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        try {
            JSONObject schema = def == null || def.inputSchema() == null
                    ? null : JSON.parseObject(def.inputSchema());
            JSONObject props = schema == null ? null : schema.getJSONObject("properties");
            if (props != null) {
                for (String k : props.keySet()) {
                    params.add(k);
                    JSONObject p = props.getJSONObject(k);
                    if (p != null && p.getString("description") != null) docs.put(k, p.getString("description"));
                }
            }
            JSONArray req = schema == null ? null : schema.getJSONArray("required");
            if (req != null) for (Object o : req) if (o != null) required.add(String.valueOf(o));
        } catch (Exception e) {
            // schema 解析失败不影响这次子调用能用什么：退化成"只给 call_tool，参数由模型自己按工具说明传"
            log.warn("[P3B] 工具 {} 的入参 schema 解析失败（脚本侧只走 call_tool）: {}", name, e.getMessage());
        }
        return new com.wenqu.ai.agent.runcode.RunCodeBridge.Spec(name, label, List.copyOf(params),
                Map.copyOf(docs), List.copyOf(required));
    }

    /**
     * 子调用的本轮上下文：除预算那三项，其余全部转发给本轮那份。
     * <p>刻意<b>不</b>重算身份：{@code toolTurnContext(st)} 在流水线入口线程捕获了 RequestUser 快照，
     * 子调用来自 Tomcat 线程、那里根本没有这个 ThreadLocal——转发（而不是新建）是唯一正确的做法，
     * 也是 P1 那条「身份快照必须在入口线程捕获」结论的复用。
     */
    private ToolTurnContext subCallTurnContext(TurnState st, ToolTurnContext base) {
        final int maxSub = Math.max(1, configService.getInt("tool.runcode.maxSubCalls", 50));
        return new ToolTurnContext() {
            @Override
            public String sessionId() {
                return base.sessionId();
            }

            @Override
            public String userId() {
                return base.userId();
            }

            @Override
            public String turnId() {
                return base.turnId();
            }

            @Override
            public boolean userStopped() {
                return base.userStopped();
            }

            @Override
            public int maxToolSteps() {
                // 子调用有自己的一摞预算，不吃模型的单轮步数（见 prepareSubCallPipeline 注释）
                return maxSub;
            }

            @Override
            public int stepsTaken() {
                return st.board.subCallCount.get();
            }

            @Override
            public void takeStep() {
                st.board.subCallCount.incrementAndGet();
            }

            @Override
            public String approvalMode() {
                return base.approvalMode();
            }

            @Override
            public boolean requiresApproval(String toolName) {
                return base.requiresApproval(toolName);
            }

            @Override
            public long approvalTimeoutMs() {
                return base.approvalTimeoutMs();
            }

            @Override
            public com.wenqu.ai.agent.tool.ToolApprover approver() {
                return base.approver();
            }

            @Override
            public com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
                return base.permissionPolicy();
            }

            @Override
            public Map<String, Object> baseToolContext() {
                return base.baseToolContext();
            }

            @Override
            public Map<ToolFamily, ToolEnrichment> enrichments() {
                return base.enrichments();
            }

            @Override
            public String execute(org.springframework.ai.tool.ToolCallback raw, String arguments,
                                  Map<String, Object> context, ToolInvocation inv, int[] attemptsOut) {
                return base.execute(raw, arguments, context, inv, attemptsOut);
            }

            @Override
            public void observe(ToolInvocation inv, String status, String resultOrError) {
                // 子调用不各开一张顶层卡片：一段脚本扇出 20 次就把过程区刷出 20 行，而它们本来就属于
                // 同一次运行。改成记进 run_code 那张卡的「扇出清单」，点开看结构（这是过程下钻的落点）。
                // 守卫链其它三道（叫停/步数/权限）与引用登记都照常走，只是不再各占一个操作位。
                // <p>start 也过这里：在途那几条与"本轮额度还剩多少"只有起止两侧才知道。
                // 这里只改计数、不发帧——发帧由面板 ticker 每秒合并一帧（见 RUN_PROGRESS_POOL）。
                if ("start".equals(status)) {
                    markSubCallInFlight(st, inv.toolName(), true);
                    return;
                }
                recordSubCall(st, inv.toolName(), status, resultOrError, inv.elapsedMillis());
                markSubCallInFlight(st, inv.toolName(), false);
            }

            @Override
            public void onOutput(ToolInvocation inv, String delta) {
                // 刻意不转发：脚本里再调 execute 时的逐行输出属于那次子调用，
                // 顶层那张 run_code 卡已经在流式转发脚本自己的 stdout 了，两路一起推会互相打断
            }

            @Override
            public String decorateResult(ToolInvocation inv, String result) {
                return base.decorateResult(inv, result);
            }

            @Override
            public String appendSteers(ToolInvocation inv, String result) {
                // 插话是给"正在出字的这一轮"的，脚本的一条子调用不是它的受众：附在这里会让
                // 一句"换个方向"混进 Python 的返回值里，模型下一步看到的是一段脏 JSON
                return result;
            }
        };
    }

    /**
     * run_code 实时面板的下发线程（P3b）。与心跳/看门狗分池，理由与上面那条注释同源：
     * 一次慢客户端的 send 不许连坐别人的心跳。
     * <p><b>发送点刻意不在子调用线程上</b>：扇出六路时六个 Tomcat 线程同时往同一根 SSE 通道 send，
     * 各自的 HTTP 响应被自己刚发的进度帧卡住，脚本就停在第 7 次调用之前直到整段运行超时
     * （本机实测：6 路 execute 扇出，卡片停在「子调用 6 次」120s 不前进）。
     * 现在子调用线程只置脏位，ticker 每秒最多合并成一帧。
     */
    private static final java.util.concurrent.ScheduledExecutorService RUN_PROGRESS_POOL =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "runcode-progress");
                t.setDaemon(true);
                return t;
            });

    /** 面板刷新间隔：1s 一跳，与卡片上那份实时耗时同拍，扇出再密也不会把通道刷成洪水 */
    private static final long RUN_PROGRESS_INTERVAL_MS = 1000L;

    /** 记录一条工具状态：实时 SSE tool_status 事件（短摘要）+ TurnState.toolCalls 累积（全文，done 汇总与持久化用） */
    /**
     * 记一条脚本子调用进 run_code 卡片的扇出清单（P3b 过程下钻）。
     * <p>摘要取结果首行/失败原因前 80 字：够看出这一支在查什么、有没有被挡下，又不至于把卡片变成第二个输出区。
     */
    private static void recordSubCall(TurnState st, String tool, String status,
                                      String resultOrError, long elapsedMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", tool);
        row.put("status", status);
        row.put("elapsedMs", elapsedMs);
        String text = resultOrError == null ? "" : resultOrError.replaceAll("\\s+", " ").trim();
        row.put("brief", text.length() > 80 ? text.substring(0, 80) + "…" : text);
        synchronized (st.board.subCallLog) {
            st.board.subCallLog.add(row);
        }
        st.board.subCallDirty.set(true);
    }

    /** 脚本子调用清单最多带这么多行进卡片（再多只报总数——清单本身不该比结果还长） */
    private static final int SUB_CALL_CARD_ROWS = 40;

    /** 交付类子调用的工具名（扇出清单里数它，卡片才写得出"这段脚本交回了几个文件"） */
    private static final String DELIVER_TOOL_NAME = "deliver_artifact";

    /**
     * 一次 run_code 起头：清掉上一段脚本的扇出清单与在途计数，并开一个每秒合并的下发 ticker。
     * <p>清单的作用域是<b>一次运行</b>：同一轮里连跑两段脚本时，第二张卡片不该显示第一段做过的事。
     * <p>刻意<b>不</b>复位 {@code subCallCount}：那是守卫按轮扣的预算，换一段脚本不会让额度回来，
     * 面板上把它如实标成「本轮额度」。
     */
    public void startRunBoard(TurnState st) {
        st.board.subCallLog.clear();
        st.board.subCallRunning.clear();
        st.board.subCallInFlight.set(0);
        st.board.subCallDirty.set(true);
        st.board.runBoardActive = true;
        stopRunBoardTicker(st);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> f = RUN_PROGRESS_POOL.scheduleWithFixedDelay(() -> {
            try {
                if (!st.board.runBoardActive) {
                    stopRunBoardTicker(st);
                    return;
                }
                if (st.board.subCallDirty.compareAndSet(true, false)) publishRunProgress(st);
            } catch (Exception e) {
                log.debug("[P3B] 进度帧生成失败（不影响脚本执行）: {}", e.getMessage());
            }
        }, RUN_PROGRESS_INTERVAL_MS, RUN_PROGRESS_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        self.set(f);
        st.board.runProgress = f;
    }

    /** 停掉本段脚本的面板 ticker（终态、自停、整轮收尾三条路都走这里，句柄只有一份） */
    public static void stopRunBoardTicker(TurnState st) {
        java.util.concurrent.ScheduledFuture<?> f = st.board.runProgress;
        if (f != null) {
            f.cancel(false);
            st.board.runProgress = null;
        }
    }

    /**
     * 在途计数：start 加、终态减，并置脏位让 ticker 下一秒发一帧。
     * <p>这里<b>不做任何 IO</b>：子调用线程忙着把 HTTP 响应送回去，面板由 ticker 代劳。
     */
    private static void markSubCallInFlight(TurnState st, String tool, boolean started) {
        synchronized (st.board.subCallRunning) {
            st.board.subCallDirty.set(true);
            if (started) {
                st.board.subCallInFlight.incrementAndGet();
                st.board.subCallRunning.merge(tool == null ? "?" : tool, 1, Integer::sum);
                return;
            }
            st.board.subCallInFlight.decrementAndGet();
            String name = tool == null ? "?" : tool;
            Integer left = st.board.subCallRunning.merge(name, -1, Integer::sum);
            if (left == null || left <= 0) st.board.subCallRunning.remove(name);
        }
    }

    /**
     * 组一份扇出清单（run_code 卡片的看板数据）。
     *
     * @param live 实时帧额外带上在途那几条（它们还没有结果可摘要），终态那份只给已完成的
     */
    public Map<String, Object> buildFanOut(TurnState st, boolean live) {
        List<Map<String, Object>> done = new ArrayList<>();
        int delivered = 0;
        synchronized (st.board.subCallLog) {
            for (Map<String, Object> row : st.board.subCallLog) {
                if (row == null) continue;
                done.add(row);
                if (DELIVER_TOOL_NAME.equals(row.get("name")) && "done".equals(row.get("status"))) delivered++;
            }
        }
        List<Map<String, Object>> items = new ArrayList<>(done.size() + st.board.subCallRunning.size());
        items.addAll(done.size() > SUB_CALL_CARD_ROWS ? done.subList(0, SUB_CALL_CARD_ROWS) : done);
        if (live) {
            synchronized (st.board.subCallRunning) {
                for (Map.Entry<String, Integer> e : st.board.subCallRunning.entrySet()) {
                    int n = Math.max(1, e.getValue() == null ? 1 : e.getValue());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("status", "start");
                    row.put("running", n);
                    items.add(row);
                }
            }
        }
        Map<String, Object> fan = new LinkedHashMap<>();
        fan.put("total", done.size());
        fan.put("inFlight", Math.max(0, st.board.subCallInFlight.get()));
        fan.put("items", items);
        if (done.size() > SUB_CALL_CARD_ROWS) {
            fan.put("omitted", done.size() - SUB_CALL_CARD_ROWS);
        }
        // 预算与并发上限：面板要能说出"这一次扇出离上限还有多远"，否则 12/50 和 12/12 看着一样
        fan.put("used", st.board.subCallCount.get());
        fan.put("max", Math.max(1, configService.getInt("tool.runcode.maxSubCalls", 50)));
        fan.put("parallel", Math.max(1, configService.getInt("tool.runcode.maxParallel", 8)));
        if (delivered > 0) fan.put("delivered", delivered);
        return fan;
    }

    /**
     * 下发一帧脚本运行进度（P3b 的实时面板）。
     * <p>只走实时通道：不进 {@code toolCalls}、不进账本——这份数据的价值在"当场看得见"，
     * 跑完后卡片自己带终态那份（随消息落库，刷新后仍点得开），两套真源只会对不上。
     */
    private void publishRunProgress(TurnState st) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", SandboxTools.RUN_CODE_TOOL_NAME);
        payload.put("fanOut", buildFanOut(st, true));
        String json = JSON.toJSONString(payload);
        boolean sent = false;
        try {
            sent = chatRunBus.publishRaw(st.channel.emitter, "runcode_progress", json, st.sessionId);
        } catch (Exception e) {
            log.debug("[P3B] 进度帧走总线失败，改直发: {}", e.getMessage());
        }
        if (sent) return;
        try {
            st.channel.emitter.send(SseEmitter.event().name("runcode_progress")
                    .data("{\"type\":\"runcode_progress\",\"content\":" + json
                            + ",\"sessionId\":\"" + st.sessionId + "\"}"));
        } catch (Exception e) {
            log.debug("[P3B] 进度帧下发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
