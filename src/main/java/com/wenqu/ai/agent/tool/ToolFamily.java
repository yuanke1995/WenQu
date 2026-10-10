package com.wenqu.ai.agent.tool;

/**
 * 工具族（P1 seam 化的第一层归属）。
 * <p>
 * 为什么要有它：一个工具的「挂不挂、要不要审批、游客能不能用、开关在哪、卡片叫什么」历史上散在
 * {@code RagService.enabledToolCallbacks} 的 if 链、{@code ToolInventoryService.LABELS}、
 * {@code ToolInventoryService} 里硬编码的 sensitive 集合、以及前端两份映射里——同一族工具的属性
 * 被抄了四份，改一处漏三处（writeTodo 上线时清单页就直接漏了中文名，就是这个机制的实测代价）。
 * 族是收口的单位：每个 {@link ToolFamilyProvider} 一次把本族全部 {@link ToolDescriptor} 声明清楚，
 * 其余地方一律来查它。
 * <p>
 * 刻意用枚举而不是自由字符串：族名同时是配置前缀口径（{@code tool.*} / {@code webSearch.*}）
 * 与提示词能力段的分组依据，写错族名不该静默变成「又冒出一个新族」。
 *
 * @author yuanke
 */
public enum ToolFamily {

    /** 内置高频工具：纯计算、无副作用、无外部依赖（准入原则见 BuiltinTools 类注释） */
    BUILTIN("builtin", "内置高频工具"),
    /** 知识库精确检索 */
    KNOWLEDGE("knowledge", "知识库精确检索"),
    /** 联网搜索（Provider 壳，外部计费与配额都在这族） */
    WEBSEARCH("websearch", "联网搜索"),
    /** 产物交付（模型生成的可下载文件） */
    ARTIFACT("artifact", "产物交付"),
    /** 技能（Skills 渐进披露的取回端，个人资产） */
    SKILL("skill", "技能（Skills）"),
    /** 任务清单（writeTodo） */
    TODO("todo", "任务清单"),
    /** 沙盒执行（隔离容器里的命令与文件读写） */
    SANDBOX("sandbox", "沙盒执行"),
    /** MCP 外部工具：按用户登记动态合入，没有静态清单 */
    MCP("mcp", "MCP 外部工具");

    private final String code;
    private final String familyLabel;

    ToolFamily(String code, String familyLabel) {
        this.code = code;
        this.familyLabel = familyLabel;
    }

    public String code() {
        return code;
    }

    public String familyLabel() {
        return familyLabel;
    }

    /** MCP 工具名由外部 server 自定，按名归属族时的兜底判定（前缀由 McpClientService 生成） */
    public static boolean looksLikeMcpTool(String name) {
        return name != null && (name.startsWith("wen_qu") || name.startsWith("w_q_"));
    }
}
