package com.wenqu.ai.service;

import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.SandboxFsBackend;
import com.wenqu.ai.sandbox.SandboxPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 沙盒工具（Function Calling）：让模型在隔离容器里真正执行命令、读写文件。
 * <p>
 * 与问渠新栈的差别要说清楚：新栈把沙盒接在 deepagents 的「文件系统中间件」上（{@code FilesystemMiddleware}
 * 提供 read_file/write_file/edit_file/ls/glob/grep/execute 一族工具，并配合大结果裁剪），
 * 而本工程没有该中间件，故这里**按本工程的 {@code @Tool} 机制逐个显式注册**，
 * 接当前用得上的六个：{@code execute} / {@code read_file} / {@code write_file} / {@code edit_file} /
 * {@code ls} / {@code deliver_artifact}（沙盒文件 → 我的产物）。工具名与新栈保持一致
 * （{@code deliver_artifact} 为本工程新增），便于将来两边共用提示词与技能文档。
 * <p>
 * 归属与 scope 的来源：会话 id 与用户 uid 都走 {@code ToolContext}（与产物交付工具同一约定），
 * 由 ChatService 发起流式请求时注入。注意：工具回调跑在 Spring AI 响应式 I/O 线程上，
 * 而 {@code RequestUser} 是流水线线程装载的 ThreadLocal，跨线程读不到（回落 anonymous），
 * 所以这里必须读 toolContext 透传的 uid，而不是 RequestUser.uid()——否则沙盒会被建到
 * {@code shared/anonymous/workspace}（已踩坑）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class SandboxTools {

    /** 单次命令输出超过该长度时，返回给模型的内容会被截断（避免把上下文撑爆）。 */
    private static final int TOOL_OUTPUT_CHARS = 20_000;

    /** toolContext 里「工具输出增量回调」的键（Consumer&lt;String&gt;，ChatService 注入 → SSE tool_output 实时下发）。 */
    public static final String CTX_OUTPUT_SINK = "wq_tool_output_sink";
    /**
     * toolContext 里「基础设施故障上报」的键（Consumer&lt;String&gt;，ChatService 注入）。
     * <p>沙盒不可用（provisioner 没起 / 连不上 / 鉴权被拒）时，工具**不抛异常**——抛出去会掐断
     * 整轮流、让已生成的正文与全部工具记录一起落空。工具照常把失败文本返回给模型让它自行调整，
     * 同时经此回调把「这次失败是基础设施层、不是命令本身跑失败」告诉 ChatService，
     * 让工具记录落成 status=error（红色失败）而不是 status=done（绿色对勾）。
     * <p>不这么做的后果：命令退出码非 0（业务失败）与沙盒压根没通（基础设施故障）都是
     * "exitCode=1 +一段文本"，记录一律显示成功，排查时完全看不出沙盒没通。
     */
    public static final String CTX_INFRA_ERROR_SINK = "wq_tool_infra_error_sink";

    /**
     * toolContext 里「本轮权限档位」的键（{@link com.wenqu.ai.agent.permission.ExecutionPolicy}，
     * 由问答侧经沙盒族的上下文注入器带进来）。
     * <p>档位是<b>这一轮</b>的属性（同一会话两轮可以不同档），所以只能走上下文，不能让工具去读配置：
     * 读配置拿到的是平台默认档，智能体选了只读也会被绕过。
     * <p>缺失按拒绝处理（见 {@link #writeDenial}）——受限策略下「判不出就放行」就是静默透传。
     */
    public static final String CTX_SANDBOX_POLICY = "wq_sandbox_policy";

    /**
     * 输出被截断时把<b>整份原文</b>外溢留存的能力：(工具名, 原文) → spillId，由问答侧经 ToolContext 注入。
     * <p>走上下文而不是全局单例：留存要按「哪一轮的哪个工具」归属，工具执行在弹性线程上并发跑，
     * setter 式单例会串号（与 P1 要修掉的那对 setSourceRegistrar/setSink 同病灶）。
     */
    public static final String CTX_SPILL = "wq_tool_spill";
    /** 留存成功后的上报（工具名, spillId）：问答侧据此把「查看完整输出」挂到对应那张工具卡上 */
    public static final String CTX_SPILL_REPORT = "wq_tool_spill_report";
    /**
     * toolContext 里「本轮子调用回程」的键（{@link com.wenqu.ai.agent.runcode.RunCodeBridge}，P3b）。
     * <p>{@code run_code} 生成的 Python 客户端要把脚本里的每次工具调用打回平台，而"打回哪儿、能调哪些、
     * 这轮还剩几次额度"全是本轮的事——所以经上下文注入，与沙盒档位、spill 信箱同一条路子。
     * 缺这个键时 run_code 直接拒（不是"那就不带工具、纯算"）：模型看到的能力与实际给的不能两样。
     */
    public static final String CTX_RUN_CODE_BRIDGE = "wq_runcode_bridge";
    /** 支持流式输出的工具名（ChatService 按 name 判定是否注入输出回调）。 */
    public static final String STREAMING_TOOL_NAME = "execute";
    /** 脚本型工具名（P3b）：ChatService 判定是否注入子调用回程用 */
    public static final String RUN_CODE_TOOL_NAME = "run_code";
    /**
     * 本组件注册的全部工具名（ChatService 判定「是否沙盒工具」用，决定要不要注入
     * CTX_INFRA_ERROR_SINK）。刻意列全而非用前缀匹配：工具名是 @Tool 显式声明的字符串，
     * 改名时编译期就会暴露这里的漏项风险（新增工具忘记加入 → 基础设施故障仍显示为成功），
     * 而前缀匹配会在重构时静默失配。
     */
    public static final java.util.Set<String> TOOL_NAMES = java.util.Set.of(
            "execute", "read_file", "write_file", "edit_file", "ls", RUN_CODE_TOOL_NAME);

    private final SandboxService sandboxService;
    private final ArtifactService artifactService;
    private final com.wenqu.ai.agent.runcode.RunCodeService runCodeService;

    public SandboxTools(SandboxService sandboxService, ArtifactService artifactService,
                        com.wenqu.ai.agent.runcode.RunCodeService runCodeService) {
        this.sandboxService = sandboxService;
        this.artifactService = artifactService;
        this.runCodeService = runCodeService;
    }
    /**
     * execute / read_file / write_file / edit_file / ls 五把已退役（全框架化 P1）。
     *
     * <p>改由框架 {@code FilesystemTool}（read/write/edit/grep/glob/list）与 {@code ShellExecuteTool}
     * 提供，模型侧工具名与契约不变；沙箱经 {@code SandboxService#frameworkFilesystem} 产出的
     * {@code ProvisionerSandboxFilesystem} 接入 builder 的 {@code .abstractFilesystem(...)}。
     *
     * <p><b>必须真删而不是留着</b>：{@code ToolRegistry} 启动期核对「声明的 descriptor」与
     * 「holder 上反射出的 {@code @Tool} 方法」，两边对不上就打 ERROR（真机验证确实报了）。
     * 留着还会在同一 toolkit 上挂出与框架重名的工具，模型只能二选一。
     *
     * <p>权限档位裁剪改由 {@code AgentxFrameworkToolGate} 在执行前逐个判
     * （只读档下裁掉写类与 {@code execute}）。
     *

    /**
     * 沙盒文件 → 我的产物已改由框架 {@code deliver_artifact} 承担（全框架化 P1-c）。
     *
     * <p>框架侧：{@code ArtifactDeliveryTarget} SPI 的实现见
     * {@code com.wenqu.ai.sandbox.WenquArtifactDeliveryTarget}，注册点为
     * {@code HarnessAgent.builder().artifactDeliveryTarget(...)}。框架工具负责从沙盒下载文件字节，
     * 本类原先那套「路径判据 → downloadFiles → writeBytes → publish」已整体搬进该 SPI 实现。
     *
     * <p><b>因此本工具已删除</b>：留着会与框架的同名工具并存，模型面对重名工具会二选一。
     * 权限档位的路径收束由 {@code AgentxFrameworkToolGate} 统一承担（deliver_artifact 按写类处理）。
     */

    /**
     * 沙盒里跑一段脚本（P3b）。工具本身只做"该不该给"的判定与落点解析，运行编排在
     * {@link com.wenqu.ai.agent.runcode.RunCodeService}：生成 Python 客户端、发一次性运行凭据、
     * 执行、回收。
     * <p>写盘位置走与 write/edit 同一条档位判据（脚本与客户端要落进沙盒目录），所以只读档压根
     * 挂不上这个工具（{@code SideEffect.MUTATE} 被档位裁掉），全自主档也不给它越过工作区的能力。
     */
    @Tool(name = RUN_CODE_TOOL_NAME, description = "在沙盒里跑一段脚本，用来把<b>多件同类的活一次做完</b>："
            + "批量读取/转换文件、逐份汇总很多资料、复杂计算与生成文件。"
            + "language 选 python（默认）或 javascript（Node）。"
            + "脚本里可以直接用本轮已挂上的工具：Python 写 `import wenqu_tools as wq`，"
            + "JavaScript 写 `const wq = require('./wenqu_tools')`；"
            + "模块由平台按本轮工具表自动生成，本轮没挂的工具里面就没有那个函数，"
            + "调用照常受平台的权限与预算约束。"
            + "硬性约束：只有标准输出（print / console.log）会回到对话里，函数返回值不会自动出现；"
            + "请只打印结论与关键数字，不要把整份资料打出来（那和直接调工具没区别）。"
            + "单次性的取数直接用对应工具即可，不必绕到脚本；脚本的优势是循环与扇出。"
            + "扇出时注意：脚本模块里能并发调用的是问渠提供的那几把（检索、联网这类）；"
            + "沙盒的文件读写、列目录与产物交付不在这个模块里——那几把由平台直接提供，请在脚本之外调用。"
            + "<b>execute（在脚本里再跑命令行）不提供</b>——实测四条并发嵌套命令只有一部分能回到脚本，"
            + "剩下的连接等不到返回，整段脚本停在上面；要跑命令行请直接调 execute 工具。"
            + "文件写在 /home/gem/user-data 下并保留，要交给用户仍需用 deliver_artifact。")
    public String run_code(
            @ToolParam(description = "脚本正文（Python 3 或 JavaScript/Node）。结果请打印到标准输出") String script,
            @ToolParam(description = "语言：python（默认）或 javascript", required = false) String language,
            ToolContext toolContext) {
        com.wenqu.ai.agent.runcode.RunCodeBridge bridge = ctxFn(toolContext, CTX_RUN_CODE_BRIDGE);
        if (bridge == null) {
            return reportInfra(toolContext, "run_code 未执行：本轮没有可用的子调用回程"
                    + "（平台未配置沙盒回调地址，或本轮没有可交给脚本的工具）。"
                    + "请改用已有的工具逐项完成。");
        }
        com.wenqu.ai.agent.runcode.ToolStubRenderer.Lang lang =
                com.wenqu.ai.agent.runcode.ToolStubRenderer.Lang.of(language);
        // 与 write/edit 同一条档位判据：脚本与客户端要写进沙盒目录
        String denial = writeDenial(toolContext, runCodeService.workDir() + "/" + lang.scriptFile());
        if (denial != null) {
            return reportFsError(toolContext, denial);
        }
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        com.wenqu.ai.agent.runcode.RunCodeService.Outcome outcome =
                runCodeService.run(bridge, backend, script, lang, outputSink(toolContext));
        String out = outcome.text();
        // 运行结果的结构那份交给卡片（退出码/超时/沙盒故障），不再靠正文首句反推是哪种失败
        bridge.recordRunOutcome(outcome);
        if (outcome.infra()) {
            // 运行失败的原因在文本里已经说清了（给模型看），但基础设施故障必须另外报一次，
            // 否则这条工具记录会显示成绿色对勾——排查的人看不出是容器没起来还是脚本写错了
            reportInfra(toolContext, out);
        }
        return clip(out == null ? "" : out, RUN_CODE_TOOL_NAME, toolContext);
    }


    /** 从 toolContext 取会话 id（与 {@link #backend(ToolContext)} 同键，供产物归属使用）。 */
    private String sessionId(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
        return v == null ? null : String.valueOf(v);
    }

    /** 取输出增量回调（ChatService 按工具名注入；缺省 null = 无流式上下文，走同步执行）。 */
    @SuppressWarnings("unchecked")
    private java.util.function.Consumer<String> outputSink(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(CTX_OUTPUT_SINK);
        return v instanceof java.util.function.Consumer ? (java.util.function.Consumer<String>) v : null;
    }

    /**
     * 上报失败并原样返回文案给模型（沙盒不可用 / 执行失败共用此出口）。
     * <p>失败文本必须照常回给模型——它据此调整下一步（改命令、改路径，或如实告知用户）。
     * 但必须同时上报，否则这条记录会显示成绿色对勾的「成功」：排查时完全看不出沙盒压根没通。
     */
    private String reportInfra(ToolContext toolContext, String message) {
        java.util.function.Consumer<String> sink = infraErrorSink(toolContext);
        if (sink != null) {
            try {
                sink.accept(message);
            } catch (Exception e) {
                log.debug("[SANDBOX] 工具失败上报失败（不影响工具返回）: {}", e.getMessage());
            }
        }
        return message;
    }

    /**
     * 文件类工具（读/写/编辑/列举/交付）的失败上报：与 {@link #reportInfra} 同机制。
     * <p>这些失败未必都是基础设施问题（路径非法、文件已存在同样算失败），此处统一按失败记——
     * 「文件没写进去」在工具记录里就该显示为失败，而不是一条看起来成功的普通输出。
     */
    private String reportFsError(ToolContext toolContext, String message) {
        return reportInfra(toolContext, message);
    }

    /** 取基础设施故障上报回调（ChatService 注入；缺省 null = 无问答流上下文，只返回文本不落状态）。 */
    @SuppressWarnings("unchecked")
    private java.util.function.Consumer<String> infraErrorSink(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(CTX_INFRA_ERROR_SINK);
        return v instanceof java.util.function.Consumer ? (java.util.function.Consumer<String>) v : null;
    }

    // ==================== 内部 ====================

    /**
     * 写入前的权限档位判据（P2）：返回 null 表示放行，否则返回给模型看的拒绝理由。
     * <p>三种拒绝的措辞刻意不同，因为模型该做的下一步不同：只读档要它<b>别再试写</b>（换工具也没用，
     * 换路径更没用）；越界要它<b>换个路径重试</b>（把允许区写清楚）；缺档位要说这是平台判定缺失，
     * 不是用户的限制，免得模型对着用户道歉或反复重试。
     * <p>缺档位按拒绝处理（fail-closed）：受限策略下「判不出就放行」就是静默透传，那是权限系统
     * 唯一不可接受的失效形态——用户以为选了只读，文件却写成了。
     */
    private String writeDenial(ToolContext toolContext, String path) {
        com.wenqu.ai.agent.permission.ExecutionPolicy policy = sandboxPolicy(toolContext);
        if (policy == null) {
            log.warn("[FAIL-LOUD] 沙盒写入缺少本轮权限档位，按拒绝处理（装配侧漏注入 CTX_SANDBOX_POLICY？）");
            return "沙盒写入未执行：本轮缺少权限档位判定，不要重试写入；请把要写的内容告知用户。";
        }
        String normalized;
        try {
            normalized = SandboxPaths.normalizeAbsolutePath(path);
        } catch (IllegalArgumentException e) {
            return "路径不合法：" + e.getMessage() + "（需要沙盒内的绝对路径）";
        }
        if (policy.permitsWritePath(normalized)) return null;
        if (!policy.sandboxMode().writable()) {
            return "沙盒写入未执行：本轮权限档位为「" + policy.preset().label() + "」（"
                    + policy.sandboxMode().label() + "），这一档不允许在沙盒里创建或修改文件。"
                    + "请基于沙盒里已有的文件继续，或把内容直接写在回答里，不要重复尝试写入。";
        }
        String root = policy.writableRoot() == null || policy.writableRoot().isBlank()
                ? "(平台未配置 sandbox.virtualPathPrefix，本轮不放行任何写入)" : policy.writableRoot();
        return "沙盒写入未执行：本轮档位「" + policy.preset().label() + "」只允许写在沙盒目录 " + root
                + " 之内，当前路径越界。请把路径改到该目录下再试。";
    }

    /** 取本轮权限档位（问答侧经沙盒族上下文注入）；不在上下文里返回 null，由 {@link #writeDenial} 按拒绝处理 */
    private com.wenqu.ai.agent.permission.ExecutionPolicy sandboxPolicy(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        Object v = toolContext.getContext().get(CTX_SANDBOX_POLICY);
        return v instanceof com.wenqu.ai.agent.permission.ExecutionPolicy p ? p : null;
    }

    /** 沙盒不可用时的统一文案：把「没配好」与「没会话上下文」分开说，否则报错无从下手。 */
    private static final String NO_CONTEXT = "沙盒不可用：缺少会话或用户上下文，请刷新页面后重试";

    private ProvisionerSandboxBackend backend(ToolContext toolContext) {
        String sessionId = null;
        String uid = null;
        if (toolContext != null) {
            Object s = toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
            if (s != null) sessionId = String.valueOf(s);
            // 用户归属必须走 toolContext 透传（CTX_USER_ID），不能读 RequestUser.uid()：
            // 工具回调执行在 Spring AI 响应式 I/O 线程，RequestUser 的 ThreadLocal 在该线程上未装载、回落 anonymous。
            Object u = toolContext.getContext().get(PresentArtifactTool.CTX_USER_ID);
            if (u != null) uid = String.valueOf(u);
        }
        if (uid == null || uid.isBlank()) {
            // 跨线程身份透传缺失（理论上 ChatService 必然注入 CTX_USER_ID）——显式报警，绝不静默回落 anonymous 建沙盒。
            log.warn("[FAIL-LOUD] 沙盒工具缺少 toolContext 用户归属，拒绝建沙盒（session={}）", sessionId);
            return null;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sandboxService.backend(sessionId, uid, writesUnrestricted(toolContext));
    }

    /**
     * 本轮档位是否放开沙盒内的写入位置（P2 的 danger-full-access）。
     * <p>沙盒的文件写入边界本来就落在 {@code ProvisionerSandboxBackend.writableRoots()} 那一层，
     * 档位不传到这里就只是界面上的一个标签——只读档靠不挂工具收紧，全自主档靠这个参数放开边界。
     */
    private boolean writesUnrestricted(ToolContext toolContext) {
        com.wenqu.ai.agent.permission.ExecutionPolicy policy = sandboxPolicy(toolContext);
        return policy != null && policy.sandboxMode() == com.wenqu.ai.agent.permission.SandboxMode.DANGER_FULL_ACCESS;
    }

    /**
     * 兜底截断（进模型的那一份）：按字符限死，读文件/列目录这类没走沙盒字节帽的也归这里收口。
     * <p>被截掉那一段不再静默丢掉：问答侧注入了留存能力时，把<b>整份原文</b>交出去落盘并上报 spillId，
     * 用户拿到的是「预览 + 可查看完整输出」；没有留存能力（非问答链路、或写盘失败）就写明丢了多少钱字，
     * 而不是留一句「已截断」让人以为那就是全部。
     */
    private static String clip(String text, String toolName, ToolContext toolContext) {
        return clip(text, null, toolName, toolContext);
    }

    /**
     * 同上，但<b>留存的那一份</b>与<b>进模型的那一份</b>分开给：{@code storeText} 是砍之前的整份原文
     * （沙盒命令输出的全文只能这么带出来——{@code text} 已经是按帽挑过的一段）。
     * <p>刻意不改 {@code text} 的形态：模型看到的内容形状一变，token 预算、历史压缩口径、评估基线会跟着漂移，
     * 而这条能力要修的是「给人看的那一份残缺」。
     */
    private static String clip(String text, String storeText, String toolName, ToolContext toolContext) {
        if (text == null) return "";
        String store = storeText != null && storeText.length() > text.length() ? storeText : text;
        if (store.length() <= TOOL_OUTPUT_CHARS) return text;
        java.util.function.BiFunction<String, String, String> spill = ctxFn(toolContext, CTX_SPILL);
        String spillId = spill == null ? null : safeSpill(spill, toolName, store);
        String head = text.length() <= TOOL_OUTPUT_CHARS ? text : text.substring(0, TOOL_OUTPUT_CHARS);
        if (spillId != null) {
            java.util.function.BiConsumer<String, String> report = ctxFn(toolContext, CTX_SPILL_REPORT);
            if (report != null) {
                try {
                    report.accept(toolName, spillId);
                } catch (Exception ignored) {
                    // 上报失败不影响回给模型的文本（卡片少一个续读入口，原文仍在盘上）
                }
            }
            return head + "\n…（输出过长，只展示前 " + TOOL_OUTPUT_CHARS + " 字；完整 " + store.length()
                    + " 字已留存，可从本轮工具卡片「查看完整输出」读回）";
        }
        return head + "\n…（内容过长已截断，另有 " + (store.length() - TOOL_OUTPUT_CHARS) + " 字未能留存）";
    }

    /** 取上下文里注入的能力（问答侧经 ToolContext 传的函数）；不在上下文里就返回 null 走降级路径 */
    @SuppressWarnings("unchecked")
    private static <T> T ctxFn(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        Object v = toolContext.getContext().get(key);
        return v == null ? null : (T) v;
    }

    /** 留存是旁路：它抛任何异常都不该让这次工具调用跟着失败 */
    private static String safeSpill(java.util.function.BiFunction<String, String, String> spill,
                                    String toolName, String text) {
        try {
            return spill.apply(toolName, text);
        } catch (Exception e) {
            return null;
        }
    }
}
