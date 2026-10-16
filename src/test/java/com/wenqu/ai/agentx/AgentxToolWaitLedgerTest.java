package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具卡「等人」那段时间该扣在谁账上的回归账。
 *
 * <p><b>真机症状</b>（本机 2026-10-16 08:55，会话 7031ec04…，消息 98419fe4…）：计划轮里
 * {@code plan_write} 这张卡落库带着 {@code elapsedMs=160610}（2m40s），而它自己只跑了三百毫秒——
 * 那 160288ms 是用户在读<b>同一批里 {@code plan_exit} 的批准卡</b>。界面读起来就是
 * 「plan_write 这个工具慢得离谱」，正是这笔改动要消灭的那个错法。
 *
 * <p><b>根因</b>：等待只摊给挂卡那把工具（{@code beginToolWait(pendingCallKeys)} 的那份判据）。
 * 但人在回路的这道闸门卡住的是<b>整个工具步</b>：框架在批准之后才把 {@code plan_write} 的
 * {@code tool/result} 发出来，于是它的窗口整段盖住了等待，结算时却没减任何东西。
 *
 * <p><b>为什么不能把 waitedMs 也一起摊给每张卡</b>：前端整段跨度是「首尾之差 − 各张卡 waitedMs 之和」，
 * 同一段 160288ms 记到两张卡上就是 320576ms，比整个 169901ms 的窗口还长，跨度被夹成 0。
 * 所以这里刻意分成两笔：<b>扣账</b>（算自己的 elapsedMs）摊给等待期间在飞的每一把，
 * <b>记账</b>（对外那份 waitedMs）只落挂卡那一张。本测试钉的就是这两笔不能互相串。
 */
@DisplayName("框架引擎：等待的扣账摊全员、记账只落挂卡那一张")
class AgentxToolWaitLedgerTest {

    /** 只喂计时与卡片登记要的那几样：spills 用空队列、展示名用空注册中心，其余传 null（本路径不碰它们）。 */
    private static AgentscopeChatEngine.Recording newRecording() {
        return new AgentscopeChatEngine.Recording(new LinkedList<>(), null, null, null,
                new ToolDisplayNames(null));
    }

    /** 复刻一次 tool/result 结算：取值顺序与引擎 drain 里那一段一致（开始→扣账→记账→算耗时→登记卡片）。 */
    private static Map<String, Object> settle(AgentscopeChatEngine.Recording r, String key, String name) {
        long beganAt = r.takeToolStart(key);
        long covered = r.takeToolWait(key);
        long charged = r.takeToolCharge(key);
        long elapsed = beganAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
        r.toolCall(name, "done", "{}", elapsed, beganAt, charged);
        List<Map<String, Object>> cards = r.toolCallList();
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (name.equals(cards.get(i).get("name"))) return cards.get(i);
        }
        throw new AssertionError("卡片没登记出来: " + name);
    }

    private static long num(Map<String, Object> card, String field) {
        Object v = card.get(field);
        return v == null ? -1L : ((Number) v).longValue();
    }

    /** 字段缺位（putTiming 只写有值的）按 0 看：这张卡的机器耗时短到一个毫秒都没跨过。 */
    private static long numOrZero(Map<String, Object> card, String field) {
        return Math.max(0L, num(card, field));
    }

    @Test
    @DisplayName("同一步里另一把在飞的卡：耗时扣掉等待，但不重复记等待账")
    void 非挂卡的在飞调用也扣等待() throws Exception {
        AgentscopeChatEngine.Recording r = newRecording();
        String planWrite = "call_w";
        String planExit = "call_e";
        r.toolCall("plan_write", "start", "{}", 0L, r.markToolStart(planWrite), 0L);
        r.toolCall("plan_exit", "start", "{}", 0L, r.markToolStart(planExit), 0L);

        r.beginToolWait(List.of(planExit));   // 挂的是 plan_exit 的批准卡
        Thread.sleep(80L);
        r.endToolWait();

        Map<String, Object> writeCard = settle(r, planWrite, "plan_write");
        Map<String, Object> exitCard = settle(r, planExit, "plan_exit");

        long writeElapsed = numOrZero(writeCard, "elapsedMs");
        assertTrue(writeElapsed < 60,
                "plan_write 只跑了几毫秒，等待必须从它自己那本账里出去（实测 " + writeElapsed + "ms）");
        assertFalse(writeCard.containsKey("waitedMs"),
                "非挂卡的那张不许带 waitedMs：跨度按各张之和减，带两张就把同一段等扣两遍");

        assertTrue(num(exitCard, "waitedMs") >= 60,
                "这段等待必须有一处记账（跨度要减它，否则簇头照样读出 2m40s）");
        assertTrue(numOrZero(exitCard, "elapsedMs") < 60,
                "挂卡那张自己的耗时同样要扣干净");
    }

    @Test
    @DisplayName("整批只有一把在飞：扣账与记账都落在它身上")
    void 单张挂卡两笔账同源() throws Exception {
        AgentscopeChatEngine.Recording r = newRecording();
        String ask = "call_ask";
        r.toolCall("askUser", "start", "{}", 0L, r.markToolStart(ask), 0L);
        r.beginToolWait(List.of(ask));
        Thread.sleep(60L);
        r.endToolWait();

        Map<String, Object> card = settle(r, ask, "askUser");
        // 真机那一轮的形状（09:03 会话 bbe22247…）：elapsed=377 / waited=60377
        assertTrue(num(card, "waitedMs") >= 40, "等待记在挂卡那张");
        assertTrue(numOrZero(card, "elapsedMs") < 40,
                "耗时只剩机器真正干活的那一截");
    }

    @Test
    @DisplayName("归属认不出来时记给在飞的第一把，扣账仍摊全员")
    void 兜底不丢账() throws Exception {
        AgentscopeChatEngine.Recording r = newRecording();
        String a = "call_a";
        String b = "call_b";
        r.markToolStart(a);
        r.markToolStart(b);
        r.beginToolWait(List.of());          // pendingCallKeys 认不出卡形状 → 空集合
        Thread.sleep(50L);
        r.endToolWait();

        Map<String, Object> cardA = settle(r, a, "writeTodo");
        Map<String, Object> cardB = settle(r, b, "write_file");
        long charged = Math.max(num(cardA, "waitedMs"), 0L) + Math.max(num(cardB, "waitedMs"), 0L);
        assertTrue(charged >= 40, "兜底也得把这段等待记出去一张，否则跨度减不到它");
        assertEquals(1, (num(cardA, "waitedMs") >= 0 ? 1 : 0) + (num(cardB, "waitedMs") >= 0 ? 1 : 0),
                "记账只允许出现在一张卡上（两张同时带就把一段等扣两遍）");
    }

    @Test
    @DisplayName("没有在飞的调用时：这段等待只进整轮账，不落任何卡")
    void 空在飞不落卡() throws Exception {
        AgentscopeChatEngine.Recording r = newRecording();
        r.beginToolWait(List.of("call_none"));
        Thread.sleep(30L);
        r.endToolWait();                     // 不该抛，也不该留下脏账
        assertEquals(0L, r.takeToolWait("call_none"), "没在飞就不该有扣账残留");
        assertEquals(0L, r.takeToolCharge("call_none"), "没在飞就不该有记账残留");
    }
}
