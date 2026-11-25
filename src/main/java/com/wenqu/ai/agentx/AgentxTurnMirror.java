package com.wenqu.ai.agentx;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跟随循环的轻量状态镜像（从 AgentscopeChatEngine 抽出）。
 *
 * <p>镜像只跟事件：pending 表与三种脏位。框架算任务状态时还看两件不落事件的事
 * （租约过期把 running 降级成 interrupted、inbox 里的 rejected），所以循环每一跳之外
 * 再按 STATE_RECHECK_MS 回查一次权威状态；镜像只负责把事件流压缩成「这一跳有什么变了」。
 *
 * <p>{@link #pendingSnapshot()} 返回副本而非内部表：{@code sessions.notePending} 存下的引用
 * 会被恢复端点跨线程读，而镜像在同一个循环的下一跳还会改它——递原表就是数据竞争。
 *
 * @author yuanke
 */
final class AgentxTurnMirror {

        private final Map<String, SessionEvent> pending = new java.util.LinkedHashMap<>();
        private boolean pendingChanged;
        private boolean taskChanged;
        private boolean messageChanged;

        void observe(SessionEvent event) {
            switch (event.type()) {
                case "interaction/requested", "interaction/resolved" -> {
                    Map<String, Object> data = event.data() == null ? Map.of() : event.data();
                    String requestId = String.valueOf(data.get("requestId"));
                    if ("interaction/requested".equals(event.type())) pending.put(requestId, event);
                    else pending.remove(requestId);
                    pendingChanged = true;
                }
                case "inbox/accepted", "inbox/started", "inbox/closed", "inbox/handled", "inbox/rejected",
                     "run/end" -> taskChanged = true;
                case "message/user", "message/assistant", "tool/result", "turn/output", "migration/baseline" ->
                        messageChanged = true;
                default -> {
                }
            }
        }

        /** 取走并清空「本跳有没有……」——取走即清，循环里那几条 continue 才不会把脏位带进下一跳。 */
        boolean takePendingChanged() {
            boolean v = pendingChanged;
            pendingChanged = false;
            return v;
        }

        boolean takeTaskChanged() {
            boolean v = taskChanged;
            taskChanged = false;
            return v;
        }

        boolean takeMessageChanged() {
            boolean v = messageChanged;
            messageChanged = false;
            return v;
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }

        /**
         * 交给外部的一份副本。
         * <p>不递内部那张表：{@code sessions.notePending} 存下的引用会被恢复端点跨线程读，
         * 而镜像在同一个循环的下一跳还会改它。
         */
        Map<String, SessionEvent> pendingSnapshot() {
            return new java.util.LinkedHashMap<>(pending);
        }

        /**
         * 用权威值重种待办表：代价是一次全量读，一轮只做一次。
         * <p>起轮时本轮游标之前挂着的待办（上一轮没答完的那张卡）不在 drain 的范围里，不种就看不见；
         * 种完把脏位置真，让第一跳照旧把这一批卡报出去。
         */
        void resyncPending(AgentSession session) {
            pending.clear();
            pending.putAll(session.pending());
            pendingChanged = true;
        }
}
