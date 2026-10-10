package com.wenqu.ai.agent.tool;

/**
 * 本轮的工具装配上下文（P1 seam 的 Provider 侧入参）。
 * <p>
 * 与 {@link ToolTurnContext} 的分工：那份是「工具已经要执行了，本轮状态给执行层」，本份是
 * 「还没决定挂什么，本轮条件给各族自己判」。分成两个是因为二者的信息面完全不同——装配期要读
 * 智能体配置与开关，执行期要读引用列表与 SSE 落点，合成一份就会变成又一个 86 字段的
 * AnswerStreamState 影子。
 * <p>
 * 为什么要让各族自己判：迁移前「哪个开关控制哪族挂不挂、游客给不给」整份抄在
 * {@code RagService.enabledToolCallbacks} 的 if 链里，并且为免登录分享会话又整份复制了一遍
 * （知识检索与内置工具各出现两次）。判据分散的后果不是难看，而是改一处忘另一处：
 * 收紧了常规分支的开关，游客分支照旧放行。
 *
 * @author yuanke
 */
public interface ToolMountContext {

    /** 本轮提问用户（技能/MCP 这类个人资产按人取） */
    String userId();

    /** 免登录分享会话（公开链接 /s/{token}）：身份敏感的能力对匿名访客不暴露 */
    boolean guestMode();

    /**
     * 本轮是否处于检索-反思循环（Agentic RAG）。这一族有特殊待遇是刻意的：该模式本身就是
     * 「模型自主多轮检索」的显式开启，不再要求 tool.knowledgeRetrieval.enabled 子开关。
     */
    boolean reflectiveRetrieval();

    /** 全局配置开关（tool.* / webSearch.* 等，键名取自 {@link ToolDescriptor#gateKey()}） */
    boolean gateOn(String gateKey);

    /**
     * 工具总闸 {@code tool.enabled}。
     * <p>放进 {@link #gateOnFor} 而不是让每个 provider 各查一遍：装配处有一条「总闸关→一个都不挂」的
     * 提前返回，但提示词侧的能力分工段是直接问「这个工具本轮挂不挂」的。总闸不在这条判据里，
     * 就会出现「关着总闸时提示词还在教模型用 askUser」。
     */
    boolean masterOn();

    /**
     * 智能体级三态覆盖：1=强制挂、0=强制不挂、null=跟随全局。
     * <p>映射到 c_ai_agent 的哪一列由问答侧决定（那是业务实体，不该被通用装配层认识）。
     */
    Integer agentFlag(ToolFamily family);

    /**
     * 智能体对本族「具体项」的选择：null=未指定（本族全挂），空集=显式一个都不用。
     * <p>与 {@link #agentFlag} 是两道独立的闸：三态决定「这一族开不开」，白名单决定「这一族里挂哪几个」。
     * 内置工具、技能、MCP 都有这一层，其余族没有（返回 null）。
     */
    java.util.Set<String> agentSelection(ToolFamily family);

    /**
     * 三态判据：智能体显式设了 1/0 就用它，否则落到全局开关（与迁移前 {@code toolOn} 同语义）；
     * 总闸关着时恒为否（见 {@link #masterOn()}）。
     */
    default boolean gateOnFor(ToolFamily family, String gateKey) {
        if (!masterOn()) return false;
        Integer flag = agentFlag(family);
        if (flag != null) return flag == 1;
        return gateOn(gateKey);
    }

    /**
     * 该工具对本轮请求方是否可见：免登录分享会话只允许描述符显式声明 {@code guestAllowed} 的能力。
     * <p>游客的收窄口径从「装配处再抄一份分支」变成「描述符上的一个字段」——这条差别要在收紧开关时
     * 才显形：以前只改常规分支，游客分支照旧放行。
     */
    default boolean visibleToThisTurn(ToolDescriptor descriptor) {
        return !guestMode() || (descriptor != null && descriptor.guestAllowed());
    }

    /**
     * 本族是否在「工具执行确认=off」档下被整族禁掉（沙盒与 MCP 是，联网搜索刻意不是）。
     * <p>迁移前这条写在装配处两处 {@code !approvalOff &&} 上，看着像两个 if，其实是同一条平台策略
     * 作用在两个族上——策略归属见 {@link ToolFamily#blockedWhenApprovalOff()}。
     */
    default boolean approvalPolicyAllows(ToolFamily family) {
        return !(family.blockedWhenApprovalOff() && approvalOff());
    }

    /** 智能体的「工具执行确认」档位是否为 off（禁用有副作用能力） */
    boolean approvalOff();

    /**
     * 本轮的权限档位策略（P2）。
     * <p>默认 null = 本轮没有档位判定，装配不做能力面裁剪（保持迁移前形态）。问答侧从
     * {@code PermissionPolicyResolver} 取值后填这里，于是「挂不挂」与「执行时被不被拒」读同一份策略。
     */
    default com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
        return null;
    }

    /**
     * 档位是否允许这个工具出现在模型的工具表里。
     * <p>收紧发生在装配（模型看不见），而不是执行（点了被拒）：被档位禁掉的能力要与不存在不可区分，
     * 否则模型会反复尝试一个它本来不该知道的操作，白烧步数预算与 token。
     */
    default boolean policyAllows(ToolDescriptor d) {
        var policy = permissionPolicy();
        return policy == null || policy.allowsTool(d);
    }

    /**
     * 登记一条本轮可见降级（fail-loud：让用户看见「为什么这个能力没生效」，而不是静默少挂一个工具）。
     * <p>MCP 与技能的具体项引用会用到：智能体指定了别人的服务、或指定了自己没登记的服务，
     * 都要说出来。不说，「智能体挂了这个外部工具」就静默变成「什么都没挂」。
     */
    void degrade(String code, String message);

    /** 本工具是否在智能体本轮选中的「具体项」里（未指定=全都要，与 {@link #agentSelection} 同语义） */
    default boolean selectedByAgent(ToolFamily family, String toolName) {
        java.util.Set<String> only = agentSelection(family);
        return only == null || (toolName != null && only.contains(toolName));
    }
}
