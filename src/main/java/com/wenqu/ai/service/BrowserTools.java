package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.TurnRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器工具统一入口：模型侧只有这一套 {@code browser_*} 工具，
 * 执行位置（本机 headed / 本机 headless / 平台云端）由 {@link BrowserModeResolver} 决定。
 *
 * <p>原来的 {@code localBrowser*} 族与平台 MCP 的 {@code browser_*} 工具在这里合流：
 * <ul>
 *   <li>本机两档 → 委托 {@link LocalBrowserTools}（执行器 browser op，headless 由执行器按配置起）；</li>
 *   <li>云端 → 委托 {@link McpClientService#invokeTool} 直调平台「浏览器操作」server，
 *       不把那 25 把外部工具挂进模型工具表。</li>
 * </ul>
 *
 * <p>每次产生页面快照后写一条 {@link BrowserSnapshotStore}，侧栏「浏览」tab 据此显示只读镜像；
 * 镜像不回灌模型，模型只从本工具返回值里读页面内容。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserTools {

    /** 平台浏览器 MCP 的登记名（BrowserCapabilitySeeder 播种，管理员可改中文名，但按 id 定位更稳——此处沿用名称兜底） */
    static final String PLATFORM_BROWSER_SERVER = "浏览器操作";

    private final BrowserModeResolver modeResolver;
    private final LocalBrowserTools local;
    private final McpClientService mcp;
    private final BrowserSnapshotStore snapshots;

    // ---------- 统一工具（@Tool 名 = 声明名，供 ToolRegistry 核对） ----------

    @Tool(description = "在当前浏览器打开网址（不返回快照；看页面优先用 browser_open）。")
    public String browserNavigate(
            @ToolParam(description = "完整 URL") String url,
            ToolContext ctx) {
        return dispatch(ctx, "browser_navigate", Map.of("url", url));
    }

    @Tool(description = "打开网址并返回页面结构化快照（含元素 ref）。看页面第一步用它，不要点地址栏。")
    public String browserOpen(
            @ToolParam(description = "完整 URL，如 https://example.com 或 http://localhost:8080") String url,
            ToolContext ctx) {
        String nav = dispatch(ctx, "browser_navigate", Map.of("url", url));
        String snap = dispatch(ctx, "browser_snapshot", Map.of());
        return nav + "\n\n" + snap;
    }

    @Tool(description = "在当前浏览器页面点击一个元素。ref 取自 browser_snapshot 快照里的 [ref=eN]。")
    public String browserClick(
            @ToolParam(description = "元素可读描述，如「登录按钮」") String element,
            @ToolParam(description = "快照里的元素引用，如 e12") String ref,
            ToolContext ctx) {
        return dispatch(ctx, "browser_click", Map.of("element", element, "ref", ref));
    }

    @Tool(description = "在当前浏览器页面的输入框填写文字。ref 取自 browser_snapshot 快照。")
    public String browserType(
            @ToolParam(description = "输入框可读描述") String element,
            @ToolParam(description = "快照里的元素引用，如 e5") String ref,
            @ToolParam(description = "要填入的文字") String text,
            ToolContext ctx) {
        return dispatch(ctx, "browser_type", Map.of("element", element, "ref", ref, "text", text));
    }

    @Tool(description = "读取当前浏览器页面的结构化快照（含元素 ref），用于决定下一步点击/填写。")
    public String browserSnapshot(ToolContext ctx) {
        return dispatch(ctx, "browser_snapshot", Map.of());
    }

    @Tool(description = "对当前浏览器页面截图留证，返回截图说明。")
    public String browserScreenshot(ToolContext ctx) {
        return dispatch(ctx, "browser_take_screenshot", Map.of());
    }

    @Tool(description = "关闭当前浏览器页面/窗口。页面核对完成、不再需要时调用。")
    public String browserClose(ToolContext ctx) {
        return dispatch(ctx, "browser_close", Map.of());
    }

    // ---------- 浏览器增强：观察 / 结构化抽取 / 多标签 ----------

    @Tool(description = "观察当前页面，返回可操作元素（按钮/链接/输入框等）与建议的下一步。"
            + "不清楚该点哪里时先 observe；可给 instruction 聚焦要看什么。底层用页面结构化快照。")
    public String browserObserve(
            @ToolParam(description = "想观察/聚焦什么，如「登录表单有哪些字段」；省略=整页", required = false) String instruction,
            ToolContext ctx) {
        // 云端与本机都只有 browser_snapshot 这把真工具；本机的 browser_observe 只是执行器侧的封装，
        // 云端直接落到 snapshot，避免「云端调了一个上游没有的工具名」。
        if (mode(ctx) == BrowserModeResolver.Mode.CLOUD) {
            return dispatch(ctx, "browser_snapshot", Map.of());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (instruction != null && !instruction.isBlank()) args.put("instruction", instruction.trim());
        return dispatch(ctx, "browser_observe", args);
    }

    @Tool(description = "按结构从当前页面抽取数据，返回结构化结果（JSON 文本）。"
            + "给 instruction 说明要抽什么；能给出 CSS selector 或取值函数(function)时最稳。"
            + "例：selector='.product' 抽每个商品；或 function 返回自定义对象。按自然语言 instruction 自动抽取需要本机执行器开启 stagehand 引擎。")
    public String browserExtract(
            @ToolParam(description = "要抽取什么，如「列出所有商品的名称和价格」") String instruction,
            @ToolParam(description = "CSS 选择器：抽取匹配元素的文本/链接/图片等（与 function 二选一）", required = false) String selector,
            @ToolParam(description = "在页面里执行的取值函数，形如 () => {...} 返回 JSON 可序列化值（与 selector 二选一）", required = false) String function,
            @ToolParam(description = "结构化输出的字段说明（JSON Schema 字符串，可选；stagehand 引擎用）", required = false) String schema,
            ToolContext ctx) {
        if (mode(ctx) == BrowserModeResolver.Mode.CLOUD) {
            // 云端只有 browser_evaluate：把 selector 折成取值函数；只有 instruction 时如实说明限制。
            String fn = function != null && !function.isBlank() ? function : null;
            if (fn == null && selector != null && !selector.isBlank()) fn = extractFn(selector.trim());
            if (fn == null) {
                return "云端浏览器按结构抽取需要 selector 或 function。"
                        + "按自然语言 instruction 自动抽取需要本机执行器 + stagehand 引擎。";
            }
            return dispatch(ctx, "browser_evaluate", Map.of("function", fn));
        }
        // 本机：交给执行器（stagehand 引擎或 browser_evaluate 兜底，见 wenqu-agent.mjs 的 extractOp）。
        Map<String, Object> args = new LinkedHashMap<>();
        if (instruction != null && !instruction.isBlank()) args.put("instruction", instruction.trim());
        if (selector != null && !selector.isBlank()) args.put("selector", selector.trim());
        if (function != null && !function.isBlank()) args.put("function", function);
        if (schema != null && !schema.isBlank()) args.put("schema", schema);
        return dispatch(ctx, "browser_extract", args);
    }

    @Tool(description = "管理浏览器多标签：action=list 列出所有标签、new 新建（可带 url）、select 切到第 index 个、close 关闭第 index 个（省略=当前）。"
            + "对比多个页面/在不同站点间来回时用它，而不是反复 navigate 覆盖当前页。")
    public String browserTabs(
            @ToolParam(description = "动作：list / new / select / close") String action,
            @ToolParam(description = "标签序号（select/close 用；从 0 开始，省略 close=当前）", required = false) Integer index,
            @ToolParam(description = "新建标签要打开的网址（new 用，可选）", required = false) String url,
            ToolContext ctx) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", action == null ? "" : action.trim());
        if (index != null) args.put("index", index);
        if (url != null && !url.isBlank()) args.put("url", url.trim());
        // browser_tabs 是 @playwright/mcp 的真工具，本机与云端同名，直接透传。
        return dispatch(ctx, "browser_tabs", args);
    }

    /** selector → 通用结构化取值函数（映射成 {text,href,src,value} 列表）。 */
    private static String extractFn(String selector) {
        return "() => Array.from(document.querySelectorAll(" + json(selector) + ")).map(el => ({"
                + " text: (el.innerText || el.textContent || '').trim(),"
                + " href: el.getAttribute('href') || null, src: el.getAttribute('src') || null,"
                + " value: ('value' in el ? el.value : null) }))";
    }

    private static String json(String s) {
        return com.alibaba.fastjson2.JSON.toJSONString(s);
    }

    /** 本轮浏览器执行位置（与 dispatch 同一 uid 口径），供 observe/extract 选真工具名。 */
    private BrowserModeResolver.Mode mode(ToolContext ctx) {
        String uid = ctx == null ? null : str(ctx.getContext().get(PresentArtifactTool.CTX_USER_ID));
        return modeResolver.resolve(uid);
    }

    // ---------- 路由 ----------

    /** 按本轮模式把一次裸工具调用分派到本机或云端，并记一条快照。 */
    private String dispatch(ToolContext ctx, String tool, Map<String, Object> args) {
        String uid = ctx == null ? null : str(ctx.getContext().get(PresentArtifactTool.CTX_USER_ID));
        String sid = ctx == null ? null : str(ctx.getContext().get(PresentArtifactTool.CTX_SESSION_ID));
        BrowserModeResolver.Mode mode = modeResolver.resolve(uid);
        String out;
        if (mode == BrowserModeResolver.Mode.CLOUD) {
            out = cloud(uid, tool, args);
        } else if (mode == BrowserModeResolver.Mode.LOCAL_HEADED
                || mode == BrowserModeResolver.Mode.LOCAL_HEADLESS) {
            out = local.call(ctx, tool, args, mode == BrowserModeResolver.Mode.LOCAL_HEADLESS);
        } else {
            out = "浏览器能力当前不可用：本机执行器不在线，且执行位置为「系统浏览器」。"
                    + "请在个人设置把执行位置改为「内置浏览器」，或在本机启动执行器。";
        }
        recordSnapshot(uid, sid, tool, args, out, modeLabel(mode));
        return out;
    }

    /** 云端直调平台 MCP（带审计），不把那 25 把外部工具挂进模型工具表。 */
    private String cloud(String uid, String tool, Map<String, Object> args) {
        try {
            return mcp.callBrowserTool(uid, tool, args);
        } catch (Exception e) {
            log.warn("[BROWSER] 云端调用失败 uid={} tool={}: {}", uid, tool, e.toString());
            return "云端浏览器调用失败：" + e.getMessage();
        }
    }

    private void recordSnapshot(String uid, String sid, String tool, Map<String, Object> args,
                                String output, String runtime) {
        if (sid == null || sid.isBlank()) return;
        // 离线失败不产生页面，不记快照
        if ("offline".equals(runtime)) return;
        // 只在读页面的工具后记快照：click/type 的结果也是快照文本，但避免一条交互产生两条重复镜像
        if (!"browser_navigate".equals(tool) && !"browser_snapshot".equals(tool)) return;
        String url = "browser_navigate".equals(tool) ? str(args.get("url")) : "";
        snapshots.record(uid, sid, url, titleOf(output), output, runtime);
    }

    private static String titleOf(String text) {
        if (text == null) return "";
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.toLowerCase().startsWith("# ")) return s.substring(2).trim();
            if (s.toLowerCase().startsWith("title:")) return s.substring(6).trim();
        }
        return "";
    }

    private static String modeLabel(BrowserModeResolver.Mode mode) {
        return mode == null ? "" : mode.name().toLowerCase().replace('_', '-');
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
