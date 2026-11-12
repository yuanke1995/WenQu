package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.executor.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 本机浏览器工具：把 browser_* 调用经「本机执行器」下发到用户电脑上的浏览器。
 * <p>
 * 与云端 browser-mcp 的区别只在执行位置：云端容器看不到用户的 localhost/内网/登录态，
 * 而执行器跑在用户机器上，能。工具语义（参数、aria 快照）与云端一致——执行器内部托管一个
 * 本地 {@code @playwright/mcp} 做转发，见 scripts/local-agent/wenqu-agent.mjs 的 browser op。
 * <p>
 * 仅当该用户有在线执行器时挂载（{@code LocalBrowserToolProvider} 判定）；身份经
 * {@link ToolContext} 随调用传入（跨线程读不到 RequestUser），与 PresentArtifactTool 同口径。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class LocalBrowserTools {

    /** 单次浏览器调用等待上限：导航/渲染可能慢，给足；执行器侧另有 deadline 兜底 */
    private static final long CALL_TIMEOUT_MS = 120_000;

    private final ExecutorService executorService;
    private final ExecutorRegistry executorRegistry;

    public LocalBrowserTools(ExecutorService executorService, ExecutorRegistry executorRegistry) {
        this.executorService = executorService;
        this.executorRegistry = executorRegistry;
    }

    /** 该用户当前在线的执行器 id（没有= null，调用方据此不挂载/报错） */
    public String onlineExecutor(String uid) {
        if (uid == null || uid.isBlank()) return null;
        for (Map<String, Object> row : executorService.list(uid)) {
            String id = String.valueOf(row.get("id"));
            if (executorRegistry.online(id)) return id;
        }
        return null;
    }

    private String call(ToolContext ctx, String tool, Map<String, Object> args) {
        Object uidV = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_USER_ID);
        String uid = uidV == null ? null : String.valueOf(uidV);
        String executorId = onlineExecutor(uid);
        if (executorId == null) {
            return "本机执行器不在线：请在那台电脑上启动执行器（npm run dev 或 wenqu-agent）后再试。";
        }
        ExecutorRegistry.Result r = executorRegistry.call(executorId, "browser",
                Map.of("tool", tool, "args", args), CALL_TIMEOUT_MS);
        if (!r.ok()) return "本机浏览器执行失败[" + r.code() + "]：" + r.message();
        Object text = r.data() == null ? null : r.data().get("text");
        return text == null ? "" : String.valueOf(text);
    }

    @Tool(description = "在你电脑的浏览器里打开一个网址（可访问 localhost 与内网），返回页面标题与地址。")
    public String localBrowserNavigate(
            @ToolParam(description = "要打开的完整 URL，如 http://localhost:5800/ 或 https://example.com") String url,
            ToolContext ctx) {
        return call(ctx, "browser_navigate", Map.of("url", url));
    }

    @Tool(description = "打开网址并立即返回页面结构化快照（含元素 ref）。要看一个页面时优先用这一个工具，"
            + "不要试图点击浏览器地址栏或手动输入网址。")
    public String localBrowserOpen(
            @ToolParam(description = "要打开的完整 URL，如 http://localhost:5800/ 或 https://example.com") String url,
            ToolContext ctx) {
        String nav = call(ctx, "browser_navigate", Map.of("url", url));
        String snap = call(ctx, "browser_snapshot", Map.of());
        return nav + "\n\n" + snap;
    }

    @Tool(description = "读取你电脑浏览器当前页面的结构化快照（含元素 ref），用于决定下一步点击/填写。")
    public String localBrowserSnapshot(ToolContext ctx) {
        return call(ctx, "browser_snapshot", Map.of());
    }

    @Tool(description = "在你电脑浏览器当前页面点击一个元素。ref 取自 localBrowserSnapshot 快照里的 [ref=eN]。")
    public String localBrowserClick(
            @ToolParam(description = "元素的可读描述，如「登录按钮」") String element,
            @ToolParam(description = "快照里的元素引用，如 e12") String ref,
            ToolContext ctx) {
        return call(ctx, "browser_click", Map.of("element", element, "ref", ref));
    }

    @Tool(description = "在你电脑浏览器当前页面的输入框里填写文字。ref 取自快照。")
    public String localBrowserType(
            @ToolParam(description = "输入框的可读描述") String element,
            @ToolParam(description = "快照里的元素引用，如 e5") String ref,
            @ToolParam(description = "要填入的文字") String text,
            ToolContext ctx) {
        return call(ctx, "browser_type", Map.of("element", element, "ref", ref, "text", text));
    }

    @Tool(description = "对你电脑浏览器当前页面截图，返回截图说明（用于留证）。")
    public String localBrowserScreenshot(ToolContext ctx) {
        return call(ctx, "browser_take_screenshot", Map.of());
    }

    /**
     * 收掉本机浏览器窗口。
     * <p>headed 浏览器是用户桌面上真实存在的窗口：验完页面不收，它会一直挂在那里，
     * 用户以为「关不掉」（执行器原本只有 5 分钟空闲兜底）。目标轮收尾时由引擎顺手调一次。
     */
    @Tool(description = "关闭你电脑上的浏览器窗口。页面核对/截图做完、确认不需要它时调用。")
    public String localBrowserClose(ToolContext ctx) {
        return call(ctx, "browser_close", Map.of());
    }

    /** 尽力而为地关掉该用户的本机浏览器：没开、执行器离线、上游不认这一调用都当成功，不抛。 */
    public void closeQuietly(String uid) {
        if (uid == null || uid.isBlank()) return;
        String executorId = onlineExecutor(uid);
        if (executorId == null) return;
        try {
            executorRegistry.call(executorId, "browser",
                    Map.of("tool", "browser_close", "args", Map.of()), 15_000L);
        } catch (RuntimeException e) {
            log.debug("[LOCAL_BROWSER] 收尾关闭浏览器失败（忽略） uid={}: {}", uid, e.toString());
        }
    }
}
