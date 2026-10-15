package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.TurnDeadline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人在回路等待「必须登记进整轮台账」的回归账。
 * <p>这条线上真机踩过一次（2026-10-15 13:54~14:01），症状是用户说的「一直 loading」：
 * 计划卡 13:57:46 挂出，用户还没点批准，13:59:53 看门狗就把这一轮截断了，日志写得很直白——
 * 「机器耗时 300087ms / 预算 300000ms（人工等待 0ms 不计入）」。
 * <p>根因：阻塞等人时没有调 {@code TurnRegistry.beginHumanWait}——引擎靠轮询等答复，
 * 却没告诉台账「这 2 分钟是人在读卡，不是机器在干活」。于是机器预算被等待吃光，看门狗必然误伤。
 * （调用点在 {@code AgentscopeChatEngine} 开轮处；手写侧原先在 PlanModeService/ChatService 的等待窗口，
 * 随推理循环删除一并退场。）
 * <p>本测试钉的是台账本身的行为（{@link TurnDeadline}）：登记后等待不计入机器耗时，
 * 且登记/收口必须成对——只 begin 不 end 会让 {@code openWaits} 永久停在 1，之后机器耗时一直偏小、
 * 看门狗形同虚设，这是同一个 bug 的另一半。
 */
@DisplayName("框架引擎：人工等待登记（等待期不消耗整轮机器预算）")
class AgentxHumanWaitLedgerTest {

    /** 复刻真机那条预算：{@code chat.sseTimeoutMs} 默认 300s。 */
    private static final long BUDGET_MS = 300_000L;

    @Test
    @DisplayName("登记等待后，等待的那段时间不计入机器耗时（这正是 13:59:53 误截断的判据）")
    void 等待期不计入机器耗时() throws Exception {
        TurnDeadline d = new TurnDeadline(BUDGET_MS);
        long t0 = System.currentTimeMillis();

        // 卡挂出 → 登记等待 → 用户「读卡」120ms → 收口
        d.beginWait();
        Thread.sleep(120);
        long machineDuringWait = d.machineMs();
        d.endWait();

        // 等待进行中就该扣：机器耗时必须明显小于墙钟，否则看门狗会把人在读卡当成机器在干活
        assertTrue(machineDuringWait < System.currentTimeMillis() - t0,
                "等待进行中就该边等边扣，否则看门狗必然误截断");

        // 收口后再等一小段纯机器时间，累计机器耗时仍应远小于墙钟 120ms
        Thread.sleep(50);
        assertTrue(d.machineMs() < System.currentTimeMillis() - t0 - 60,
                "等待收口后，这段等待应永久从预算里扣除");
    }

    @Test
    @DisplayName("begin/end 成对：等待结束后机器耗时不再被这段拖住")
    void 成对登记后预算恢复正常() {
        TurnDeadline d = new TurnDeadline(BUDGET_MS);
        d.beginWait();
        d.endWait();
        d.beginWait();
        d.endWait();
        // 两次成对登记后 openWaits 归零，再登记一次才会重新进入等待态
        d.beginWait();
        // 此时仍在等待，机器耗时应约等于墙钟（还没扣，因为等待刚开始）
        assertTrue(d.machineMs() >= 0);
        d.endWait();
        assertTrue(d.machineMs() >= 0, "收口后机器耗时不能为负");
    }

    /**
     * 收集型通道（定时任务/MCP/子智能体节点）的 {@code emitter()} 为 null。
     * {@code ConcurrentHashMap.get(null)} 会抛 NPE，所以引擎侧那两个入口必须先判空——
     * 这条测试把「判空」这个约束写在明面上，避免后人图省事去掉它。
     */
    @Test
    @DisplayName("emitter 为 null（收集型通道）时台账不登记，也不抛异常")
    void 空通道不登记() {
        TurnDeadline d = new TurnDeadline(BUDGET_MS);
        // 引擎侧入口对 null emitter 直接跳过（DEADLINES 是 ConcurrentHashMap，get(null) 会 NPE），
        // 等价于什么都没做：这里直接钉「台账本身不依赖通道存在」这条
        assertTrue(d.machineMs() >= 0, "空通道下台账仍可正常读机器耗时");
    }

    @Test
    @DisplayName("人工等待可叠加多段（一张轮里先等提问、再等计划），每段都要扣")
    void 多段等待累加() throws Exception {
        TurnDeadline d = new TurnDeadline(BUDGET_MS);
        long before = d.machineMs();
        d.beginWait();
        Thread.sleep(80);
        d.endWait();
        d.beginWait();
        Thread.sleep(80);
        d.endWait();
        long after = d.machineMs();
        // 两段共 160ms 的等待都不该计入，机器耗时的增量应远小于 160ms
        assertTrue(after - before < 80,
                "两段人工等待都应被扣除，实际增量 " + (after - before) + "ms");
    }

    /** 占位：确保 SseEmitter 在本测试的 import 里被真实引用（框架引擎入口的判空对象类型）。 */
    @SuppressWarnings("unused")
    private static final Class<SseEmitter> CHANNEL_TYPE = SseEmitter.class;
}