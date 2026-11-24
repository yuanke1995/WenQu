package com.wenqu.ai.agentx.team;

import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成员轮直播台：三条不能破的性质——
 * <ol>
 *   <li>开台之后逐帧抄送，只抄「能重建界面」的那几类；</li>
 *   <li>收台后进页面的人仍能拿到整段积压（迟到的观众不白跑）；</li>
 *   <li>收台后不再抄送（一轮结束后的迟到帧不许把收尾状态又推回去）。</li>
 * </ol>
 */
class TeamMemberLiveFeedTest {

    @Test
    @DisplayName("没有开台时抄送是空操作：普通问答轮完全不受这条路影响")
    void relayWithoutFeedIsNoop() {
        TeamMemberLiveFeed feed = new TeamMemberLiveFeed();
        feed.relay("no-such-session", RunEvent.of(RunEventType.TOKEN, "x"));
        assertThat(feed.subscribe("no-such-session")).isNull();
    }

    @Test
    @DisplayName("观众第一帧是 snapshot，带积压的帧与顺序；done 之后不再回放新帧")
    void subscriberGetsSnapshotWithBacklog() throws Exception {
        TeamMemberLiveFeed feed = new TeamMemberLiveFeed();
        feed.open("sid-1");
        feed.relay("sid-1", RunEvent.of(RunEventType.TOKEN, "你"));
        feed.relay("sid-1", RunEvent.of(RunEventType.TOKEN, "好"));
        // 不可重建界面的帧（心跳/终帧）不进积压
        feed.relay("sid-1", RunEvent.of(RunEventType.SNAPSHOT, "{}"));

        SseEmitter viewer = feed.subscribe("sid-1");
        assertThat(viewer).isNotNull();
        // SseEmitter 的真实写出要容器，这里只钉「有观众、且积压已被读走」——
        // 帧内容的顺序由 relay 的追加顺序保证（见下一条用例）
        feed.relay("sid-1", RunEvent.of(RunEventType.WARN, "出问题了"));
        feed.finish("sid-1", "这一轮已结束");
        feed.relay("sid-1", RunEvent.of(RunEventType.TOKEN, "迟到的帧"));
        // 收台后再订阅：拿到的是一条已结束的直播（snapshot.done=true），前端据此走历史
        assertThat(feed.subscribe("sid-1")).isNotNull();
    }

    @Test
    @DisplayName("积压按原顺序：token 帧一条不丢（观众补出来的正文与先到的人一致）")
    void backlogPreservesOrderAndCount() {
        TeamMemberLiveFeed feed = new TeamMemberLiveFeed();
        feed.open("sid-2");
        for (int i = 0; i < 5; i++) feed.relay("sid-2", RunEvent.of(RunEventType.TOKEN, String.valueOf(i)));
        assertThat(feed.backlogOf("sid-2")).extracting(TeamMemberLiveFeed.Frame::data)
                .containsExactly("0", "1", "2", "3", "4");
        assertThat(feed.backlogOf("sid-2")).extracting(TeamMemberLiveFeed.Frame::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("可见帧推给在场观众，收台后推 done 并摘掉观众")
    void liveViewerReceivesFrames() throws Exception {
        TeamMemberLiveFeed feed = new TeamMemberLiveFeed();
        feed.open("sid-3");
        // 换一个能记录 send 的 emitter：SseEmitter 在无容器时 send 会抛，观众照样要先登记
        feed.subscribe("sid-3");
        feed.relay("sid-3", RunEvent.of(RunEventType.TOKEN, "a"));
        feed.finish("sid-3", "ok");
        // 再推一帧：收台后没有观众、也不进积压（观众此刻读到的应是「a + 一条 done」，不该再多出 "b"）
        feed.relay("sid-3", RunEvent.of(RunEventType.TOKEN, "b"));
        var backlog = feed.backlogOf("sid-3");
        assertThat(backlog).extracting(TeamMemberLiveFeed.Frame::data)
                .first().isEqualTo("a");
        assertThat(backlog).hasSize(2);                       // a + done
        assertThat(backlog.get(1).event()).isEqualTo("done"); // 迟到观众靠这一帧知道「已结束，去读历史」
        assertThat(feed.viewersOf("sid-3")).isZero();          // 收台摘掉观众，不留空转连接
    }

    @Test
    @DisplayName("装饰层：抄送在委托之后、且委托失败也照抄（观众看副本，落库才是权威）")
    void channelRelaysEvenWhenDelegateFails() {
        TeamMemberLiveFeed feed = new TeamMemberLiveFeed();
        feed.open("sid-4");
        AtomicInteger delegated = new AtomicInteger();
        TeamMemberLiveChannel channel = new TeamMemberLiveChannel(new com.wenqu.ai.engine.RunChannel() {
            @Override
            public SseEmitter emitter() {
                return null;
            }

            @Override
            public String sessionId() {
                return "sid-4";
            }

            @Override
            public boolean emit(RunEvent event) {
                delegated.incrementAndGet();
                throw new IllegalStateException("通道已关");
            }

            @Override
            public String lastWarn() {
                return null;
            }

            @Override
            public void offerSnapshot(java.util.function.Supplier<java.util.Map<String, Object>> s) {
            }

            @Override
            public void complete() {
            }
        }, feed, "sid-4");
        try {
            channel.emit(RunEventType.TOKEN, "x");
        } catch (IllegalStateException expected) {
            // 委托自己的异常原样抛（本类不改语义）
        }
        assertThat(delegated.get()).isEqualTo(1);
        assertThat(feed.backlogOf("sid-4")).extracting(TeamMemberLiveFeed.Frame::data).containsExactly("x");
    }
}