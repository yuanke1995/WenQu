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
     * 智能体级三态覆盖：1=强制挂、0=强制不挂、null=跟随全局。
     * <p>映射到 c_ai_agent 的哪一列由问答侧决定（那是业务实体的知识，不该放进通用装配层）。
     */
    Integer agentFlag(ToolFamily family);

    /**
     * 该工具对本轮请求方是否可见：免登录分享会话只允许描述符显式声明 {@code guestAllowed} 的能力。
     * <p>游客的收窄口径从「装配处再抄一份分支」变成「描述符上的一个字段」——这条差别要在收紧开关时
     * 才显形：以前只改常规分支，游客分支照旧放行。
     */
    default boolean visibleToThisTurn(ToolDescriptor descriptor) {
        return !guestMode() || (descriptor != null && descriptor.guestAllowed());
    }

    /**
     * 三态判据：智能体显式设了 1/0 就用它，否则落到全局开关（与迁移前 {@code toolOn} 同语义）。
     */
    default boolean gateOnFor(ToolFamily family, String gateKey) {
        Integer flag = agentFlag(family);
        if (flag != null) return flag == 1;
        return gateOn(gateKey);
    }
}
