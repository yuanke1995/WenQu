package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.UserImageService;
import io.agentscope.core.message.Msg;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.agentx.turn.TurnPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 本轮送模型的输入拼装（从 AgentscopeChatEngine 抽出）：目标上下文、重新计划血缘、多模态快照。
 *
 * <p>目标模式：用户这一轮也带上当前目标的一句上下文（跨轮目标不能只活在更早的提示词里，
 * 压缩/换设备后仍要让助手知道自己正在做什么）。判据与界面入口同源（GoalService.enabledFor）：
 * 助手关掉目标模式后，界面不再露入口、这一轮也不再塞目标——两边必须说同一句话。
 *
 * <p>重新计划血缘只在本轮确实开计划模式时拼：普通问答带 planBaseId 没有意义，也别往上下文塞一整份旧计划。
 * 目标/血缘都必须拼在 buildMultimodalMsg 之前——多模态那一支是从 modelInput 拍的快照，晚拼就丢了。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnInput {

    private final GoalService goalService;
    private final TurnPromptAssembler assembler;
    private final java.util.function.Function<ChatCommand, String> mentionDirective;

    /** 拼装结果：最终输入、是否带目标、以及多模态快照（图片形态不可用时为 null，退回纯文本 submit）。 */
    public record Result(String modelInput, boolean goalActive, Msg multimodal) {
    }

    /**
     * 计划模式按轮进入（跑完必须退出）。是否进入只由两件事决定：用户显式开计划开关，
     * 或模型自己在推理中调用 plan_enter。问渠不做「按消息意图预判」，避免在模型之外多一层会误判的闸。
     *
     * @param setPlanMode 引擎的 sessions.setPlanMode 转发
     */
    public boolean engagePlan(ChatCommand cmd, RunChannel channel,
                              java.util.function.BiPredicate<ChatCommand, Boolean> setPlanMode) {
        boolean planWanted = cmd.isPlanMode();
        boolean planEngaged = planWanted && setPlanMode.test(cmd, true);
        if (planWanted && !planEngaged) {
            log.warn("[PLAN] 计划模式请求但未进入激活位（框架侧未就位），本轮不展示计划卡: session={}",
                    cmd.getSessionId());
            channel.emit(com.wenqu.ai.engine.RunEventType.WARN,
                    "计划模式未能启用（框架侧未进入计划位），本轮将不使用计划卡");
        }
        return planEngaged;
    }

    /**
     * 送进模型的不只是问题原文：附件正文 / @ 引用 / # 历史引用都在这里拼进去。只送问题原文的话，
     * 用户传的东西模型一概看不见——落库只保证人能回看到，不等于模型收到了。
     * <p>用户改动对账（轮首）只等一小会儿，快照慢就跳过——对账是增强，不能把每一轮都拖住。
     * 放在 modelInput 而不是系统提示词里：系统提示词是 build 期烤进常驻实例的，轮级事实进不去。
     */
    public String userContent(ChatCommand cmd, AgentBinding binding, String imgNote,
                              java.util.function.Supplier<String> driftNote) {
        String base = assembler.userContentWithContext(cmd, binding.skillFilter(),
                mentionDirective.apply(cmd)) + imgNote;
        String drift = driftNote.get();
        return drift.isEmpty() ? base : base + drift;
    }

    public Result assemble(ChatCommand cmd, String modelInput, AgentBinding binding, boolean planEngaged,
                           boolean chatSeesImages, List<UserImageService.UserImage> userImgs) {
        String withGoal = appendGoal(cmd, modelInput, binding);
        String withPlan = appendPlanBase(cmd, withGoal, planEngaged);
        Msg multimodal = (chatSeesImages && !userImgs.isEmpty())
                ? com.wenqu.ai.agentx.turn.TurnPromptAssembler.buildMultimodalMsg(withPlan, userImgs) : null;
        boolean goalActive = !goalNote(cmd, binding).isEmpty();
        return new Result(withPlan, goalActive, multimodal);
    }

    private String appendGoal(ChatCommand cmd, String modelInput, AgentBinding binding) {
        String note = goalNote(cmd, binding);
        return note.isEmpty() ? modelInput : modelInput + note;
    }

    private String goalNote(ChatCommand cmd, AgentBinding binding) {
        return goalService.enabledFor(binding.row()) ? goalService.contextNoteFor(cmd.getSessionId()) : "";
    }

    private String appendPlanBase(ChatCommand cmd, String modelInput, boolean planEngaged) {
        String planBaseNote = cmd.getPlanBaseContext();
        if (!planEngaged || planBaseNote == null || planBaseNote.isBlank()) return modelInput;
        return modelInput + planBaseNote;
    }
}
