package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/**
 * 按 {@code (userId, sessionId)} 持有框架会话（方案 §6 风险3 的最终形态：AgentSession 原生接管轮次）。
 * <p>为什么要常驻而不是「每请求建一个用完就关」：文档与实测都确认，待办持久化（{@code pending()}）、
 * 答复后续跑（{@code respond} 自动接续原任务与工具调用，应用不必拼工具 ID）、断线按游标读增量事件
 * 这几件事挂在 {@code AgentSession} 上，而它必须活到后台执行结束。用完即关的实例等于把这些能力又丢掉。
 * <p>与 per-user 模型覆盖的冲突这样解：实例按「会话本轮使用的模型引用」键控，
 * <b>模型换了就重建实例并接管同一条会话</b>——Phase 0 已实测换一个全新实例、沿用同一
 * {@code (userId, sessionId)} 能读到完整历史并接着答，所以接续不靠实例永生，靠框架的会话存储。
 * <p>待办的归属同时落 {@code c_ai_tool_approval}，所以进程重启后还能从表里取回 {@code (userId, sessionId,
 * turnId)} 重建实例接回那一张卡（{@link ResumeHandler#resume}）——唤醒句柄在 {@link AgentSession} 上、
 * 随进程没了，但状态在框架的 stateStore 里，缺的只是「把实例按归属重建回来」这一步。
 */
@Component
public class FrameworkSessions {

    private static final Logger log = LoggerFactory.getLogger(FrameworkSessions.class);

    /** 常驻实例上限：超出的按最久未用关闭。框架实例带工作区与传输层，不能有上限地攒。 */
    private static final int MAX_HELD = 200;

    /**
     * 常驻实例。
     *
     * @param bindingKey 建实例时用到的智能体配置指纹（见 {@code AgentscopeChatEngine#bindingOf}）。
     *                   人设、工具挂载、技能范围都是 <b>build 期</b>定死的，换了智能体或改了它的配置
     *                   而只比 {@code modelRef} 的话，旧实例会继续用上一个人的提示词与工具表作答。
     */
    private record Held(HarnessAgent agent, AgentSession session, String modelRef, String bindingKey) {
    }

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    /** 待办 → 所属会话：{@code /tool-approval/{approvalId}} 只带得到审批号，得靠它找回是哪条会话。 */
    private final Map<String, String> pendingOwner = new ConcurrentHashMap<>();
    /**
     * 每条常驻会话的步数预算闸：middleware 只能在 build 期登记进实例，而它的计数按「轮」归零，
     * 所以引擎要在开轮时拿到<b>这一个实例自己的</b>那把闸——放进本表随实例一起淘汰，
     * 模型切换重建实例时旧的自动作废（键相同、直接覆盖）。
     */
    private final Map<String, AgentxStepBudget> stepBudgets = new ConcurrentHashMap<>();

    /**
     * 框架工具的档位闸（全框架化 P1）。形态与 {@link #stepBudgets} 相同：
     * 闸随实例常驻，登记后由引擎在开轮时更新当前轮标签。
     */
    private final Map<String, AgentxFrameworkToolGate> frameworkToolGates = new ConcurrentHashMap<>();

    /**
     * 每条常驻会话「交给组管理的注册名」（工具面第一刀）。
     * <p>就是建实例那一轮注册进 Toolkit 并建了组的那批名字。每轮激活集<b>只在这份名单里增删</b>，
     * 名单外的组一律不碰——框架的技能工具组是它自己按技能激活的，整份覆盖会把那些一起关掉。
     */
    private final Map<String, java.util.Set<String>> groupedSurfaces = new ConcurrentHashMap<>();

    /**
     * 每条常驻会话的「当前轮」槽位。
     * <p>实例与工具表按会话常驻，而引用池/通道/轮号/咨询台账是<b>一轮</b>的——
     * 工具只能捕获这个会话级槽位，由引擎在每轮 {@code submit} 前换掉里面的 {@code Turn}。
     * <b>重建实例时不换槽位</b>：工具闭包指向它，换一份新的会让已注册的工具失去落点；
     * 只在没有实例、也没有在跑的轮、且总量超过高水位时清理，避免长跑进程按会话无限增长。
     */
    private final Map<String, AgentxTurnSlot> turnSlots = new ConcurrentHashMap<>();

    /** 取（必要时创建）这条会话的当前轮槽位。开轮与建实例都认同一份。 */
    public AgentxTurnSlot turnSlot(ChatCommand cmd) {
        return turnSlots.computeIfAbsent(key(cmd), k -> new AgentxTurnSlot());
    }

    /** 按会话键取槽位；没有就创建一个（续跑/接回那条路手里只有 key）。 */
    public AgentxTurnSlot turnSlot(String sessionKey) {
        return turnSlots.computeIfAbsent(sessionKey, k -> new AgentxTurnSlot());
    }
    /**
     * 哪几条会话此刻有「把这一轮跟到收尾」的循环。
     * <p>这不是实例存活判据（那是 {@link #held}）：循环有墙钟上限、也会因超时退出，退了实例还在。
     * 答复端点要问的是「我这次答完，有没有人接着把这一轮跑完并落库」——只有这个表能答。
     */
    private final Map<String, Boolean> followers = new ConcurrentHashMap<>();
    /**
     * 待办的持久化落点（同一张 {@code c_ai_tool_approval}）。
     * <p>不落这一行的后果是用户看得见的具体问题：<b>刷新页面后框架引擎的确认卡/提问卡/计划卡取不回来</b>
     * ——三个 {@code /*&#47;pending} 恢复端点都是按这张表列的，legacy 有、框架原先没有。
     */
    private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;

    /**
     * 沙盒装配（框架化P1）：按官网「自管沙箱实例」的 {@code externalSandbox} 模式，
     * 在建会话实例时把本轮沙盒上下文挂进 {@link RuntimeContext}——框架的 {@code SandboxLifecycleMiddleware}
     * 从这里取到沙箱，只在 call 期间借用，call 结束只 stop() 不 shutdown()。
     */
    private final com.wenqu.ai.service.SandboxService sandboxService;

    public FrameworkSessions(
            com.wenqu.ai.agent.hitl.HitlDecisionService hitl,
            com.wenqu.ai.service.SandboxService sandboxService,
            SessionLogArchive archive) {
        this.hitl = hitl;
        this.sandboxService = sandboxService;
        this.archive = archive;
    }

    /** 会话事件日志的归档出口（框架 SessionExportSink，开关关闭时不挂） */
    private final SessionLogArchive archive;

    /**
     * 进程重启后的续跑入口（由引擎在装配期登记）。
     * <p>为什么要它：待办的唤醒句柄在 {@code AgentSession} 上，进程一重启就没了。此时若只回一句
     * 「已批准」，用户看到的是一次成功的操作加上一件什么都没发生的事；而框架的会话状态本来就持久化在
     * {@code stateStore} 里，完全可以从表里取回归属、把这条待办重新接上跑完并落库。
     */
    public interface ResumeHandler {
        /**
         * @param kind approval（工具确认）/ ask（提问卡）/ plan（计划卡）
         * @return 接续出来的那条任务；null = 接不动，调用方按失效处理
         */
        AgentSession.Task resume(String sessionId, String userId, String turnId, String requestId,
                                 String kind, boolean approved, String reason, String answer);

        /**
         * 答复已经送进框架会话，但这一轮没有活着的跟随者——补起一个把它跟到收尾并落库。
         * <p>为什么要它：跟随循环有墙钟上限（{@code chat.sseTimeoutMs}），到点就退；退了之后卡片仍可答复，
         * 框架也会重新起一轮执行，但没有人在那儿读事件、也没有人落库，用户看到的就是一次「已批准」
         * 加上一件什么都没发生的事（真机实测：续跑跑完了、正文却没落库）。
         */
        void follow(String sessionId, String userId, String turnId);
    }

    private volatile ResumeHandler resumeHandler;

    public void bindResume(ResumeHandler handler) {
        this.resumeHandler = handler;
    }

    /**
     * 进程重启后接回一条待办：归属只在这张表里，所以先查行再交给引擎重新起一轮。
     *
     * @return null = 没有这条记录或引擎接不动（调用方回落旧通道，按失效处理）
     */
    AgentSession.Task resume(String requestId, String kind, boolean approved, String reason, String answer) {
        ResumeHandler h = resumeHandler;
        if (h == null || requestId == null) return null;
        var row = hitl.findApproval(requestId);
        if (row == null) return null;
        AgentSession.Task task = h.resume(row.getSessionId(), row.getUserId(), row.getTurnId(), requestId,
                kind, approved, reason, answer);
        if (task == null) return null;
        // 接回来之后这条待办又回到「进程内有主」的状态：归属、终态都在这一处落，三条通道不各写一遍
        pendingOwner.put(requestId, (row.getUserId() == null || row.getUserId().isBlank()
                ? "guest" : row.getUserId()) + "|" + row.getSessionId());
        hitl.resolveFrameworkPending(requestId, "ask".equals(kind) ? "ANSWERED"
                : (approved ? "APPROVED" : "REJECTED"), null, answer != null ? answer : reason);
        log.info("[agentx] 重启后接回待办 {}（{}）：已重新起一轮续跑 session={}",
                requestId, kind, row.getSessionId());
        return task;
    }

    /**
     * 框架待办出现时登记归属并落库。
     *
     * @param toolName 确认类填真实工具名；提问卡填 {@code askUser}；计划卡填 {@code planApproval}（与手写侧同词，
     *                 恢复端点就是按这两个名字分流的）
     */
    public void recordPending(ChatCommand cmd, String sessionKey, String turnId, String requestId,
                              String toolName, String requestArgs) {
        if (requestId == null || requestId.isBlank()) return;
        pendingOwner.put(requestId, sessionKey);
        hitl.recordFrameworkPending(requestId, cmd.getSessionId(), cmd.getUserId(), turnId, toolName, requestArgs);
    }


    /**
     * 取这一轮的会话。{@code agentFactory} 只在需要新建、模型已换、或智能体绑定已变时被调用
     * （框架侧建实例有成本，每请求都建就失去常驻的意义）。
     *
     * @param bindingKey 本轮智能体配置指纹：与建实例时那份不同就必须重建。人设
     *                   （{@code sysPrompt(rolePrompt(...))}）、工具挂载（{@code tools.frameworkTools}）、
     *                   技能白名单（{@code .skillFilter}）全在 build 期固化，实例复用时不会重算。
     *                   传空串表示「本轮没有智能体绑定」（默认助手），与真正绑定了智能体的实例区分开。
     */
    public AgentSession open(ChatCommand cmd, String modelRef, String bindingKey,
                             Function<RuntimeContext, HarnessAgent> agentFactory) {
        String key = key(cmd);
        String wantBinding = bindingKey == null ? "" : bindingKey;
        Held current = held.get(key);
        // /compact 强制压缩那一实例的 triggerMessages(1) 只应作用一轮：下次开轮丢掉它、用默认配置重建。
        // 两个标记都要取走（用 | 而不是 ||，否则第二个 remove 被短路、标记永久留着）。
        boolean stale = rebuildAfterCompact.remove(key) | staleConfigKeys.remove(key);
        if (stale && current != null) {
            closeQuietly(key, current);
            held.remove(key);
            current = null;
        }
        if (current != null && current.modelRef().equals(modelRef)
                && current.bindingKey().equals(wantBinding)) {
            touch(key);
            return current.session();
        }
        if (current != null) {
            // 旧实例不再能用：模型、人设、工具表、技能范围都只能在 build 期定。关掉并接管同一条会话
            // ——会话状态在 stateStore 里，重建不丢历史（与换模型同一套依据）。
            boolean modelChanged = !current.modelRef().equals(modelRef);
            closeQuietly(key, current);
            held.remove(key);
            if (modelChanged) {
                log.info("[agentx] 会话 {} 模型切换 {}→{}，重建实例并接管同一会话", key, current.modelRef(), modelRef);
            } else {
                log.info("[agentx] 会话 {} 智能体换绑/改配置（{}→{}），重建实例并接管同一会话",
                        key, current.bindingKey(), wantBinding);
            }
        }
        String ctxUid = cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId();
        RuntimeContext.Builder ctxBuilder = RuntimeContext.builder()
                .userId(ctxUid)
                .sessionId(cmd.getSessionId());
        // 沙盒上下文（框架化 P1）：挂上后框架的文件工具与 ShellExecuteTool 才有沙箱可用。
        // 取不到时返回 null（沙盒不可用 / 无会话），此时本轮不挂框架沙箱，
        // 形态与 P1 之前一致——模型退回无沙箱工具，而不是整轮失败。
        if (sandboxService != null) {
            String uid = cmd.getUserId();
            String sessionId = cmd.getSessionId();
            if (uid != null && !uid.isBlank() && sessionId != null && !sessionId.isBlank()) {
                try {
                    io.agentscope.harness.agent.sandbox.SandboxContext sandboxCtx =
                            sandboxService.frameworkSandboxContext(sessionId, uid, /* unrestrictedWrites */ false);
                    if (sandboxCtx != null) {
                        ctxBuilder.put(io.agentscope.harness.agent.sandbox.SandboxContext.class, sandboxCtx);
                        // 关键：工具真正用的是 AbstractFilesystem，不是 SandboxContext（真机踩出来的）。
                        // FilesystemTool / ShellExecuteTool 解析 ctx.get(AbstractFilesystem.class)，
                        // 而 HarnessAgent#ensureSessionDefaults(:1087) 只透传 SandboxContext。
                        // 只挂前者时模型调 execute("pwd") 拿到的是宿主项目目录——
                        // 工具静默跑在宿主上，看着成功实则没隔离。
                        io.agentscope.harness.agent.filesystem.AbstractFilesystem fs =
                                sandboxService.frameworkFilesystem(sessionId, uid, /* unrestrictedWrites */ false);
                        if (fs != null) {
                            ctxBuilder.put(io.agentscope.harness.agent.filesystem.AbstractFilesystem.class, fs);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("[agentx] 框架沙箱上下文注入失败（session={}）：{}", sessionId, e.toString());
                }
            }
        }
        // 本轮落点槽位必须进 RuntimeContext：重建实例时 buildAgent 里的工具闭包要捕获它，
        // 而工具调用发生在框架的弹性线程上，只有 ctx 里的这一份能保证「同一个会话同一个槽位」。
        ctxBuilder.put(AgentxTurnSlot.class, turnSlot(key));
        // 事件日志归档（框架的导出 outbox）：sink 挂在 RuntimeContext 上，框架建 SessionRecorder 时
        // 从这一份取（ReActAgent 认 SessionExportSink.CONTEXT_KEY），此后每轮心跳按持久游标增量送。
        // 按会话各挂一个 sink：事件本身不带会话号，写哪个文件只能由 sink 自己带着归属。
        if (archive != null && archive.enabled()) {
            ctxBuilder.put(io.agentscope.core.session.SessionExportSink.CONTEXT_KEY,
                    archive.sinkFor(ctxUid, cmd.getSessionId()));
        }
        RuntimeContext ctx = ctxBuilder.build();
        HarnessAgent agent = agentFactory.apply(ctx);
        AgentSession session = agent.session(ctx);
        session.start();
        held.put(key, new Held(agent, session, modelRef, wantBinding));
        touch(key);
        evictOverflow();
        return session;
    }

    /** 登记这条会话本轮建了组的注册名（引擎在 buildAgent 里回调，与 stepBudget 同一套登记口）。 */
    public void noteGroupedSurface(String sessionKey, java.util.Set<String> names) {
        if (sessionKey == null || names == null || names.isEmpty()) return;
        groupedSurfaces.put(sessionKey, java.util.Set.copyOf(names));
    }

    /**
     * 把本轮的工具面写成框架的组激活集，并与老装配判据<b>双跑比对</b>（工具面第一刀）。
     *
     * <p><b>为什么每轮都要写</b>：激活集是持久化在会话状态里的（{@code tool_context.activated_groups}），
     * 而实例按会话常驻、换绑重建也不重置它——只在 build 期写一次，第二轮起模型看到的就是上一任配置的
     * 那一份面。{@code initialActiveToolGroups} 只在<b>首次</b>建态时生效，不是每轮的默认值。
     *
     * <p><b>为什么是相对更新</b>：只在本刀管理的名单里增删。框架的技能工具组由它自己按技能激活，
     * 整份覆盖会把那些一并关掉。
     *
     * <p><b>写不动时选哪一头</b>：框架拒这次状态写入（事件日志有未结算的工具调用、或快照已过期）时，
     * 这一轮沿用旧面并<b>说出来</b>——与改造前的形态同源（那时工具面本来就冻结在建实例那一轮），
     * 而不是静默少一道闸，也不是把整轮打挂。
     *
     * @param allowed 本轮按老装配判据允许的注册名（{@code WenquAgentxTools#turnGroupedNames}）
     * @return 需要让用户看见的问题；空表=组激活逐字复现了装配判据
     */
    public java.util.List<String> applyToolSurface(ChatCommand cmd, java.util.Set<String> allowed) {
        String key = key(cmd);
        Held h = held.get(key);
        java.util.Set<String> managed = groupedSurfaces.get(key);
        if (h == null || h.agent() == null || managed == null || managed.isEmpty()) return java.util.List.of();
        String uid = cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId();
        java.util.List<String> problems = new java.util.ArrayList<>();
        java.util.List<String> activated;
        try {
            var state = h.agent().getDelegate().getAgentState(uid, cmd.getSessionId());
            var toolContext = state.getToolContext();
            java.util.Set<String> current = new java.util.LinkedHashSet<>(toolContext.getActivatedGroups());
            java.util.List<String> toAdd = new java.util.ArrayList<>();
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String name : managed) {
                boolean want = allowed != null && allowed.contains(name);
                if (want && !current.contains(name)) toAdd.add(name);
                if (!want && current.contains(name)) toRemove.add(name);
            }
            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                toolContext.replaceActivatedGroups(toRemove, toAdd);
                h.agent().getDelegate().saveAgentState(uid, cmd.getSessionId());
                log.info("[agentx] 会话 {} 本轮工具面：+{} -{}", key, toAdd, toRemove);
            }
            activated = toolContext.getActivatedGroups();
        } catch (RuntimeException e) {
            log.warn("[agentx] 本轮工具面写入失败（session={}）：{}", key, e.toString());
            problems.add("本轮能力面未能按配置收窄（框架拒了这次会话状态写入），已沿用上一轮的工具面");
            return problems;
        }
        // 双跑比对：框架真正下发给模型的那份 schema 与老装配判据逐名对齐。
        // 这一步不是为了打印——它是「别名写错→建了空组、真工具无组=永远可见」唯一的能抓到的地方。
        try {
            io.agentscope.core.tool.Toolkit toolkit = h.agent().getToolkit();
            if (toolkit == null) return problems;
            java.util.Set<String> schemaNames = new java.util.LinkedHashSet<>();
            for (var schema : toolkit.getToolSchemas(activated)) {
                schemaNames.add(schema.getName());
            }
            java.util.List<String> missing = new java.util.ArrayList<>();
            java.util.List<String> extra = new java.util.ArrayList<>();
            for (String name : managed) {
                boolean want = allowed != null && allowed.contains(name);
                boolean seen = schemaNames.contains(name);
                if (want && !seen) missing.add(name);
                if (!want && seen) extra.add(name);
            }
            if (!missing.isEmpty() || !extra.isEmpty()) {
                log.error("[agentx] 工具面双跑不一致（session={}）：应挂未挂={}，不该挂却挂着={}",
                        key, missing, extra);
                if (!missing.isEmpty()) {
                    problems.add("这轮有允许的能力没挂上（配置允许、框架工具表里没有）：" + String.join("、", missing));
                }
                if (!extra.isEmpty()) {
                    problems.add("这轮有已禁的能力仍出现在工具表里（配置不允许、框架工具表里有）：" + String.join("、", extra));
                }
            }
            // 阳性对照：一致也要留一行，否则「没报错」分不清是「比过了」还是「压根没跑到」
            log.info("[agentx] 工具面双跑（session={}）：组管理 {} 把、本轮允许 {} 把、下发工具表 {} 把，{}",
                    key, managed.size(), allowed == null ? 0 : allowed.size(), schemaNames.size(),
                    missing.isEmpty() && extra.isEmpty() ? "一致" : "不一致");
        } catch (RuntimeException e) {
            log.warn("[agentx] 工具面双跑比对未跑成（session={}）：{}", key, e.toString());
        }
        return problems;
    }

    /**
     * 按「谁 + 哪条会话」取<b>当前进程内</b>的框架会话实例（只读，不新建、不建实例）。
     *
     * <p>给谁用：任务清单要按框架 state 回显，而 state 挂在常驻实例上。REST 端点读清单时
     * 实例可能不在（服务重启过、或这条会话还没在本进程答过一轮）——那属于「读不到」，
     * 调用方该回落别的来源，而不是在这里悄悄建一个空实例（建了就是凭空多一份状态）。
     *
     * @return null=这条会话当前没有进程内实例
     */
    public AgentSession peek(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String uid = (userId == null || userId.isBlank()) ? "guest" : userId;
        Held h = held.get(uid + "|" + sessionId);
        return h == null ? null : h.session();
    }

    /** 本轮新出现的待办登记归属，供答复端点找回会话。 */
    public void notePending(String sessionKey, Map<String, SessionEvent> pending) {
        for (String requestId : pending.keySet()) {
            pendingOwner.put(requestId, sessionKey);
        }
    }

    /**
     * 这条会话当前挂着的工具声明（{@code tools} 字段的 schema），供容量面板估算「工具声明」一段。
     * <p>取值走常驻实例的 toolkit —— 与真正发出去的那份请求同一份，不是重算一遍：
     * 工具挂载是建实例时定死的（{@code open} 只在新建或换模型时调 factory），所以读实例
     * 拿到的就是本轮真实下发的清单。
     * <p>会话还没建起来时返回空表（调用方按「无工具」处理，该段不出现）——拿不到就少画一段，
     * 不能拿别的会话的工具集冒充本轮。
     */
    public List<io.agentscope.core.model.ToolSchema> toolSchemas(ChatCommand cmd) {
        Held h = held.get(key(cmd));
        if (h == null || h.agent() == null) return List.of();
        try {
            io.agentscope.core.tool.Toolkit toolkit = h.agent().getToolkit();
            return toolkit == null ? List.of() : toolkit.getToolSchemas();
        } catch (Exception e) {
            log.warn("[agentx] 工具声明读取失败（容量面板少一段）: session={}, {}", key(cmd), e.toString());
            return List.of();
        }
    }

    /**
     * 占住这一条会话的跟随位：占到才该起一轮续跑（占不到说明已经有人在跟，不该有两个循环读同一条会话——
     * 那会各落一条助手消息）。
     */
    public boolean reserveFollower(String sessionKey) {
        return sessionKey != null && followers.putIfAbsent(sessionKey, Boolean.TRUE) == null;
    }

    /**
     * 开新一轮问答时强制占位：这一路是权威的提问者（轮级互斥已经保证同会话同时只有一轮在答），
     * 不能因为上一轮的续跑还没让位就不跟。
     */
    public void claimFollower(String sessionKey) {
        if (sessionKey != null) followers.put(sessionKey, Boolean.TRUE);
    }

    /** 跟随循环退出（正常收尾、超时、叫停都算）：让位，之后再有答复就补起一个新的。 */
    public void releaseFollower(String sessionKey) {
        if (sessionKey != null) followers.remove(sessionKey);
    }

    public boolean hasFollower(String sessionKey) {
        return sessionKey != null && followers.containsKey(sessionKey);
    }

    /**
     * 答复之后确认「有人把这一轮跟到收尾」，没有就补起一个。
     * <p>归属记录（{@code c_ai_tool_approval} 那一行）在这一刻已经是终态，所以 turnId 只能从表里取——
     * 它是续跑那一跑的记账身份（待办、任务清单、步数预算都挂在它下面）。
     */
    private void ensureFollowing(String requestId) {
        ResumeHandler h = resumeHandler;
        if (h == null || requestId == null) return;
        var row = hitl.findApproval(requestId);
        if (row == null) return;
        // 该不该起、能不能起（同一条会话不许两个循环对着跟）由引擎那侧的原子占坑决定，这里只负责问一声
        h.follow(row.getSessionId(), row.getUserId(), row.getTurnId());
    }

    /**
     * 答复一张确认卡。
     *
     * @return false = 这个审批号不属于框架引擎的任何会话（交回人在回路的旧通道处理）
     */
    public AgentSession.Task respond(String approvalId, boolean approved, String reason) {
        String sessionKey = pendingOwner.remove(approvalId);
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null) return resume(approvalId, "approval", approved, reason, null);
        // 框架自己找回待办所属的原任务与工具调用并安排接续执行，这里不拼工具 ID、也不再调 resume
        AgentSession.Task resumed = h.session().respond(approvalId,
                approved ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                        : io.agentscope.harness.agent.session.SessionAnswer.reject(reason));
        hitl.resolveFrameworkPending(approvalId, approved ? "APPROVED" : "REJECTED", null, reason);
        log.info("[agentx] 待办已答复 requestId={} approved={} → 接续任务={}",
                approvalId, approved, resumed.commandId());
        ensureFollowing(approvalId);
        return resumed;
    }

    /**
     * 答复一个提问卡（问题类待办）。与确认卡同一条通道：框架自己找回是哪一次工具调用在等人。
     *
     * @return false = 该 askId 不属于框架会话（交回人在回路的旧通道）
     */
    public AgentSession.Task respondAsk(String askId, String answerText) {
        String sessionKey = pendingOwner.remove(askId);
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null) return resume(askId, "ask", true, null, answerText);
        AgentSession.Task resumed = h.session().respond(askId,
                io.agentscope.harness.agent.session.SessionAnswer.text(answerText));
        hitl.resolveFrameworkPending(askId, "ANSWERED", null, answerText);
        log.info("[agentx] 提问已答复 askId={} → 接续任务={}", askId, resumed.commandId());
        ensureFollowing(askId);
        return resumed;
    }

    /**
     * 计划卡裁决后的「界面要定格成什么样」：批准/退回重出/继续对话/拒绝 在问渠界面上是四种终态，
     * 而框架只认 approve/reject 两态。这里把界面语义按会话留下一份，引擎收尾时取走补发对应帧——
     * 不补的表现为计划卡在用户已经拒绝之后还挂着「等待中」。
     */
    private final Map<String, String> planOutcomes = new ConcurrentHashMap<>();

    /** 计划卡的裁决：批准走 approve，其余按拒绝并把用户意见作为理由送回模型。 */
    public boolean respondPlanApproval(String planApprovalId, boolean approved, String reason) {
        return respondPlanApproval(planApprovalId, approved, reason, approved ? "approve" : "reject");
    }

    /**
     * @param outcome 界面终态：approve / revise（退回重出）/ supersede（继续对话、本版作废）/ reject（取消本轮）
     */
    public boolean respondPlanApproval(String planApprovalId, boolean approved, String reason, String outcome) {
        String sessionKey = pendingOwner.remove(planApprovalId);
        Held live = sessionKey == null ? null : held.get(sessionKey);
        String why = reason == null || reason.isBlank() ? "用户未批准这版计划" : reason;
        if (live == null) {
            // 重启后接回：续跑起来才算裁决送达；界面终态按接回后的归属键留下
            if (resume(planApprovalId, "plan", approved, why, null) == null) return false;
            planOutcomes.put(pendingOwner.getOrDefault(planApprovalId, ""), outcome == null ? "reject" : outcome);
            return true;
        }
        Held h = live;
        h.session().respond(planApprovalId, approved
                ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                : io.agentscope.harness.agent.session.SessionAnswer.reject(why));
        hitl.resolveFrameworkPending(planApprovalId, approved ? "APPROVED"
                : "supersede".equals(outcome) ? "SUPERSEDED" : "REJECTED", null, why);
        planOutcomes.put(sessionKey, outcome == null ? "reject" : outcome);
        log.info("[agentx] 计划卡已裁决 id={} approved={} outcome={}", planApprovalId, approved, outcome);
        ensureFollowing(planApprovalId);
        return true;
    }

    /** 引擎取走并清掉本轮的计划裁决（一次裁决只对应一次定格）。 */
    public String takePlanOutcome(String sessionKey) {
        return sessionKey == null ? null : planOutcomes.remove(sessionKey);
    }

    /** 只看不取：跟随循环要在「还在跑」的时候就知道用户点了继续对话，取走了收尾就没得发帧。 */
    public String peekPlanOutcome(String sessionKey) {
        return sessionKey == null ? null : planOutcomes.get(sessionKey);
    }

    /**
     * 这张待办此刻是否真能被答（进程内还持有那条会话）。
     * <p>恢复端点的 {@code live=false} 语义是「卡片取回也没人能唤醒」（进程重启/多副本在别的实例上）；
     * 框架待办的唤醒句柄在 {@code AgentSession} 上，所以存活判据是这里而不是旧通道的内存表。
     */
    public boolean isLive(String requestId) {
        return requestId != null && pendingOwner.containsKey(requestId) && held.containsKey(pendingOwner.get(requestId));
    }

    /**
     * 提问卡等满预算、已按「未作答」把答案送回模型：把那一行落成 TIMEOUT 并摘掉归属。
     * <p>与 {@link #markPendingTerminal} 的区别是那条给「整轮作废」用——它把这条会话还挂着的所有待办
     * 一锅端了；这里只处理这一张提问卡，且答案要留审计（模型收到的那句「用户未回答这一题」）。
     * 状态仍记 TIMEOUT 而不是 ANSWERED：用户确实没答，审计要看得出来。
     */
    public void markAskTimedOut(String requestId, String unanswered) {
        if (requestId == null) return;
        pendingOwner.remove(requestId);
        hitl.resolveFrameworkPending(requestId, "TIMEOUT", null, unanswered);
    }

    /**
     * 等待预算用尽或用户叫停：把这条会话还挂着的待办落成终态并摘掉归属。
     * <p>不落终态的话库里永远留着 PENDING——刷新后端点还会把一张已经作废的卡片重建出来，
     * 用户答了才发现什么都没发生。超时与叫停都不替用户代答（既有口径），这里只改状态。
     *
     * @param status {@code TIMEOUT}=等满预算，{@code STOPPED}=用户叫停（与手写侧
     *               {@code HitlDecisionService.terminateSessionWaits} 同一组词）
     */
    public void markPendingTerminal(String sessionKey, String status) {
        if (sessionKey == null) return;
        for (Map.Entry<String, String> e : new java.util.LinkedHashMap<>(pendingOwner).entrySet()) {
            if (!sessionKey.equals(e.getValue())) continue;
            hitl.resolveFrameworkPending(e.getKey(), status, null, null);
            pendingOwner.remove(e.getKey());
        }
    }

    /**
     * 这些待办在问渠侧是不是都已经没人能答了（记录已落终态，或压根没落过库）。
     * <p>给「上一轮停在等人裁决、这一轮再也起不动」的自愈判据用：恢复端点只列 PENDING，
     * 所以非 PENDING 就等于这张卡不会再有人答——框架那一轮不该继续占着会话。见 {@code abandoned} 的调用方。
     */
    public boolean abandoned(java.util.Collection<String> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) return true;
        for (String id : requestIds) {
            var rec = hitl.findApproval(id);
            if (rec != null && "PENDING".equals(rec.getStatus())) return false;
        }
        return true;
    }

    /**
     * 按「这一轮」进出计划模式。实例是按会话常驻的，所以计划模式不能在 build 期定死；
     * 框架给的是 {@code enterPlanMode(userId, sessionId)} 这一对显式开关，用完必须退出，
     * 否则下一条不想要计划的轮会继承它。
     */
    /**
     * 按「这一轮」进出计划模式。实例是按会话常驻的，所以计划模式不能在 build 期定死；
     * 框架给的是 {@code enterPlanMode(userId, sessionId)} 这一对显式开关，用完必须退出，
     * 否则下一条不想要计划的轮会继承它。
     *
     * @return 这一轮计划模式最终是否真的处于激活位（on=true 时若没能进入即返回 false）
     */
    public boolean setPlanMode(ChatCommand cmd, boolean on) {
        Held h = held.get(key(cmd));
        if (h == null) return false;
        String userId = cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId();
        try {
            if (on) {
                h.agent().enterPlanMode(userId, cmd.getSessionId());
                // 计划模式是不是真的进了，直接决定 plan_* 那一族工具能不能被模型调用
                // （框架的 PlanModeMiddleware 会把不在计划位上的计划工具调用滤掉，症状是「空补全」而不是报错）
                boolean active = h.agent().isPlanModeActive(userId, cmd.getSessionId());
                log.info("[agentx] 计划模式已开启？key={} active={}", key(cmd), active);
                return active;
            }
            else if (h.agent().isPlanModeActive(userId, cmd.getSessionId())) {
                h.agent().exitPlanMode(userId, cmd.getSessionId());
            }
        } catch (Exception e) {
            log.warn("[agentx] 计划模式切换失败 on={}: {}", on, e.toString());
            return false;
        }
        return false;
    }

    /**
     * 手动 /compact 在框架引擎下的落点：这一条会话下一次建实例时强制压缩。
     * <p>为什么是「下一次」而不是「当场」：框架的压缩长在推理循环里（{@code CompactionMiddleware} 在
     * 每次模型调用前判阈值），它没有「把已有会话就地压一次」的入口。而 /compact 要的结果就是
     * 「下一次提问别再背着那些旧对话」——在下一次提问开跑之前压，效果与用户要的是同一件事。
     */
    private final java.util.Set<String> compactRequests = ConcurrentHashMap.newKeySet();

    /**
     * 手动 /compact 在框架引擎下的<b>就地压缩</b>（全框架化 P4）。
     * <p>结论修正（2026-10-18）：此前注释说「框架没有把已有会话就地压一次的入口」是<b>错的</b>——
     * 框架自己在 {@code HarnessAgent#forceCompactAndRetry} 里就是这么用的：
     * 取 state 上下文 → {@code ConversationCompactor.compactIfNeeded(forceConfig)} → 写回
     * {@code state.contextMutable()}。而更规范的写回口是 {@code ReActAgent#updateAgentState}
     * （native 模式走 {@code SessionStateAccess.update}，拿 writer lease、判 pending/uncertain
     * 后提交 {@code context/replaced}）。
     * <p>两条路：
     * <ul>
     *   <li><b>进程内持有活实例</b>：当场压并写回——用户立即拿到压缩结果（摘要、腾出多少 token），
     *       不必等下一次提问开跑才生效。</li>
     *   <li><b>无活实例</b>（进程重启后 / 从未在本进程答过）：回落 {@link #requestCompact}
     *       排到下一次建实例时压（下一轮 open 会带 triggerMessages(1) 强制压）。</li>
     * </ul>
     *
     * @return 压缩结果（与问渠手写侧同形，前端 {@code compactContext} 按同套键渲染）：
     *         <ul>
     *           <li>{@code scheduled=true} = 没压成、排到了下一次提问前（无活实例 / 压缩失败回落）</li>
     *           <li>否则 {@code compressedTurns}（被覆盖的消息条数）、{@code freedTokens}（净腾出）、
     *               {@code summary}（框架摘要全文）、{@code summaryTokens}（摘要自身 token）</li>
     *         </ul>
     *         没得压（对话还短）返回 {@code compressedTurns=0}。
     */
    public Map<String, Object> compactNow(String userId, String sessionId) {
        String key = (userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId;
        Held h = held.get(key);
        if (h == null) {
            // 无活实例：回落「排到下一次建实例时压」（那一路会带上 triggerMessages(1) 强制压缩）
            requestCompact(userId, sessionId);
            return java.util.Map.of("engine", "agentscope", "scheduled", true, "compressedTurns", 0);
        }
        HarnessAgent agent = h.agent();
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId == null || userId.isBlank() ? "guest" : userId)
                .sessionId(sessionId)
                .build();
        try {
            AgentState state = agent.getDelegate().getAgentState(ctx);
            if (state == null) {
                requestCompact(userId, sessionId);
                return java.util.Map.of("engine", "agentscope", "scheduled", true, "compressedTurns", 0);
            }
            // 上下文里可能混有 SYSTEM / 合成消息：压缩器只吃对话消息（与 CompactionMiddleware 的
            // 拆分一致——SYSTEM 由 HarnessContextBuilder 单独装配，不参与压缩）。
            List<Msg> conversation = new java.util.ArrayList<>();
            for (Msg m : state.contextMutable()) {
                if (m.getRole() != null && "SYSTEM".equals(String.valueOf(m.getRole()))) continue;
                conversation.add(m);
            }
            if (conversation.isEmpty()) {
                return noneCompacted();
            }
            int before = TokenCounterUtil.calculateToken(conversation);
            // 与框架 forceCompactAndRetry 同一套压缩器与强制配置：有一条就先压（逐字留最近 20 条）
            WorkspaceManager ws = agent.getWorkspaceManager();
            MemoryFlushManager fm = new MemoryFlushManager(ws, agent.getModel());
            ConversationCompactor compactor = new ConversationCompactor(agent.getModel(), fm);
            CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
            Optional<List<Msg>> opt = compactor
                    .compactIfNeeded(ctx, conversation, forceConfig, agent.getName(), sessionId)
                    .block(Duration.ofSeconds(120));
            if (opt == null || opt.isEmpty()) {
                // 压不动（safe cutoff 0 等）：不是失败，就是没得压
                return noneCompacted();
            }
            List<Msg> compacted = opt.get();
            int after = TokenCounterUtil.calculateToken(compacted);
            List<Msg> finalCompacted = compacted;
            // 写回：native 模式走 SessionStateAccess.update（writer lease + context/replaced）
            agent.getDelegate().updateAgentState(ctx, "manual_compact", s -> {
                s.contextMutable().clear();
                s.contextMutable().addAll(finalCompacted);
            });
            // 摘要与覆盖消息数从摘要消息（name=__compaction_summary__，首位）提取
            String summary = "";
            int covered = 0;
            int summaryTokens = 0;
            if (!compacted.isEmpty()
                    && ConversationCompactor.SUMMARY_MSG_NAME.equals(compacted.get(0).getName())) {
                Msg sm = compacted.get(0);
                String text = sm.getTextContent();
                summary = text == null ? "" : text;
                summaryTokens = TokenCounterUtil.calculateToken(List.of(sm));
                Object cov = sm.getMetadata() == null ? null : sm.getMetadata().get("context.covered_messages");
                if (cov instanceof List<?> l) covered = l.size();
            }
            log.info("[agentx] 手动 /compact 就地压缩完成: session={} covered={} freedTokens={}",
                    sessionId, covered, Math.max(0, before - after));
            return java.util.Map.of(
                    "engine", "agentscope",
                    "scheduled", false,
                    "compressedTurns", Math.max(1, covered),
                    "freedTokens", Math.max(0, before - after),
                    "summary", summary,
                    "summaryTokens", summaryTokens);
        } catch (Exception e) {
            log.warn("[agentx] 手动 /compact 就地压缩失败，回落排到下一次: session={}, {}",
                    sessionId, e.toString());
            requestCompact(userId, sessionId);
            return java.util.Map.of("engine", "agentscope", "scheduled", true, "compressedTurns", 0);
        }
    }

    /** 「没得压」的统一返回（对话还短 / 压不动）：字段与压缩成功时同形，前端按同一套键渲染。 */
    private static Map<String, Object> noneCompacted() {
        return java.util.Map.of("engine", "agentscope", "scheduled", false,
                "compressedTurns", 0, "freedTokens", 0, "summary", "", "summaryTokens", 0);
    }

    /** 登记一次强制压缩并丢掉常驻实例（下一轮会带新配置重建；会话状态在 stateStore 里，不丢历史）。 */
    public void requestCompact(String userId, String sessionId) {
        String key = (userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId;
        compactRequests.add(key);
        Held h = held.remove(key);
        if (h != null) {
            order.remove(key);
            closeQuietly(key, h);
        }
        // 实例与槽位一起丢：下一轮会按新配置重建，槽位由 turnSlot(cmd) 现建
        turnSlots.remove(key);
    }

    /** 取走并清掉这次强制压缩（一次请求只对应一次生效）。 */
    public boolean takeCompactRequest(String sessionKey) {
        return sessionKey != null && compactRequests.remove(sessionKey);
    }

    /**
     * 标「下一次开轮重建」：配合 {@code takeCompactRequest} 的强制压缩用——强制压缩的
     * {@code triggerMessages(1)} 会烤进常驻实例，若不复位会被该会话后续每一轮继承（每轮都压）。
     * 标记后，下一轮 {@link #open} 会丢掉这个实例、用默认压缩配置重建，保证「/compact 只压一次」。
     */
    private final java.util.Set<String> rebuildAfterCompact = ConcurrentHashMap.newKeySet();

    public void markRebuildAfterCompact(String sessionKey) {
        if (sessionKey != null) rebuildAfterCompact.add(sessionKey);
    }

    /**
     * 标「这个人的会话下一次开轮重建实例」——用于**只能在 build 期定**的个人偏好发生变化时。
     * <p>典型就是「自动提炼记忆」：它落到 {@code MemoryConfig.flushTrigger}，而配置烤在常驻实例里，
     * 不重建就会让用户看到「我关了，它还在往记忆里写」（或反过来：开了却没生效）。
     * <p>只标不删：正在跑的那一轮不受影响，下一轮 {@link #open} 才换实例——历史在 stateStore 里，
     * 换实例不等于丢上下文。
     */
    private final java.util.Set<String> staleConfigKeys = ConcurrentHashMap.newKeySet();

    public void markConfigStale(String uid) {
        if (uid == null || uid.isBlank()) return;
        String prefix = uid + "|";
        for (String key : held.keySet()) {
            if (key.startsWith(prefix)) staleConfigKeys.add(key);
        }
    }

    /**
     * 标「全部活跃会话下一轮重建」——用于**平台级**且只能在 build 期定的配置变化
     * （当前是 {@code memory.*}：flush 节流、consolidation 上限、硬上限、日账保留期）。
     * <p>只标不删：正在跑的那一轮不受影响；没有活实例的会话下次 open 本来就会按新配置建。
     */
    public void markAllConfigStale() {
        staleConfigKeys.addAll(held.keySet());
    }

    /**
     * 关掉某个用户的全部常驻实例（清空长期记忆/删除账号时用）。
     * <p>只 mark stale 不够：正在跑的会话收尾时还会 flush，清空后又被写回一条。
     * 这里先关实例再删文件，语义才是「清空」；下一次提问会按 stateStore 里的历史重建实例。
     *
     * @return 关掉的实例数
     */
    public int evictUser(String uid) {
        if (uid == null || uid.isBlank()) return 0;
        String prefix = uid + "|";
        int closed = 0;
        for (String key : new java.util.ArrayList<>(held.keySet())) {
            if (!key.startsWith(prefix)) continue;
            Held h = held.remove(key);
            order.remove(key);
            stepBudgets.remove(key);
            frameworkToolGates.remove(key);
            turnSlots.remove(key);
            followers.remove(key);
            compactRequests.remove(key);
            rebuildAfterCompact.remove(key);
            staleConfigKeys.remove(key);
            if (h != null) {
                closeQuietly(key, h);
                closed++;
            }
        }
        return closed;
    }

    /** 建实例时登记这一路会话的步数预算闸（引擎在 {@code buildAgent} 里回调）。 */
    public void noteStepBudget(String sessionKey, AgentxStepBudget budget) {
        if (sessionKey != null && budget != null) stepBudgets.put(sessionKey, budget);
    }

    /** 取这一轮所属实例的步数预算闸；实例还没建出来时返回 null（开轮即会建，不该长期为空）。 */
    public AgentxStepBudget stepBudget(String sessionKey) {
        return stepBudgets.get(sessionKey);
    }

    /**
     * 登记这条会话的框架工具档位闸（全框架化 P1）。
     *
     * <p>与 {@link #noteStepBudget} 同一形态：middleware 只能在 build 期挂载，
     * 而闸的状态（当前轮标签）要按轮更新，所以实例随会话常驻、这里只做登记。
     */
    public void noteFrameworkToolGate(String sessionKey, AgentxFrameworkToolGate gate) {
        if (sessionKey != null && gate != null) frameworkToolGates.put(sessionKey, gate);
    }

    /** 取这一轮所属实例的框架工具档位闸；实例还没建出来时返回 null。 */
    public AgentxFrameworkToolGate frameworkToolGate(String sessionKey) {
        return frameworkToolGates.get(sessionKey);
    }

    /**
     * 按会话键直接答复一条待办——<b>不</b>走「归属查不到就重启接回」那一层。
     * <p>恢复路径自己要用这个口：那条路是先查表、重建实例、再 respond，若再走回带兜底的入口，
     * 就会自己绕回重启分支。
     */
    public AgentSession.Task respondRaw(String sessionKey, String requestId, String kind,
                                        boolean approved, String reason, String answer) {
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null || requestId == null) return null;
        io.agentscope.harness.agent.session.SessionAnswer a = switch (kind == null ? "approval" : kind) {
            case "ask" -> io.agentscope.harness.agent.session.SessionAnswer.text(answer == null ? "" : answer);
            default -> approved ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                    : io.agentscope.harness.agent.session.SessionAnswer.reject(
                            reason == null || reason.isBlank() ? "用户未批准" : reason);
        };
        return h.session().respond(requestId, a);
    }

    /**
     * 把这句话插进此刻正在跑的那一轮（框架原生 steer，运行中插话）。
     * <p><b>为什么这条能保底而旧那条不能</b>：框架只在「真的有在跑的 run 且写租约还活着」时收货
     * （{@code SessionRecorder} 每秒续租，长工具调用期间也算活着），收下之后它在<b>下一次调模型之前</b>
     * 被写进会话上下文并记一条 {@code inbox/applied}；而这一轮没读到这句就<b>不收尾</b>
     * （{@code ReActAgent#settleSession} → {@code SessionExecution#continueOrClose}）。
     * 问渠原先靠「把插话附在自家工具结果尾部」送达，框架引擎的模型直调工具不走那条守卫链，
     * 于是那句话只进历史不进模型——这里换成框架自己的通道，收据号留给收尾对账。
     *
     * @return 框架的收据号（{@code InputReceipt.inputId}，与 {@code inbox/applied} 里那些 commandId 同源）；
     *         null=这一轮不收插话：进程内没有这条会话的实例（工作流轮、重启后还没在本机答过）、
     *         没有在跑的 run、或租约已过期
     */
    public String steer(String userId, String sessionId, String modelText) {
        AgentSession session = peek(userId, sessionId);
        if (session == null) return null;
        try {
            return session.steer(modelText).inputId();
        } catch (RuntimeException e) {
            // 框架对「没有正在跑的轮」抛 IllegalStateException：这不是失败，是插话时机没赶上，
            // 调用方按「未接受」处理（内容退回输入框），不能让人看到 500
            log.info("[agentx] 插话未被框架会话接收 session={}: {}", sessionId, e.toString());
            return null;
        }
    }

    public void interrupt(ChatCommand cmd) {
        Held h = held.get(key(cmd));
        if (h != null) h.session().interrupt();
    }

    public String key(ChatCommand cmd) {
        return (cmd.getUserId() == null ? "guest" : cmd.getUserId()) + "|" + cmd.getSessionId();
    }

    private void touch(String key) {
        order.remove(key);
        order.addLast(key);
    }

    private void evictOverflow() {
        while (order.size() > MAX_HELD) {
            String oldest = order.pollFirst();
            Held h = oldest == null ? null : held.remove(oldest);
            if (h != null) closeQuietly(oldest, h);
        }
        // 槽位不跟着实例立刻删：工具闭包捕获的是它，删了之后这条会话下一轮会拿到一个新槽位，
        // 已建好的工具却还指着旧的那个（又变回「工具写上一轮」）。只在高水位时清理<b>没有实例、
        // 也没有在跑</b>的槽位，避免长跑进程里按会话无限增长。
        if (turnSlots.size() > MAX_HELD * 4) {
            turnSlots.entrySet().removeIf(e ->
                    !held.containsKey(e.getKey()) && !e.getValue().active());
        }
    }

    private void closeQuietly(String key, Held h) {
        try {
            h.session().close();
        } catch (Exception e) {
            log.warn("[agentx] 关闭会话失败 {}: {}", key, e.toString());
        }
        try {
            h.agent().close();
        } catch (Exception e) {
            log.warn("[agentx] 关闭框架实例失败 {}: {}", key, e.toString());
        }
    }
}
