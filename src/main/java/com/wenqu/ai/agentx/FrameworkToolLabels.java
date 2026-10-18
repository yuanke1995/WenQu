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
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(PlanModeToolsNames.ENTER, "开始规划方案"),
            Map.entry(PlanModeToolsNames.WRITE, "把计划写下来"),
            Map.entry(PlanModeToolsNames.EXIT, "提交计划待确认"),
            // 全框架化 P1：沙箱与文件系统改由框架提供后，这七把也走本表。
            // 问渠侧同名工具已退役（见 SandboxToolProvider#descriptors），模型看到的工具名不变，
            // 但执行方换成框架 FilesystemTool / ShellExecuteTool，卡片文案得跟上，否则界面上
            // 会退回前端自己的英文名映射。
            Map.entry(FilesystemToolNames.READ_FILE, "读取沙盒文件"),
            Map.entry(FilesystemToolNames.WRITE_FILE, "写入沙盒文件"),
            Map.entry(FilesystemToolNames.EDIT_FILE, "编辑沙盒文件"),
            Map.entry(FilesystemToolNames.GREP_FILES, "在沙盒里搜内容"),
            Map.entry(FilesystemToolNames.GLOB_FILES, "按文件名模式找文件"),
            Map.entry(FilesystemToolNames.LIST_FILES, "查看沙盒目录"),
            Map.entry(FilesystemToolNames.EXECUTE, "执行沙盒命令"),
            // 全框架化 P3：技能与记忆也换成框架工具面后，这十一把同样只露裸名。
            // 技能那三把是每次用技能都会露面的一把（load_skill_through_path），漏它的表现
            // 就是界面上出现一个没人看得懂的英文函数名。
            Map.entry("load_skill_through_path", "打开技能说明书"),
            Map.entry("skill_manage", "改写我的技能"),
            Map.entry("propose_skill", "把做法草拟成技能"),
            Map.entry("memory_search", "翻长期记忆"),
            Map.entry("memory_get", "读记忆原文"),
            Map.entry("memory_save", "记进长期记忆"),
            Map.entry("session_search", "搜以前的会话"),
            Map.entry("session_list", "列出以前的会话"),
            Map.entry("session_history", "回看某次会话"),
            Map.entry("deliver_artifact", "保存生成的文件"),
            // 子智能体已改为框架接管（全框架化 P0）：派活、催话、取结果这六把都在卡上露面。
            Map.entry("agent_spawn", "派个助手去做"),
            Map.entry("agent_send", "给助手补一句"),
            Map.entry("agent_list", "看看有哪些助手"),
            Map.entry("task_list", "查后台任务进度"),
            Map.entry("task_output", "取回任务结果"),
            Map.entry("task_cancel", "停掉后台任务"),
            Map.entry("wait_async_results", "等后台结果"));

    /** 工具名 → 悬停一句话说明（这个工具到底在干什么；未收录的不给 title）。 */
    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry(PlanModeToolsNames.ENTER, "先只读地调研与设计方案，这期间不改任何东西"),
            Map.entry(PlanModeToolsNames.WRITE, "把想好的方案写成计划，你点开就能看"),
            Map.entry(PlanModeToolsNames.EXIT, "计划写完了，请你过目；你同意后它才开始动手"),
            Map.entry(FilesystemToolNames.READ_FILE, "读云端沙盒里的文件，不会碰你本机的文件"),
            Map.entry(FilesystemToolNames.WRITE_FILE, "在云端沙盒里新建或覆盖文件"),
            Map.entry(FilesystemToolNames.EDIT_FILE, "在云端沙盒里替换文件中的某段内容"),
            Map.entry(FilesystemToolNames.GREP_FILES, "按关键词在沙盒文件里搜，跨很多文件一起找"),
            Map.entry(FilesystemToolNames.GLOB_FILES, "按文件名规律找文件，比如「所有 .md」"),
            Map.entry(FilesystemToolNames.LIST_FILES, "列出云端沙盒里某个目录下有哪些文件"),
            Map.entry(FilesystemToolNames.EXECUTE, "在隔离的云端沙盒里跑一条命令，不影响你本机"),
            Map.entry("load_skill_through_path", "把某个技能的说明书读出来，它照着那份做"),
            Map.entry("skill_manage", "新建、修改或删除你名下的技能"),
            Map.entry("propose_skill", "先起草一份技能给你过目，同意后才会留下"),
            Map.entry("memory_search", "在沉淀下来的记忆里按关键词找，看看以前记过什么"),
            Map.entry("memory_get", "把某一份记忆的具体内容读出来"),
            Map.entry("memory_save", "把这件事写进你的长期记忆，以后还能想起来"),
            Map.entry("session_search", "在以往的历史会话里按关键词搜"),
            Map.entry("session_list", "列出历史会话都有哪些"),
            Map.entry("session_history", "把某一次历史会话的往来读出来"),
            Map.entry("deliver_artifact", "把云端沙盒里生成的文件存进会话，可查看下载"),
            Map.entry("agent_spawn", "让一个子助手单独去完成这件事，做完把结果交回来"),
            Map.entry("agent_send", "往已经在跑的助手那儿再递一句话"),
            Map.entry("agent_list", "看看这一轮派出去的助手都有哪些在跑"),
            Map.entry("task_list", "看看派出去的任务跑到哪一步了"),
            Map.entry("task_output", "把后台任务跑出来的结果读回来"),
            Map.entry("task_cancel", "让一个还没跑完的后台任务停下来"),
            Map.entry("wait_async_results", "派出去的活还没回来，在这儿等一会儿"));

    /** 框架文件工具与命令工具的名字（沙箱/文件系统改由框架提供后，问渠不再自定义这七把）。 */
    static final class FilesystemToolNames {
        static final String READ_FILE = "read_file";
        static final String WRITE_FILE = "write_file";
        static final String EDIT_FILE = "edit_file";
        static final String GREP_FILES = "grep_files";
        static final String GLOB_FILES = "glob_files";
        static final String LIST_FILES = "list_files";
        static final String EXECUTE = "execute";

        private FilesystemToolNames() {
        }
    }

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