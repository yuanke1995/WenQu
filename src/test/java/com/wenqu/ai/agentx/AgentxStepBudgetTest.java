package com.wenqu.ai.agentx;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架侧步数预算闸的计数语义断言（对标 {@code StepBudgetGuard}，四条都是过去踩过的形状）。
 * <p>盯住四件事：判定与计数同一处（被拒的那步照样占预算）、跨派发累计、{@code beginTurn} 真的归零、
 * 超限要说一句可见降级而不是静默少跑。上限本身取自问答侧同一份解析，这里只验闸的行为。
 */
@DisplayName("框架护栏：工具步数预算（AgentxStepBudget）")
class AgentxStepBudgetTest {

    private static ToolUseBlock call(String name) {
        return new ToolUseBlock("id-" + name, name, Map.of());
    }

    @Test
    void 一批里超出上限的那几把被拒其余照跑() {
        AtomicReference<String> code = new AtomicReference<>();
        AgentxStepBudget budget = new AgentxStepBudget();
        budget.beginTurn("t1", 3, (c, m) -> code.set(c));

        var out = budget.allocate(List.of(call("a"), call("b"), call("c"), call("d"), call("e")), 3);

        assertEquals(3, out.allowed().size(), "上限 3 就该放行 3 把");
        assertEquals(2, out.denied().size(), "多出来的 2 把必须拒");
        assertEquals(5, budget.taken(), "被拒的那两步同样占预算——否则模型撞同一把工具就是免费重试");
        assertEquals("engine.stepBudget", code.get(), "超限必须说给前端，静默少跑会被读成配了没生效");
    }

    @Test
    void 跨派发累计到上限后整批拒绝() {
        AtomicReference<String> message = new AtomicReference<>();
        AgentxStepBudget budget = new AgentxStepBudget();
        budget.beginTurn("t1", 3, (c, m) -> message.set(m));

        budget.allocate(List.of(call("a"), call("b")), 3);
        var second = budget.allocate(List.of(call("c"), call("d")), 3);

        assertEquals(1, second.allowed().size(), "只剩 1 步预算，第二批只该跑一把");
        assertEquals(1, second.denied().size());
        assertTrue(message.get().contains("上限（3 步）"), "提示要写清上限值：" + message.get());
    }

    @Test
    void 开新一轮就归零() {
        AgentxStepBudget budget = new AgentxStepBudget();
        budget.beginTurn("t1", 2, (c, m) -> {
        });
        budget.allocate(List.of(call("a"), call("b"), call("c")), 2);
        assertEquals(3, budget.taken());

        budget.beginTurn("t2", 2, (c, m) -> {
        });
        assertEquals(0, budget.taken(), "实例按会话常驻，不归零就是上一轮的步数吃掉这一轮的预算");
        assertEquals(2, budget.allocate(List.of(call("x"), call("y"), call("z")), 2).allowed().size());
    }

    @Test
    void 零或负数上限是不限制而不是全拒() {
        AgentxStepBudget budget = new AgentxStepBudget();
        budget.beginTurn("t1", 0, (c, m) -> {
        });

        var out = budget.allocate(List.of(call("a"), call("b"), call("c")), 0);

        assertEquals(3, out.allowed().size(), "0=不限制（问渠口径），递减出不限制是最坏的结果");
        assertTrue(out.denied().isEmpty());
    }

    @Test
    void 放行与空派发都不许说一句超限() {
        AtomicInteger called = new AtomicInteger();
        AgentxStepBudget budget = new AgentxStepBudget();
        budget.beginTurn("t1", 1, (c, m) -> called.incrementAndGet());

        assertTrue(budget.allocate(List.of(), 1).denied().isEmpty());
        assertEquals(0, called.get(), "空派发不该说话");
        assertEquals(1, budget.allocate(List.of(call("a")), 1).allowed().size());
        assertEquals(0, called.get(), "放行的那把不该说话");

        budget.allocate(List.of(call("b")), 1);
        assertEquals(1, called.get(), "真超限必须说一次，静默少跑会被读成配了没生效");
    }
}
