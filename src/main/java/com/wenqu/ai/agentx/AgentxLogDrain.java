package com.wenqu.ai.agentx;

import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 按游标读增量事件并翻译（从 AgentscopeChatEngine 抽出）。
 *
 * <p>必须翻到读完为止：{@code readAfter(after, limit)} 是「最多 limit 条」的截断语义，
 * 一次只取一页等于给两次读之间的积压设了隐形上限，而收尾那一次是全轮最后一次读——
 * 只走一页，落后的那一截（最后几个正文增量）永久丢掉。上界取进入时的 head 快照，
 * 否则事件在跑的过程中持续追加、这个循环可以永不返回。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxLogDrain {

    /** 一次从 Session Log 读多少条事件（框架的翻页上界）。 */
    private static final int DRAIN_PAGE = 200;

    /** 只记账不播报的通道：读历史事件时用它——事件照样进 recording，但不再发一遍给观众。 */
    static final RunChannel SILENT_CHANNEL = new RunChannel() {
        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter() {
            return null;
        }

        @Override
        public String sessionId() {
            return "";
        }

        @Override
        public boolean emit(RunEvent event) {
            return true;
        }

        @Override
        public String lastWarn() {
            return null;
        }

        @Override
        public void offerSnapshot(Supplier<Map<String, Object>> snapshotSource) {
        }

        @Override
        public void complete() {
        }
    };

    private final AgentxEventTranslator eventTranslator;

    /**
     * @param emitFrom 这个 seq 之前的只记不发（续跑要把批准前那几次工具记进账，但那些帧已经播过）
     * @param mirror   本轮状态镜像；收尾那次传 null（出轮之后没人读状态）
     * @return 新游标
     */
    public long drain(AgentSession session, long cursor, long emitFrom, String turnId, RunChannel channel,
                      AgentxTurnRecording recording, AgentxTurnMirror mirror) {
        SessionLog eventLog = session.log();
        long through = eventLog.head().seq();
        while (cursor < through) {
            List<SessionEvent> batch = eventLog.readAfter(cursor, DRAIN_PAGE);
            if (batch.isEmpty()) break;   // seq 有空洞（压缩/迁移）：游标推不动，不在这儿死循环
            for (SessionEvent event : batch) {
                cursor = Math.max(cursor, event.seq());
                eventTranslator.translate(event, event.seq() > emitFrom ? channel : SILENT_CHANNEL, turnId, recording);
                if (mirror != null) mirror.observe(event);
            }
        }
        return cursor;
    }
}
