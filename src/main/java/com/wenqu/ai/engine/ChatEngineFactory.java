package com.wenqu.ai.engine;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ConfigService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按 {@code chat.engineMode} 把这一轮分派给某个引擎：{@code legacy}（默认，手写运行时）/
 * {@code agentscope}（框架运行时）/ {@code ab}（按 sessionId 哈希分流，灰度对比用）。
 * <p>框架引擎由 Phase 2 落地时自行注册；在那之前选到它一律 fail-loud 报错，
 * 绝不静默退回 legacy——那会让「我把引擎切过去了」被读成「切了没生效」。
 */
@Component
public class ChatEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatEngineFactory.class);

    /** ab 档下没配比例时的取值：一条都不走框架，避免「选了 ab 却以为在做双跑」。 */
    private static final int AB_PERCENT_UNSET = 0;

    private final ConfigService configService;
    private LegacyChatEngine legacy;
    private ChatEngine agentscope;

    public ChatEngineFactory(ConfigService configService) {
        this.configService = configService;
    }

    @PostConstruct
    void logMode() {
        String mode = configService.get("chat.engineMode");
        log.info("问答引擎分派就绪: mode={}（legacy=手写运行时, agentscope=框架运行时）ab 占比={}%",
                mode, abPercent());
    }

    public void bindLegacy(LegacyChatEngine.LegacyTurn turn) {
        this.legacy = new LegacyChatEngine(turn);
    }

    /** Phase 2：框架引擎落地时调用；未注册前 {@code agentscope}/{@code ab} 命中即报错。 */
    public void bindFramework(ChatEngine engine) {
        this.agentscope = engine;
        log.info("框架问答引擎已注册: {}", engine.name());
    }

    public ChatEngine select(ChatCommand cmd) {
        String mode = configService.get("chat.engineMode");
        if (mode == null) mode = "legacy";
        return switch (mode.trim().toLowerCase()) {
            case "agentscope" -> framework(cmd.getSessionId());
            case "ab" -> frameworkHit(cmd.getSessionId()) ? framework(cmd.getSessionId()) : legacy();
            default -> legacy();
        };
    }

    /** 同一会话始终落同一侧：分流要的是「一个会话一套行为」，不是「一轮一套」。 */
    private boolean frameworkHit(String sessionId) {
        return hitsFramework(sessionId, abPercent());
    }

    /**
     * 这条会话接下来由哪套运行时持有上下文——「压缩上下文」这类会话级操作要按这个分家。
     * <p>判据与 {@link #select} 必须一字不差（同一模式、ab 用同一个哈希），否则设置页里写着双跑、
     * 压缩却按另一侧的规矩办，用户看到的就是一句与真实行为相反的提示。
     */
    public boolean frameworkOwns(String sessionId) {
        String mode = configService.get("chat.engineMode");
        if (mode == null) return false;
        return switch (mode.trim().toLowerCase()) {
            case "agentscope" -> true;
            case "ab" -> frameworkHit(sessionId);
            default -> false;
        };
    }

    /**
     * ab 分流的落点判定：sessionId 哈希到 0~99 的桶，桶号小于占比就走框架侧。
     * <p>占比越界一律夹到 0~100——设置页填进 150 或负数时，「谁在双跑」不能变成一个没人能复算的行为。
     */
    static boolean hitsFramework(String sessionId, int percent) {
        if (sessionId == null || sessionId.isBlank()) return false;
        int clamped = Math.max(0, Math.min(100, percent));
        return Math.floorMod(sessionId.hashCode(), 100) < clamped;
    }

    /** ab 档下框架侧占比（0~100）：未登记按 0，越界夹住。 */
    private int abPercent() {
        return Math.max(0, Math.min(100, configService.getInt("chat.engineAbPercent", AB_PERCENT_UNSET)));
    }

    private ChatEngine legacy() {
        LegacyChatEngine engine = legacy;
        if (engine == null) {
            throw new IllegalStateException("问答引擎尚未就绪（legacy 运行时未注册）");
        }
        return engine;
    }

    private ChatEngine framework(String sessionId) {
        ChatEngine engine = agentscope;
        if (engine == null) {
            throw new IllegalStateException("chat.engineMode=agentscope 需要框架引擎（AgentScope 平移 Phase 2），"
                    + "尚未落地，请把该配置改回 legacy。会话=" + sessionId);
        }
        return engine;
    }
}
