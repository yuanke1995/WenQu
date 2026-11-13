package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 提问卡（人在回路）的框架原生实现：工具主动挂起，由框架记成待办（实测 {@code kind=external_execution}），
 * 用户在问渠界面答完后 {@code AgentSession.respond(requestId, SessionAnswer.text(...))} 接续原任务。
 * <p>为什么不复用问答侧那个内置 {@code askUser}：它靠 {@code ToolContext} 里的函数阻塞在工具线程上等 future。
 * 实测两件事——① Spring AI 会把工具方法抛出的异常包掉，挂起信号到不了框架，模型只看到「工具失败」
 * 就自己编了答案；② 两套「等人」并存早晚对不上。所以框架内只留框架那一套：这里没有阻塞、没有第二份待办账。
 * <p>参数面 {@code topic / question / options / multi}，其中 {@code multi} 决定这张卡是单选还是可多选
 * （省略即单选）。框架只把入参 Map 存进待办、把答案当一条文本回灌，选项语义它不认，
 * 所以单选/多选的判据只在问渠这一侧：题面下发、卡片渲染、答案拼装三处同一个字段。
 */
public final class AskUserTool extends ToolBase {

    public static final String NAME = "askUser";

    public AskUserTool() {
        super(NAME, "就缺失的信息、偏好或要拍板的事，向用户提出简短、直接、自足的问题。"
                + "用用户能懂的说法，默认他不知道后台做了什么、也不认识内部名词。"
                + "某一题的答案为空，表示用户跳过了这一题：带着「没答」继续，不要再问同一题，"
                + "也不要把跳过当成许可。返回 pending 则表示超时前没收到这一批作答，用户仍然可以作答。"
                + "能给推荐项就把推荐放第一项；默认单选，只有用户可能同时要几项时才把该题标 multi。",
                Map.of("type", "object", "properties", Map.of(
                                "question", Map.of("type", "string",
                                        "description", "要问用户的问题：一句话交代背景与要决定的事"),
                                "options", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "2~3 个候选，每项「关键词：一句话说明」；第一项视为推荐"),
                                // 单/多选由这一题自己带，不按选项数或题面措辞猜：猜出来的选择面会把
                                // 「走哪条路」这类互斥题变成能同时勾三项的自相矛盾答案
                                "multi", Map.of("type", "boolean",
                                        "description", "该题是否允许多选。只在用户可能同时要几项时为 true——"
                                                + "勾选范围、组成部分、可并存的素材或模块；省略即单选。"
                                                + "「两条路选哪条」「用哪个方案」这类互斥取舍一律不要设 true。"
                                                + "界面会自动在题面旁标出「可多选」，题面里不用再写一遍"),
                                "topic", Map.of("type", "string", "description", "话题标签，2~6 字短语；可省略")),
                        "required", java.util.List.of("question")),
                // readOnly=true：提问不写任何东西，不该被「禁用有副作用能力」的档位挡掉
                true, false, false, null, false, false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        // 挂起原因带的是本轮要问的题面（框架原样存在待办里，刷新页面/重连仍能看到这张卡）
        return Mono.error(new ToolSuspendException(JSON.toJSONString(input)));
    }

    /**
     * 提问不改变任何状态，权限上直接放行。
     * <p>不覆写就会沿用 {@link ToolBase} 默认的 passthrough，而权限引擎对 passthrough 的默认分支是
     * <b>执行前确认</b>——模型一提问，用户收到的是「有一个操作等你批准」，点开还恢复不了，
     * 这一轮就挂在审批上等人到超时。提问卡只能由工具挂起产生，不能先过一道确认。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("提问不改变任何状态"));
    }
}
