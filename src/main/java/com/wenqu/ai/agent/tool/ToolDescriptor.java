package com.wenqu.ai.agent.tool;

import java.util.function.Predicate;

/**
 * 工具描述符（平台内一个工具的单一真源）。
 * <p>
 * 迁移前这一个工具的信息摊在四处：{@code ToolInventoryService.LABELS}（管理清单中文名）、
 * {@code ToolInventoryService} 里硬编码的 sensitive 集合、{@code RagService.enabledToolCallbacks}
 * 的 if 链（哪个开关控制挂不挂、游客给不给、算不算有副作用）、以及前端 {@code projections.js} 的
 * TOOL_LABELS/TOOL_DESCS（对话卡片口语名）。四处各自演进的结果是：新工具上线时清单页显示裸英文名
 * （writeTodo 就漏过），改一个开关名要同步三处，而「提示词教了模型、工具却没挂上」只能靠注释约束
 * （{@code builtinToolExposed} 的存在就是为这个补丁打的）。
 * <p>
 * 本类型是那份「四处」的替代起点。两个中文名都留下且有意的：{@code label} 是管理视角的术语口径
 * （知识库精确检索），{@code cardLabel} 是用户视角的口语口径（查知识库资料）——它们面向的人不同，
 * 强行合成一个会两边都不合适。前端在拿不到后端下发的卡片名时仍用自己那份兜底映射（历史消息、
 * 免登录分享页的旧记录里没有 label 字段）。
 *
 * @param name             模型可见工具名（@Tool 方法名或显式 name；改名是破坏性变更，前端历史与提示词都引用它）
 * @param family           所属族
 * @param label            管理清单中文名（术语向）
 * @param cardLabel        对话卡片展示名（口语向，随 tool_status 载荷下发给前端）
 * @param cardHint         卡片悬停一句话说明（可空）
 * @param sideEffect       客观副作用等级，见 {@link SideEffect}
 * @param gateKey          本工具的全局开关配置键（null=只受总闸 tool.enabled 管）
 * @param guestAllowed     免登录分享会话（游客）是否可见
 * @param requiresApproval 是否属于「有副作用工具」（审批模式 ask 时执行前要用户点确认）；静态取值
 * @param approvalGateKey  非 null 时改用该配置项决定是否需要审批（联网搜索的 webSearch.requireApproval）
 * @param viewerRequired   这件能力<b>要有正在看这一轮的人</b>才成立：提问卡等人点、任务清单写进会话给人看。
 *                        它和 {@code sideEffect} 是两个轴——askUser 不改世界（PURE），但没有人的那一轮
 *                        它只会阻塞到超时；子智能体分支（P3a）跑的是一条没有观众、跑完即删的临时会话，
 *                        所以这类工具在分支轮<b>不挂</b>，而不是挂上去等一张永远不会来的卡。
 * @author yuanke
 */
public record ToolDescriptor(
        String name,
        ToolFamily family,
        String label,
        String cardLabel,
        String cardHint,
        SideEffect sideEffect,
        String gateKey,
        boolean guestAllowed,
        boolean requiresApproval,
        String approvalGateKey,
        boolean viewerRequired) {

    /** 既有声明的便捷构造：没有特别说明就是「不需要有人在场」（引入 viewerRequired 前的形态） */
    public ToolDescriptor(String name, ToolFamily family, String label, String cardLabel, String cardHint,
                          SideEffect sideEffect, String gateKey, boolean guestAllowed, boolean requiresApproval,
                          String approvalGateKey) {
        this(name, family, label, cardLabel, cardHint, sideEffect, gateKey, guestAllowed, requiresApproval,
                approvalGateKey, false);
    }

    /** 需要审批的判定：配了 approvalGateKey 的按配置走（联网搜索），否则用静态取值（沙盒恒真、内置恒假） */
    public boolean requiresApproval(Predicate<String> configOn) {
        if (approvalGateKey != null) {
            return configOn != null && configOn.test(approvalGateKey);
        }
        return requiresApproval;
    }

    /** 卡片展示名：口语名缺省时退到术语名，两者都缺省退到工具名本身（绝不返回空串给前端） */
    public String displayLabel() {
        if (cardLabel != null && !cardLabel.isBlank()) return cardLabel;
        if (label != null && !label.isBlank()) return label;
        return name;
    }
}
