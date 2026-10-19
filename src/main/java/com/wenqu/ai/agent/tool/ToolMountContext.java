package com.wenqu.ai.agent.tool;

/**
 * 本轮的工具装配上下文（P1 seam 的 Provider 侧入参）。
 * <p>
 * 与 {@link ToolTurnContext} 的分工：那份是「工具已经要执行了，本轮状态给执行层」，本份是
 * 「还没决定挂什么，本轮条件给各族自己判」。分成两个是因为二者的信息面完全不同——装配期要读
 * 智能体配置与开关，执行期要读引用列表与 SSE 落点，合成一份就会变成又一个 86 字段的
 * TurnState 影子。
 * <p>
 * 为什么要让各族自己判：迁移前「哪个开关控制哪族挂不挂、游客给不给」整份抄在
 * {@code ChatService.enabledToolCallbacks} 的 if 链里，并且为免登录分享会话又整份复制了一遍
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
     * 本轮是不是<b>子智能体分支</b>（P3a 全循环：父轮派出去、跑在一条临时会话里的那一轮）。
     * <p>默认 false = 用户在对话里直接发起的轮，行为与引入本字段之前逐字相同。它为的是把一件
     * 只有分支才有的事实说清楚：<b>没有人在看那条会话</b>，所以「要等人点」的能力在分支里
     * 不是不能用，是会把这一支挂到超时——于是装配时就不该给它。
     */
    default boolean branchTurn() {
        return false;
    }

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
        // gateKey=null：该族没有平台级子开关，总闸开启即随智能体三态/停用名单收口
        return gateKey == null || gateKey.isBlank() || gateOn(gateKey);
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
        if (!family.blockedWhenApprovalOff()) return true;
        if (approvalOff()) return false;
        // 分支轮 + 「执行前确认」= 这一族不给：没有人能批那张卡，挂上去只会让这一支在审批等待里
        // 耗尽整支超时。与 off 档同一处置口径——要拦的能力让模型看不见，而不是给了再等一个不会来的答复。
        return !(branchTurn() && approvalAsk());
    }

    /** 智能体的「工具执行确认」档位是否为 off（禁用有副作用能力） */
    boolean approvalOff();

    /** 智能体的「工具执行确认」档位是否为 ask（每次执行前等人的答复） */
    default boolean approvalAsk() {
        return false;
    }

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
     * 这个名字是否被本轮点名禁用（按名黑名单走这条，档位裁剪走 {@link #policyAllows}）。
     * <p>MCP 那族必须用它：动态工具没有静态描述符，{@code policyAllows(null)} 一律放行，
     * 于是"禁掉某个外部服务里的那一个工具"只能按名字判——名字由外部 server 自定，
     * 平台不猜它的性质，只按用户写下的名字逐字匹配。
     */
    default boolean deniesTool(String toolName) {
        var policy = permissionPolicy();
        return policy != null && policy.denies(toolName);
    }

    /**
     * 这件工具本轮开不开——<b>跨族判据的唯一汇聚点</b>，装配与提示词两侧都问它。
     * <p>落在这一层而不是各族的 {@code exposes} 里，理由与 P2 那条黑名单相同：判据按族写就迟早
     * 漏掉没重写 {@code exposes} 的那几族（检索族还因「反思轮强制暴露」直接 {@code ||} 掉了默认实现）。
     * <p>分支轮（{@link #branchTurn()}）不收 {@code viewerRequired} 的工具：提问卡在没人看的临时会话里
     * 只会把这一支挂到超时，任务清单写进去也没有人读——与其挂上去等一件永远不会发生的事，
     * 不如让模型压根看不到这个能力（与档位裁剪同一条「收窄要与不存在不可区分」的口径）。
     */
    default boolean turnAllows(ToolDescriptor d) {
        if (!policyAllows(d)) return false;
        return !branchTurn() || d == null || !d.viewerRequired();
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
