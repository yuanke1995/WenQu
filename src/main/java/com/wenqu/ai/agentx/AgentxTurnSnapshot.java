package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.util.TurnBlocks;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Map;

/**
 * 接流快照：本轮到此刻已产出的东西，一次交全给总线（从 AgentscopeChatEngine 抽出）。
 * 字段名与前端 applyTurnSnapshot 一一对齐、与刷新后读历史那份同源同形。计划卡走 AgentxPlanCardSnapshot。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTurnSnapshot {

    private final AgentxAgentBinding agentBinding;
    private final ArtifactService artifactService;
    private final AgentxPlanCardSnapshot planCardSnapshot;

    public Map<String, Object> build(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                                             ModelRegistryService.ModelRoute route, AgentxTurnRecording recording,
                                             String turnId, long startedAt, long prepMs) {
        Map<String, Object> snap = recording.snapshotFields();
        snap.put("running", true);
        snap.put("phase", "generating");
        snap.put("sources", new java.util.ArrayList<>(pool.sources()));
        // 配图清单随快照一起给：image 帧是「有新增才发全量」的状态事件，接流方漏不上那一帧就是
        // 整轮没有图位（前端按 images[N-1] 还原，缺数组则 [图片N] 原样留在正文里）
        snap.put("images", pool.signedImages());
        snap.put("artifacts", artifactService.takeArtifacts(cmd.getSessionId()));
        snap.put("model", route.modelId());
        snap.put("turnId", turnId);
        // 计划卡：草稿/待确认/退回重出/已批准都按运行时状态重建；落库的 planRecord 只在收尾有
        Map<String, Object> planCard = planCardSnapshot.build(cmd, recording);
        if (planCard != null) snap.put("planCard", planCard);
        // 游客接流同样只给答案块；完整 blocks 留在服务端与登录态历史里
        if (cmd.isGuestMode() && snap.get("blocks") != null) {
            Map<String, Object> minimal = TurnBlocks.answerOnly(snap.get("blocks"));
            if (minimal == null) snap.remove("blocks");
            else snap.put("blocks", minimal);
        }
        // 到此刻已经跑掉的毫秒（每次 attach 现算）：接流那端的秒表是从接上那一刻起表的，不给这个数，
        // 刷新一下「正在溯源，用时 N 秒」就从 0 重新跳一遍——已经跑掉的那段在用户眼里不算数了。
        // 给「跑掉了多久」而不给「绝对开轮时刻」：前端本地钟与 nowTick 都在客户端钟上，跨钟相减会把
        // 两边的偏差直接算进用户眼里；给时长则只差这一帧在路上的时间。
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        snap.put("elapsedMs", elapsedMs);
        // 整轮墙钟（含开轮前准备）与那一段准备一起给：接流那端的秒表按这一份起表，
        // 否则「刷新一次」会把排队+装配那几秒抹掉（用户看见的数凭空变小，与 done 同一件事）。
        // 没有请求体的入口 prepMs=0、基准就是开轮时刻——照样给，刷新后才接得上同一本账。
        snap.put("wallMs", elapsedMs + prepMs);
        snap.put("prepMs", prepMs);
        if (cmd.getAgentId() != null && !cmd.getAgentId().isBlank()) {
            snap.put("agentId", cmd.getAgentId());
            String name = agentBinding.agentNameOf(cmd);
            if (name != null) snap.put("agentName", name);
        }
        return snap;
    }
}
