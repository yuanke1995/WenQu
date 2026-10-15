package com.wenqu.ai.engine;

import com.wenqu.ai.agent.chat.ChatCommand;

/**
 * 手写运行时这一侧的引擎：把这一轮原样交回 {@code ChatService} 的流水线主体，不改一行控制流。
 * <p>它接的是函数而不是 bean——{@code ChatService → 工厂 → 本类 → ChatService} 会成构造环，
 * 由 ChatService 在 {@code @PostConstruct} 里把自己注册进来（与 {@code BranchRunner}、
 * {@code PlanModeService.PlanTurnHooks} 同一套路数）。
 */
public final class LegacyChatEngine implements ChatEngine {

    /** 本轮的执行体，由 ChatService 提供。 */
    public interface LegacyTurn {
        void run(ChatCommand cmd, RunChannel channel);
    }

    private final LegacyTurn turn;

    public LegacyChatEngine(LegacyTurn turn) {
        this.turn = turn;
    }

    @Override
    public String name() {
        return "legacy";
    }

    @Override
    public void chat(ChatCommand cmd, RunChannel channel) {
        turn.run(cmd, channel);
    }
}
