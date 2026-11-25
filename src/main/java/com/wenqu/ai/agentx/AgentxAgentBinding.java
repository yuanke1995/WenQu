package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.OrgService;
import com.wenqu.ai.service.UserConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 本轮智能体的绑定解析与实例指纹（从 AgentscopeChatEngine 抽出）。
 *
 * <p>bindingOf 把「哪个智能体 + 执行档 + 目标开关 + 配置覆盖 + 工作流发布态 + 记忆/推荐开关 +
 * 上下文窗口/最大输出」揉成一个实例指纹 key：任何一项变了都要重建实例，否则会出现「库里改了、
 * 实例没生效」。modelContextWindow/modelMaxOutput 从模型登记取窗口与上限；relatedSuggestionsEnabled
 * 读个人偏好；agentNameOf 取显示名（取不到返 null，宁缺不假）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxAgentBinding {

    private final WenquAgentxTools tools;
    private final OrgService orgService;
    private final UserConfigService userConfigService;
    private final ModelRegistryService modelRegistry;

    public AgentBinding build(ChatCommand cmd, ExecutionPolicy policy, String modelRef) {
        Agent row = null;
        try {
            row = tools.agentOf(cmd);
        } catch (RuntimeException e) {
            // 解析失败不打断本轮：按「无绑定」走平台默认档（与 rolePrompt 的兜底一致）
            log.warn("[agentx] 本轮智能体解析失败，回落平台默认档: session={} err={}",
                    cmd.getSessionId(), e.getMessage());
        }
        String key = row == null
                ? ""
                : row.getId() + "@" + (row.getUpdateTime() == null ? "" : row.getUpdateTime());
        // 执行档位也是 build 期烤进实例的（permissionContext、闸、适配器兜底），档位变了必须重建：
        // 管理员在设置页改审批档，不能等整条会话换个模型才生效。
        key = key + "|" + (policy == null ? "" : policy.preset() + "/" + policy.sandboxMode()
                + "/" + policy.approvalMode() + "/" + policy.deny().stream().sorted().toList());
        // 目标模式开关同样烤在实例里（taskContext 投影参数是 build 期装配）：智能体改了
        // goal_enabled 不重建，就会出现「库里开着、实例没投影」或反过来——界面与模型看到的不是一套
        key = key + "|goal=" + (row != null && row.getGoalEnabled() != null ? row.getGoalEnabled() : "-");
        // 本轮配置覆盖（历史版本复测/临时换资料）进实例指纹：改 revision 或 knowledgePatch 必须重建，
        // 否则工具表、技能滤镜、人设仍来自上一次 build 的实例；无 override 时不追加，旧行为逐字节不变。
        if (cmd.getAgentConfigOverride() != null && !cmd.getAgentConfigOverride().isEmpty()) {
            key = key + "|ov=" + cmd.getAgentConfigOverride().fingerprint();
        }
        // 工作流工具只存 id（Agent.java 的 workflowTools 注释承诺「重新发布即时生效」），但发布/回滚/
        // 改名不会改 agent.update_time，工具表又是 build 期固化的。把引用工作流的发布态并进指纹，
        // 让同一会话的下一次开轮重建实例并重新 mount 工具，兑现那条承诺。
        key = key + tools.workflowBindingFingerprint(row);
        // 个人「自动提炼记忆」开关也是 build 期装配（memoryConfig 还是 disableMemoryHooks）：不并进
        // 指纹的话，关掉后要等下一次重建才停，与个人设置里「关掉后下一轮起才停」的说法不符。
        key = key + "|mem=" + orgService.memoryFlushEnabled(cmd.getUserId());
        // 「接下来可以」开关也烤进系统提示词（关掉就不再挂 RelatedPrompt）：并进指纹，拨动后下一轮即重建实例生效。
        key = key + "|rel=" + relatedSuggestionsEnabled(cmd.getUserId());
        // 上下文窗口并进指纹：它决定框架的压缩阈值与输入上限，烤死在实例里。
        // 窗口恒等于模型登记上限，并进来是为了管理员改了登记值时这一轮就重建。
        int contextWindow = modelContextWindow(modelRef);
        // 最大输出同理烤在实例里（作为 max_tokens 随请求下发），登记值改了也得重建才生效。
        int maxOutput = modelMaxOutput(modelRef);
        key = key + "|ctx=" + contextWindow + "|out=" + maxOutput;
        // 思考开关仍留在指纹外：它同样烤在实例里，但续跑轮的 ChatCommand 复原不了它，并进来会让
        // 续跑按默认档重建。那一档的变更仍欠「按调用传 GenerateOptions」那一刀。
        return new AgentBinding(row, key, AgentxSkillFilter.of(row), contextWindow, maxOutput);
    }

    public int modelContextWindow(String modelRef) {
        com.wenqu.ai.model.ModelInfo mi = modelRegistry.modelInfoOf(modelRef);
        Integer max = mi == null ? null : mi.getContextWindow();
        return max == null || max <= 0 ? 0 : max;
    }

    public int modelMaxOutput(String modelRef) {
        com.wenqu.ai.model.ModelInfo mi = modelRegistry.modelInfoOf(modelRef);
        Integer max = mi == null ? null : mi.getMaxOutput();
        return max == null || max <= 0 ? 0 : max;
    }

    public boolean relatedSuggestionsEnabled(String uid) {
        String v = userConfigService.personalValue(uid, "ui.showRelatedSuggestions");
        return v == null || v.isBlank() || "true".equalsIgnoreCase(v.trim());
    }

    public String agentNameOf(ChatCommand cmd) {
        if (cmd.getAgentId() == null || cmd.getAgentId().isBlank()) return null;
        try {
            com.wenqu.ai.model.Agent a = tools.agentOf(cmd);
            return a == null ? null : a.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
