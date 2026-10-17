package com.wenqu.ai.agentx;

import com.wenqu.ai.model.Agent;

/**
 * 本轮角色提示词段的选段口径（{@code sysPrompt} 的第一段）。
 *
 * <p><b>分层</b>：智能体的人设（{@code c_ai_agent.system_prompt}）优先，为空则回落到平台级默认
 * （{@code AppProperties.systemPrompt}，DB 可改、保存即生效）。这与 {@code Agent.systemPrompt}
 * 字段的声明一致——「提示词归智能体管；空=使用内置默认提示词」，也与 {@code ConfigService} 里
 * 「{@code chat.systemPrompt} 已退役、角色提示词归智能体管」的口径一致。
 *
 * <p><b>为什么只换角色段、不替换整条 sysPrompt</b>：咨询清单、计划/口径约束、技能段与「相关推荐」行
 * 都是<b>运行时契约</b>而非人设——人设再怎么写也不能把它们顶掉（工具挂上了却不给清单，模型只能猜 id；
 * 不挂口径，计划卡会退化成「它打算在沙盒里怎么写文件」的说明书）。那些附加段由引擎另行拼接。
 *
 * <p>抽成纯函数是为了让这条口径可被单测钉住：它此前是「注释写着归智能体管、实现里没人读」的形状，
 * 而库里已经存在 608 字的法律助手人设一直没生效。
 */
public final class RolePrompt {

    private RolePrompt() {
    }

    /**
     * @param bound          本轮绑定的智能体；可为 null（无绑定 = 平台默认档）
     * @param platformPrompt 平台级兜底提示词（{@code AppProperties.systemPrompt}）
     * @return 本轮的角色提示词段；智能体人设非空白时用它，否则用平台默认
     */
    public static String of(Agent bound, String platformPrompt) {
        if (bound != null) {
            String persona = bound.getSystemPrompt();
            if (persona != null && !persona.isBlank()) return persona;
        }
        return platformPrompt;
    }
}
