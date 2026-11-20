package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.executor.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面操作（Computer Use）工具：经用户本机执行器在 macOS 上列应用/窗口、打开应用、截图、点击、输入。
 *
 * <p>与 {@link LocalBrowserTools} 同一传输形态：按 uid 找在线执行器，发 {@code cua} op，
 * 真正动手的是执行器侧的 AppleScript / screencapture（见 {@code scripts/local-agent/wenqu-agent.mjs}）。
 * 因此权限（辅助功能、屏幕录制）由那台电脑的系统授予，服务端不接触桌面。
 *
 * <p>没有在线执行器时**明确回一句话**而不是静默失败；执行器不是 macOS 时也如实告知。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ComputerUseTools {

    /** 单次桌面动作等待上限（截图/打开大应用可能慢）。 */
    private static final long CALL_TIMEOUT_MS = 60_000;

    private final ExecutorService executorService;
    private final ExecutorRegistry executorRegistry;
    private final ArtifactService artifactService;

    public ComputerUseTools(ExecutorService executorService, ExecutorRegistry executorRegistry,
                            ArtifactService artifactService) {
        this.executorService = executorService;
        this.executorRegistry = executorRegistry;
        this.artifactService = artifactService;
    }

    /** 该用户当前在线的执行器 id（没有= null）。 */
    public String onlineExecutor(String uid) {
        if (uid == null || uid.isBlank()) return null;
        try {
            for (Map<String, Object> row : executorService.list(uid)) {
                String id = String.valueOf(row.get("id"));
                if (executorRegistry.online(id)) return id;
            }
        } catch (RuntimeException e) {
            log.debug("[CUA] 列执行器失败 uid={}: {}", uid, e.getMessage());
        }
        return null;
    }

    @Tool(name = "cuaListApps",
            description = "列出你电脑上当前正在运行、有界面的应用（名称 / bundleId / pid）。"
                    + "需要操作某个软件前先用它确认应用名。")
    public String cuaListApps(ToolContext ctx) {
        return call(ctx, "listApps", Map.of());
    }

    @Tool(name = "cuaOpenApp",
            description = "在你电脑上打开一个应用（按应用名或 bundle id）。例如打开「备忘录」「Safari」。")
    public String cuaOpenApp(
            @ToolParam(description = "应用名（如 Safari / 备忘录 / 微信）") String app,
            ToolContext ctx) {
        if (app == null || app.isBlank()) return "缺少应用名。";
        return call(ctx, "openApp", Map.of("app", app.trim()));
    }

    @Tool(name = "cuaListWindows",
            description = "列出某个应用当前打开的窗口（标题 / 位置 / 尺寸）。")
    public String cuaListWindows(
            @ToolParam(description = "应用名（如 Safari）") String app,
            ToolContext ctx) {
        if (app == null || app.isBlank()) return "缺少应用名。";
        return call(ctx, "listWindows", Map.of("app", app.trim()));
    }

    @Tool(name = "cuaScreenshot",
            description = "对当前屏幕（或指定应用的某个窗口）截图。返回截图文件的路径与尺寸，用于确认当前界面。")
    public String cuaScreenshot(
            @ToolParam(description = "只截某个应用的窗口（应用名）；省略=整屏", required = false) String app,
            @ToolParam(description = "窗口 id（cuaListWindows 返回）；省略=该应用最前面的窗口", required = false) Integer windowId,
            ToolContext ctx) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (app != null && !app.isBlank()) args.put("app", app.trim());
        if (windowId != null) args.put("windowId", windowId);
        return call(ctx, "screenshot", args);
    }

    @Tool(name = "cuaClick",
            description = "在屏幕坐标 (x,y) 上点击鼠标（可选先激活某个应用窗口）。坐标以屏幕左上角为原点，单位是点。"
                    + "不确定坐标时先 cuaScreenshot 看清楚。")
    public String cuaClick(
            @ToolParam(description = "横坐标 x") Integer x,
            @ToolParam(description = "纵坐标 y") Integer y,
            @ToolParam(description = "点击前先激活的应用名", required = false) String app,
            @ToolParam(description = "点击次数：1=单击（默认），2=双击", required = false) Integer clickCount,
            ToolContext ctx) {
        if (x == null || y == null) return "缺少点击坐标 (x,y)。";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("x", x);
        args.put("y", y);
        if (app != null && !app.isBlank()) args.put("app", app.trim());
        if (clickCount != null) args.put("clickCount", clickCount);
        return call(ctx, "click", args);
    }

    @Tool(name = "cuaType",
            description = "向当前焦点输入框键入一段文字（可选先激活某个应用）。只支持普通文本，不要用它输入超长内容。")
    public String cuaType(
            @ToolParam(description = "要输入的文字") String text,
            @ToolParam(description = "输入前先激活的应用名", required = false) String app,
            ToolContext ctx) {
        if (text == null || text.isEmpty()) return "缺少要输入的文字。";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("text", text);
        if (app != null && !app.isBlank()) args.put("app", app.trim());
        return call(ctx, "type", args);
    }

    @Tool(name = "cuaKey",
            description = "发送一个按键或组合键（如 enter / tab / esc / cmd+s / cmd+space）。"
                    + "key 用常见键名；modifiers 用 cmd/ctrl/alt/shift。")
    public String cuaKey(
            @ToolParam(description = "键名：enter/return/tab/esc/space/delete/↑/↓/←/→ 或单个字母") String key,
            @ToolParam(description = "修饰键，逗号分隔：cmd,ctrl,alt,shift", required = false) String modifiers,
            @ToolParam(description = "按键前先激活的应用名", required = false) String app,
            ToolContext ctx) {
        if (key == null || key.isBlank()) return "缺少按键。";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", key.trim());
        if (modifiers != null && !modifiers.isBlank()) args.put("modifiers", modifiers.trim());
        if (app != null && !app.isBlank()) args.put("app", app.trim());
        return call(ctx, "key", args);
    }

    // ==================== 传输 ====================

    private String call(ToolContext ctx, String action, Map<String, Object> args) {
        String uid = ctx == null ? null : str(ctx.getContext().get(PresentArtifactTool.CTX_USER_ID));
        String executorId = onlineExecutor(uid);
        if (executorId == null) {
            return "桌面操作不可用：本机执行器不在线。请在那台电脑上启动 wenqu-agent 后再试。";
        }
        try {
            ExecutorRegistry.Result r = executorRegistry.call(executorId, "cua",
                    Map.of("action", action, "args", args == null ? Map.of() : args), CALL_TIMEOUT_MS);
            if (!r.ok()) return "桌面操作失败[" + r.code() + "]：" + r.message();
            deliverScreenshot(ctx, action, r.data());
            Object text = r.data() == null ? null : r.data().get("text");
            if (text != null) return String.valueOf(text);
            return JSON.toJSONString(r.data() == null ? Map.of() : r.data());
        } catch (RuntimeException e) {
            log.warn("[CUA] 调用执行器失败 uid={} action={}: {}", uid, action, e.toString());
            return "桌面操作失败：" + e.getMessage();
        }
    }

    /** 截图若带回了 base64，就登记成产物并推卡片，让用户直接看到画面；失败不影响工具结果。 */
    private void deliverScreenshot(ToolContext ctx, String action, Map<String, Object> data) {
        if (!"screenshot".equals(action) || data == null) return;
        Object b64 = data.get("base64");
        String sid = ctx == null ? null : str(ctx.getContext().get(PresentArtifactTool.CTX_SESSION_ID));
        if (b64 == null || sid == null || sid.isBlank()) return;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(String.valueOf(b64));
            Map<String, Object> info = artifactService.writeBytes(sid,
                    "桌面截图-" + System.currentTimeMillis() + ".png", bytes, "桌面操作截图");
            artifactService.publish(sid, "artifact", JSON.toJSONString(info));
        } catch (Exception e) {
            log.debug("[CUA] 截图登记产物失败（忽略）: {}", e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
