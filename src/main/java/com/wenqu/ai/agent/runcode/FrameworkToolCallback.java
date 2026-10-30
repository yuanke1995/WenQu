package com.wenqu.ai.agent.runcode;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 框架原生工具 → Spring AI {@link ToolCallback} 的反向适配（脚本子调用只认这一种形状）。
 *
 * <p><b>为什么只给申报只读的工具</b>：脚本的扇出一次能发几十上百次调用，而写类工具在问渠的口径里
 * 要么逐项确认（几十张没人点的卡会把整段运行悬死）、要么根本不该由脚本代答（命令行那条已经因回程
 * 问题明确切断，见 {@code RunCodeSubCallRuntime#prepareSubCallPipeline}）。读类工具两样都不沾：
 * 不改外部世界、框架自己按 {@code readOnly} 免审批，纳进名册不新增任何一道需要人看的闸。
 *
 * <p><b>上下文按调用现取</b>：{@code RuntimeContext} 里是「谁 + 哪条会话」，实例按会话常驻、
 * 脚本一段接一段跑，捕获进闭包就是拿上一轮的身份去读这一轮的文件。
 */
public final class FrameworkToolCallback implements ToolCallback {

    private final AgentTool tool;
    private final Supplier<RuntimeContext> runtimeContext;
    private final long timeoutMs;

    public FrameworkToolCallback(AgentTool tool, Supplier<RuntimeContext> runtimeContext, long timeoutMs) {
        this.tool = tool;
        this.runtimeContext = runtimeContext;
        this.timeoutMs = Math.max(1_000L, timeoutMs);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 说明与参数表都取自框架工具自己那份（schema 由框架按 @Tool 方法反射生成），这里不再抄第三份
        return ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription() == null ? tool.getName() : tool.getDescription())
                .inputSchema(JSON.toJSONString(tool.getParameters()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> input = toolInput == null || toolInput.isBlank()
                ? Map.of() : JSON.parseObject(toolInput, Map.class);
        ToolResultBlock result;
        try {
            result = tool.callAsync(ToolCallParam.builder()
                    .input(input)
                    .runtimeContext(runtimeContext.get())
                    .build())
                    .block(java.time.Duration.ofMillis(timeoutMs));
        } catch (Exception e) {
            // 工具自己的失败照实说，不吞：脚本侧看得懂 ok=false，看不懂的是"什么都没返回"
            return "工具 " + tool.getName() + " 调用失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (result == null) return "工具 " + tool.getName() + " 没有返回结果（超时 " + timeoutMs + "ms）";
        return textOf(result);
    }

    /** 结果块转脚本能直接用的字符串：多块拼接，非文本块退化成类型名（不静默丢内容）。 */
    private static String textOf(ToolResultBlock result) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.getText());
            } else if (block != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append('[').append(block.getClass().getSimpleName()).append(']');
            }
        }
        return sb.toString();
    }
}
