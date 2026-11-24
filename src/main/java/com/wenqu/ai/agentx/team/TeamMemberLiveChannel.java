package com.wenqu.ai.agentx.team;

import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 成员轮的直播出口：包住那条收集型通道，<b>只加一条抄送</b>，其余语义逐字不变。
 *
 * <p>为什么是装饰而不是换成真 SSE：成员轮由框架的唤醒回调起跑，那一刻没有「这个请求的响应」，
 * 收集型通道正是为这种 headless 轮设计的（收尾、看门狗、落库都挂在它上面）。
 * 直播的需求是「另外的人也看得见」，不是「换一个出口」——所以这里把帧原样交给
 * {@link TeamMemberLiveFeed}，本轮自己的出口、收尾与落库一律不碰。
 *
 * @author yuanke
 */
public final class TeamMemberLiveChannel implements RunChannel {

    private final RunChannel delegate;
    private final TeamMemberLiveFeed feed;
    private final String sessionId;

    public TeamMemberLiveChannel(RunChannel delegate, TeamMemberLiveFeed feed, String sessionId) {
        this.delegate = delegate;
        this.feed = feed;
        this.sessionId = sessionId;
    }

    @Override
    public SseEmitter emitter() {
        return delegate.emitter();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public boolean emit(RunEvent event) {
        boolean alive;
        try {
            alive = delegate.emit(event);
        } finally {
            // 抄送失败绝不影响本轮：观众看的是副本，主链路（落库/收尾）才是权威
            feed.relay(sessionId, event);
        }
        return alive;
    }

    @Override
    public boolean emit(RunEventType type, String content) {
        return emit(RunEvent.of(type, content));
    }

    @Override
    public String lastWarn() {
        return delegate.lastWarn();
    }

    @Override
    public void offerSnapshot(Supplier<Map<String, Object>> snapshotSource) {
        // 快照语义属于那条收集型通道（它没有观众，快照也就没人读）：原样转交，不多造一份
        delegate.offerSnapshot(snapshotSource);
    }

    @Override
    public void complete() {
        delegate.complete();
    }
}
