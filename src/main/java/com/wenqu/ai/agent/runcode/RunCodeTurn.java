package com.wenqu.ai.agent.runcode;

import com.wenqu.ai.agent.chat.TurnRunBoard;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 脚本子调用运行时需要的「这一轮」——刻意不认任何运行时的私有状态袋。
 * <p>存在的理由：{@code RunCodeSubCallRuntime} 原先只认手写运行时的状态袋，于是框架引擎挂不上
 * {@code run_code}——不是因为脚本跑不了，而是因为「子调用清单、扇出面板、身份与档位」这些本轮事实
 * 没有地方放。认接口而不是认状态袋，运行时就共用<b>同一条</b>子调用管线（同一条守卫链、同一份扇出
 * 清单、同一个预算计数），而不是各写一套。
 * <p>它只需要五样东西：本轮身份三元组、扇出面板（{@link TurnRunBoard}，本来就与状态袋无关）、
 * 可子调用的回调表、当前那条 SSE 通道、以及本轮的基础工具上下文。
 */
public interface RunCodeTurn {

    String sessionId();

    String turnId();

    String userId();

    /** 扇出面板：清单、在途计数、脏位、ticker 句柄与运行结果都挂这里。 */
    TurnRunBoard board();

    /** 本轮可被子调用触达的工具（已由守卫链包好）。 */
    Map<String, ToolCallback> subCallTools();

    /**
     * 当前那条 SSE 通道。<b>必须是现取而不是构造时捕获</b>：断线重连会换通道，
     * 捕获旧的那根就把进度帧送进了没人听的死通道。
     */
    SseEmitter emitter();

    /** 本轮的基础工具上下文（子调用只换预算那几项，其余转发给它）。 */
    ToolTurnContext baseContext();

    /**
     * 框架运行时：没有私有状态袋，本轮的落点按样显式给。
     *
     * @param emitter 当前通道的供应者（引擎每轮一条通道，取的时候现取）
     */
    static RunCodeTurn ofFramework(String sessionId, String turnId, String userId, TurnRunBoard board,
                                   Map<String, ToolCallback> subCallTools,
                                   java.util.function.Supplier<SseEmitter> emitter,
                                   ToolTurnContext base) {
        return new RunCodeTurn() {
            @Override
            public String sessionId() {
                return sessionId;
            }

            @Override
            public String turnId() {
                return turnId;
            }

            @Override
            public String userId() {
                return userId;
            }

            @Override
            public TurnRunBoard board() {
                return board;
            }

            @Override
            public Map<String, ToolCallback> subCallTools() {
                return subCallTools;
            }

            @Override
            public SseEmitter emitter() {
                return emitter == null ? null : emitter.get();
            }

            @Override
            public ToolTurnContext baseContext() {
                return base;
            }
        };
    }
}
