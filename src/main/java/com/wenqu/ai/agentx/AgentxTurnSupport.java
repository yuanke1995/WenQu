package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.SandboxMode;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.turn.TurnPromptAssembler;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.skill.SkillStatsStore;
import io.agentscope.core.session.SessionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.str;

/**
 * 一轮里的几处通用小动作（从 AgentscopeChatEngine 抽出）：问题落库、提问超时回送 pending、
 * 取本轮挂载工具名、发点名技能命中帧。它们被 chat/followTurn 调用，各自依赖单一，收在一处。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnSupport {

    private final ChatAttachmentService attachments;
    private final SessionService sessionService;
    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final SkillStatsStore skillStatsStore;

    public String appendQuestion(ChatCommand cmd, String turnId, List<String> userImgUrls) {
        String messageId = sessionService.appendMessage(MessageWrite.builder()
                .sessionId(cmd.getSessionId())
                .role("user")
                .content(cmd.getQuestion())
                .images(userImgUrls == null || userImgUrls.isEmpty() ? null : userImgUrls)
                .attachments(attachments.metasJson(cmd.getAttachments(), cmd.uploadOwnerUid()))
                .mentions(TurnPromptAssembler.mentionsJson(cmd.getMentions()))
                .historyRefs(TurnPromptAssembler.historyRefsJson(cmd.getHistoryRefs()))
                .turnId(turnId)
                .build());
        if (messageId != null && cmd.getEditVariantGroup() != null && !cmd.getEditVariantGroup().isBlank()) {
            sessionService.setMessageVariant(messageId, cmd.getEditVariantGroup());
        }
        return messageId;
    }

    public boolean answerTimedOutAsk(Map<String, SessionEvent> pending, ChatCommand cmd, AgentxTurnRecording recording) {
        String sessionKey = sessions.key(cmd);
        for (Map.Entry<String, SessionEvent> e : pending.entrySet()) {
            Map<String, Object> data = e.getValue().data() == null ? Map.of() : e.getValue().data();
            if ("confirmation".equals(str(data.get("kind")))) continue;
            String notice = TurnInteractionCoordinator.ASK_PENDING_NOTICE;
            try {
                if (sessions.respondRaw(sessionKey, e.getKey(), "ask", true, null, notice) == null) {
                    continue;   // 这条待办接不动，试下一条
                }
            } catch (Exception ex) {
                log.warn("[agentx] 提问超时回送 pending 失败 {} session={}: {}", e.getKey(),
                        cmd.getSessionId(), ex.toString());
                continue;
            }
            sessions.markAskContinued(e.getKey(), notice);
            // 随后那条 tool/result 要带上「这张卡仍可答」，否则前端按终态把提问面板撤了
            recording.markAskContinued(e.getKey());
            log.info("[agentx] 提问前台等待结束，按 pending 送回模型继续本轮（卡片仍可答）: requestId={} session={}",
                    e.getKey(), cmd.getSessionId());
            return true;
        }
        return false;
    }

    public java.util.Set<String> mountedToolNames(ChatCommand cmd, ExecutionPolicy policy,
                                               AgentxTurnSlot slot) {
        try {
            java.util.Set<String> out = new LinkedHashSet<>(tools.turnGroupedNames(cmd, policy));
            AgentxTurnSlot.Surface surface = slot == null ? null : slot.surface();
            if (surface != null) {
                if (surface.runCodeMounted()) out.add("run_code");
                addFrameworkSurface(out, surface, policy);
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("[agentx] 取本轮挂载工具名失败，本轮不追加工具分工段: {}", e.toString());
            return Set.of();
        }
    }

    /** 框架原生工具（deliver_artifact 等）不在注册中心里，按工具面那份清单并进来；只读档/API Key 按名禁用已在 policy.deny 里。 */
    private void addFrameworkSurface(Set<String> out, AgentxTurnSlot.Surface surface, ExecutionPolicy policy) {
        if (surface.rawCallbacks() == null) return;
        if (policy.sandboxMode() == SandboxMode.READ_ONLY) return;
        Set<String> framework = Set.copyOf(WenquAgentxTools.frameworkSurfaceToolNames());
        for (var cb : surface.rawCallbacks()) {
            if (cb == null || cb.getToolDefinition() == null) continue;
            String name = cb.getToolDefinition().name();
            if (framework.contains(name) && !policy.denies(name)) out.add(name);
        }
    }

    public void emitPickedSkillHits(ChatCommand cmd, RunChannel channel) {
        List<String> picked = cmd.getSkills();
        if (picked == null || picked.isEmpty()) return;
        for (String raw : picked) {
            if (raw == null || raw.isBlank()) continue;
            // 智能体「指定」模式存的是 {uid}/{name} 引用串，命中帧与统计都用裸名
            String name = raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1) : raw.trim();
            if (name.isEmpty()) continue;
            skillStatsStore.recordRead(cmd.getUserId(), name, cmd.getSessionId());
            try {
                channel.emit(RunEventType.SKILL_HIT, com.alibaba.fastjson2.JSON.toJSONString(
                        java.util.Map.of("name", name, "source", "picked")));
            } catch (RuntimeException gone) {
                log.debug("[SKILL] skill_hit 帧没发出去（通道已收）: session={} skill={}",
                        cmd.getSessionId(), name);
            }
        }
    }
}
