package com.wenqu.ai.agentx;

import java.util.Map;

/**
 * 框架原生工具的卡片展示名（口语向）与悬停说明。
 *
 * <p><b>为什么需要这张表</b>：问渠自己的工具（{@code consultAgent}、{@code writeTodo}、
 * {@code search_knowledge}…）由 {@link com.wenqu.ai.agent.tool.ToolRegistry} 统一声明中文名，
 * 两条运行时都从那里取——但框架自己挂上去的那几把（{@code PlanModeTools} 的 plan_* 三把）
 * <b>不在问渠的注册中心里</b>：它们是 {@code agentscope-harness} 的类，既没有
 * {@code @Tool} 方法可反射，也不受问渠的族开关与审批档位管。原先框架引擎下发工具卡时
 * 直接把工具裸名塞进 {@code label}（{@code AgentscopeChatEngine#toolCard} 的
 * {@code esc(name)}），前端 {@code toolLabel} 认得出「这不是展示名」而回落本地映射，
 * 而本地映射里没有 plan_* —— 于是用户看到的是 {@code plan_write} 这种英文裸名。
 *
 * <p><b>为什么不把它们塞进 ToolRegistry</b>：注册中心有一条启动期硬核对
 * （{@code ToolRegistry#validate}：声明名必须与 holder 上反射出的 {@code @Tool} 方法逐一对上），
 * 框架工具过不了那条——塞进去只会在启动日志里刷一条「声明了却不存在的方法」的 ERROR，
 * 换不来任何东西。它们也不该出现在管理员工具清单里：那把清单讲的是「本平台有哪些可配置的工具」，
 * 而 plan_* 是执行模式的内部机制，用户没有开关可关它。
 *
 * <p><b>取名口径</b>：与 {@code ToolDescriptor#cardLabel} 同一个口径——用动词短语写「它在干什么」，
 * 而不是把机制名翻译一遍（{@code plan_write} 不是「写计划」而是「把计划写下来给人看」）。
 *
 * <p><b>覆盖范围</b>：只收<b>框架挂的、且会在工具卡上露面</b>的。问渠自己的工具一律不进这张表——
 * 它们的名字归注册中心管，两处都写就是又一处会漂移的副本。
 *
 * @author yuanke
 */
final class FrameworkToolLabels {

    private FrameworkToolLabels() {
    }

    /** 工具名 → 卡片展示名（口语向）。取不到返回 null，由调用方按「未知工具」处理，不硬造翻译。 */
    private static final Map<String, String> LABELS = Map.of(
            PlanModeToolsNames.ENTER, "开始规划方案",
            PlanModeToolsNames.WRITE, "把计划写下来",
            PlanModeToolsNames.EXIT, "提交计划待确认");

    /** 工具名 → 悬停一句话说明（这个工具到底在干什么；未收录的不给 title）。 */
    private static final Map<String, String> HINTS = Map.of(
            PlanModeToolsNames.ENTER, "先只读地调研与设计方案，这期间不改任何东西",
            PlanModeToolsNames.WRITE, "把想好的方案写成计划，你点开就能看",
            PlanModeToolsNames.EXIT, "计划写完了，请你过目；你同意后它才开始动手");

    /**
     * 框架计划模式三把工具的名字。
     *
     * <p>刻意用字面量而<b>不引框架的 {@code PlanModeTools.PLAN_ENTER} 常量</b>：那张表是
     * 「万一框架改了工具名，我们照样显示得对」的兜底，编译期 coupling 到框架常量就等于把这个
     * 兜底的前提又交回给框架。真要改名时这里的旧名取不到值、落回裸名，行为与今天一致（可发现），
     * 而不是跟着改名后静默指向另一个工具。
     */
    static final class PlanModeToolsNames {
        static final String ENTER = "plan_enter";
        static final String WRITE = "plan_write";
        static final String EXIT = "plan_exit";

        private PlanModeToolsNames() {
        }
    }

    /** 卡片展示名；未知工具返回 null（调用方按注册中心/前端映射兜底，绝不返回英文裸名冒充展示名） */
    static String label(String toolName) {
        return toolName == null ? null : LABELS.get(toolName);
    }

    /** 悬停说明；未收录返回 null（前端据此不显示 title，好过显示一句编出来的解释） */
    static String hint(String toolName) {
        return toolName == null ? null : HINTS.get(toolName);
    }
}