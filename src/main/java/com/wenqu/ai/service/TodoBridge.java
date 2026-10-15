package com.wenqu.ai.service;

import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务清单的存储桥：<b>问渠的护栏 + 框架的 state</b>。
 *
 * <p>问渠的清单原来自己落 {@code c_ai_session.todos}。单引擎之后权威真源改由框架持有 ——
 * 清单状态就是 {@code AgentState.tasksContext}（框架的 {@code todo_write} 与
 * {@code TaskReminderMiddleware} 每轮重述都围绕它），问渠再把它当一份平行的真值源就是两个真源，
 * 迟早对不上（模型读到框架那份、前端显示问渠那份，用户看到的进度和模型以为的进度不是一回事）。
 * 会话表那一份现在只留「最后写成的那张清单」做回显兜底：进程重启后框架实例不在本进程，
 * 右栏仍要能长出清单，而重建一个实例只为读一张卡不值当。
 *
 * <p>本类只做翻译，不做存储：
 * <ul>
 *   <li><b>写入</b>：问渠归一后的条目 → 框架 {@link Task} 列表 → {@code TaskContextState#replaceTasks}，
 *       落在<b>这一轮活着的</b> {@code AgentState} 上。状态词映射 {@code doing→in_progress}、
 *       {@code done→completed}（问渠用前者、框架用后者，语义同、词不同）；</li>
 *   <li><b>读出</b>：同一份活状态 → 问渠的清单文档 JSON（实时帧与账本都吃它）；REST 回显那条会话
 *       没有「本轮」，只能读 {@code session.inspect()} 那份快照——前端契约一个字没改。</li>
 * </ul>
 *
 * <p><b>为什么写活状态而不是会话投影</b>（这里踩过一次，症状是「清单已写入但回读失败」每轮必报）：
 * {@code SessionProjection#restore} 每次从日志重放出<b>一个新的</b>反序列化对象，改它不影响任何人，
 * 框架收尾落库的仍是自己那份活状态——于是写入永远静默丢失。框架自带的 {@code todo_write}
 * 之所以没这个毛病，是因为它靠 {@code stateInjected} 拿到同一份活对象；问渠的工具走 Spring AI 的
 * {@code ToolCallback}，框架绑不了它的参数，只能由 {@code SpringAiToolAdapter} 按次调用把状态放进工具上下文。
 *
 * <p><b>为什么保留问渠的护栏</b>（≤N 项、单项截断、同时只允许一项 doing）：框架的
 * {@code todo_write} 不管这些，而清单卡是用户直接看见的界面。模型一次写二十项、
 * 把单项写成一段话、同时标三项 doing，在框架那边都是"合法"的，在用户那边是一张没法看的卡。
 * 护栏留在问渠这一侧，是因为它是<b>呈现约束</b>而不是存储约束。
 */
@Service
public class TodoBridge {

    private static final Logger log = LoggerFactory.getLogger(TodoBridge.class);

    /** 写入结果：失败时带一句可回给模型的原因（工具报错会让模型反复重试，所以要能救的救、说清的说清） */
    public record WriteResult(boolean ok, String reason) {
        static WriteResult good() {
            return new WriteResult(true, null);
        }

        static WriteResult bad(String reason) {
            return new WriteResult(false, reason);
        }
    }

    /**
     * 整表替换本轮的任务清单。
     *
     * <p><b>写的是框架这一轮活着的 {@link AgentState}</b>，不是 {@code session.inspect()} 那份快照：
     * 后者是从会话日志重放出来的只读恢复视图（{@code SessionProjection#restore} 每次
     * 反序列化一个新对象），改它等于改完就丢——框架在收尾时落库的是自己那份活状态，
     * 于是「写入成功、回读为空」，清单永远记不上（真机就是这个形状）。框架自带的
     * {@code todo_write} 之所以正常，就是因为它拿的是 {@code stateInjected} 注入的同一份活对象。
     *
     * <p>落库不用这里管：框架在每次 call 收尾时把活状态整体 checkpoint 进会话日志与 stateStore。
     *
     * @param items 已由 {@link TodoService#normalize} 归一过的条目（≤N 项、状态合法、单项 doing 至多一项）
     */
    public WriteResult replace(AgentState state, String sessionId, List<TodoService.TodoItem> items) {
        if (state == null) return WriteResult.bad("本轮没有拿到框架会话状态");
        try {
            TaskContextState ctx = state.getTasksContext();
            TaskContextState snapshot = ctx.snapshot();
            List<Task> rebuilt = new ArrayList<>(items.size());
            // 按内容匹配上一轮的 task，保留 id/createdAt：整表重写不该让标识符每轮都变
            Map<String, Task> byContent = new LinkedHashMap<>();
            for (Task t : snapshot.getTasks()) {
                if (t.getSubject() != null) byContent.putIfAbsent(t.getSubject(), t);
            }
            for (TodoService.TodoItem item : items) {
                Task.State state1 = toFrameworkState(item.status());
                Task prior = byContent.get(item.content());
                Task.Builder b = Task.builder()
                        .subject(item.content())
                        .description(item.content())
                        .state(state1);
                if (prior != null) b.id(prior.getId()).createdAt(prior.getCreatedAt());
                rebuilt.add(b.build());
            }
            // 乐观锁：expectedRevision 用刚读到的快照版本，冲突说明这一轮里被别处改过
            ctx.replaceTasks(rebuilt, snapshot.getRevision());
            log.info("[TODO] 清单已写入框架状态: 会话={} 共 {} 项（进行中 {}）", sessionId, rebuilt.size(),
                    rebuilt.stream().filter(t -> t.getState() == Task.State.IN_PROGRESS).count());
            return WriteResult.good();
        } catch (Exception e) {
            log.warn("[TODO] 清单写入框架状态失败: session={}, {}", sessionId, e.toString());
            return WriteResult.bad(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 组装清单文档 JSON：<b>以刚落进框架活状态的那份为真源读回来</b>，不是拿传入的 items 再拼一次。
     * <p>为什么要绕这一圈：items 是问渠归一后的结果，state 是框架最终接受的结果。两者理论上
     * 一致（护栏在写之前就拦过了），但一旦框架侧再做任何归一（截断、排序、补 id），发给前端的
     * 就会和模型下一轮看到的不一致。读回来是唯一能让「用户看到的」与「模型以为的」同源的办法。
     *
     * @param turnId 清单所属轮次（前端按它标「哪一轮写的」）
     * @return null=这份状态里既没有条目、也没有可兜底的条目
     */
    public String docJson(AgentState state, List<TodoService.TodoItem> fallback, String turnId) {
        List<Task> tasks = state == null ? null : state.getTasksContext().getTasks();
        if (tasks == null || tasks.isEmpty()) tasks = toTasks(fallback);
        return docJson(tasks, turnId);
    }

    /**
     * 按会话实例回显当前清单（REST 端点用，那里没有「本轮」，也就没有活状态可拿）。
     *
     * <p>读的是会话日志重放出来的那份快照，所以它<b>只到上一次收尾为止</b>：本轮刚写的条目要等
     * 框架 checkpoint 之后才看得见。实时那一路走的是上面 {@code docJson(AgentState, …)} 加 SSE 帧，
     * 不靠这里，因此这个滞后不会让用户看到旧清单。
     *
     * @param session 常驻框架会话；null=这条会话当前不在本进程（重启过），调用方该回落别的来源
     */
    public String docJson(AgentSession session, String turnId) {
        if (session == null) return null;
        try {
            SessionProjection projection = session.inspect();
            AgentState state = projection == null ? null : projection.restore();
            return state == null ? null : docJson(state, List.of(), turnId);
        } catch (Exception e) {
            log.warn("[TODO] 清单回读失败: {}", e.toString());
            return null;
        }
    }

    /** 框架 Task 列表 → 前端那份清单文档 JSON */
    private static String docJson(List<Task> tasks, String turnId) {
        if (tasks.isEmpty()) return null;
        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", t.getSubject());
            row.put("status", toWenquStatus(t.getState()));
            rows.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("turnId", turnId == null ? "" : turnId);
        doc.put("items", rows);
        doc.put("updatedAt", java.time.LocalDateTime.now().withNano(0).toString());
        return com.alibaba.fastjson.JSON.toJSONString(doc);
    }

    /** 问渠条目 → 框架 Task（不含「保留上一轮 id」那步，那是写入路径的事，这里只做纯转换） */
    private static List<Task> toTasks(List<TodoService.TodoItem> items) {
        List<Task> out = new ArrayList<>();
        if (items == null) return out;
        for (TodoService.TodoItem i : items) {
            if (i == null || i.content() == null || i.content().isBlank()) continue;
            out.add(Task.builder()
                    .subject(i.content())
                    .description(i.content())
                    .state(toFrameworkState(i.status()))
                    .build());
        }
        return out;
    }

    /** 问渠状态词 → 框架状态枚举（doing→in_progress、done→completed；其余归 pending） */
    static Task.State toFrameworkState(String wenquStatus) {
        String s = wenquStatus == null ? "" : wenquStatus.strip().toLowerCase();
        return switch (s) {
            case "doing", "in_progress" -> Task.State.IN_PROGRESS;
            case "done", "completed" -> Task.State.COMPLETED;
            default -> Task.State.PENDING;
        };
    }

    /** 框架状态枚举 → 问渠状态词（前端右栏那份契约一个字没改） */
    static String toWenquStatus(Task.State state) {
        if (state == null) return "pending";
        return switch (state) {
            case IN_PROGRESS -> "doing";
            case COMPLETED -> "done";
            default -> "pending";
        };
    }
}
