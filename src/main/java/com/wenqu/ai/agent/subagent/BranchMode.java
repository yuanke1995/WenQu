package com.wenqu.ai.agent.subagent;

/**
 * 子智能体分支的执行模式（P3a）。
 * <p>
 * 两档的差别不是"强弱"，是<b>分支里到底有没有一个完整的智能体在跑</b>：
 * <ul>
 *   <li>{@link #DIGEST}——原有的「检索 + 要点提炼」：分支直接调混合检索，再用一次短 LLM 调用提炼要点，
 *       {@code internalToolExecutionEnabled(false)}，不跑工具循环、看不到子智能体自己的技能/MCP/沙盒。
 *       多视角检索场景它更省（一次检索 + 一次短调用），所以<b>保留而不是删掉</b>；</li>
 *   <li>{@link #FULL}——真全循环：分支跑整条问答管线（临时会话 + 收集型通道），于是子智能体的
 *       知识库范围、工具集、技能、沙盒、产物全部按它自己的配置生效，能独立完成
 *       「查库 → 跑代码 → 出产物」。代价是每支都是一轮完整问答（延迟与成本按轮计）。</li>
 * </ul>
 * <p>
 * 默认值是 {@link #DIGEST}：<b>平台默认档完全等于引入本字段之前的行为</b>（沿用 P2 那刀的口径），
 * 谁要全循环谁显式开，回滚只需把档位改回去，不留数据迁移。
 *
 * @author yuanke
 */
public enum BranchMode {

    /** 轻量分支：并行检索 + 要点提炼（不跑工具循环） */
    DIGEST("digest", "检索要点"),
    /** 真全循环：分支跑完整问答管线（自己的知识库范围、工具、技能、沙盒、产物） */
    FULL("full", "全循环");

    private final String code;
    private final String label;

    BranchMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 认不出的一律按 {@code fallback}（与档位解析同口径：写错不静默升档，也不静默降档） */
    public static BranchMode of(String code, BranchMode fallback) {
        if (code == null || code.isBlank()) return fallback;
        String t = code.trim();
        for (BranchMode m : values()) {
            if (m.code.equalsIgnoreCase(t)) return m;
        }
        for (BranchMode m : values()) {
            if (m.label.equals(t)) return m;
        }
        return fallback;
    }
}
