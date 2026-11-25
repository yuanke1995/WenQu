package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.TurnNotifyService;
import com.wenqu.ai.service.UserImageService;
import com.wenqu.ai.service.webhook.AgentWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 开局即败的统一出口（从 AgentscopeChatEngine 抽出）：落问题、error+done 帧、通知、webhook。
 *
 * <p>这一支不经过正常收尾（那一串收口变量压根没建起来），所以必须在这里补通知与 webhook：
 * 不补，模型都解析不出来的轮次是通知面上唯一的空白——而它恰恰是最该喊人的那种。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxFailLoud {

    private final WenquAgentxTools tools;
    private final AgentxTurnSupport turnSupport;
    private final UserImageService userImages;
    private final TurnNotifyService turnNotify;
    private final AgentWebhookService agentWebhookService;
    private final java.util.function.Function<ChatCommand, String> turnIdOf;

    public void fail(ChatCommand cmd, RunChannel channel, String message) {
        appendQuestionOnFailure(cmd);
        channel.emit(RunEventType.ERROR, message);
        channel.emit(RunEventType.DONE,
                "{\"finalContent\":\"\",\"sources\":[],\"degradations\":[],\"engine\":\"agentscope\"}");
        channel.complete();
        turnNotify.onTurnSettled(cmd, null, 0L, 0, false, false, message, false, false);
        agentWebhookService.onTurnFinished(cmd, null, "failed", message, "", null, Map.of(), List.of(), 0L);
    }

    /**
     * 失败轮的那一问。图片走 processDisplay（只落盘回显）：本轮压根没进模型，
     * 谈不上「这个模型读不读得懂图」，但用户贴的图该跟着他的问题一起留在对话里。
     */
    private void appendQuestionOnFailure(ChatCommand cmd) {
        if (cmd.isRegenerate() || cmd.isFromSteer()) return;   // 提问在上一句请求里已经落过
        if (cmd.getQuestion() == null || cmd.getQuestion().isBlank()) return;
        try {
            List<String> urls = userImages.processDisplay(cmd.getUserImages()).stream()
                    .map(UserImageService.UserImage::url).toList();
            turnSupport.appendQuestion(cmd, turnIdOf.apply(cmd), urls);
        } catch (Exception e) {
            // 问没留住不碍「这一轮失败」这件事本身：帧照发、日志照记，只是刷新后读不回这一句
            log.warn("[FAIL-LOUD] 失败轮的提问落库失败: session={} {}", cmd.getSessionId(), e.getMessage());
        }
    }
}
