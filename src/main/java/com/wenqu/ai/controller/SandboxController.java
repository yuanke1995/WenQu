package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.SandboxFsBackend;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SandboxService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 沙盒工作区浏览 + 回答内代码执行（右栏「沙盒」卡的数据源 / 代码块「在沙盒中运行」按钮的后端）。
 * <p>
 * 归属与隔离：scope = (sessionId, uid)，sandboxId 由 (uid, sessionId) 派生——探别人的会话 id
 * 只会按**自己的 uid** 派生 sandboxId，天然找不到别人的容器，无需额外越权判定。
 * <p>
 * <b>无副作用原则</b>：state/tree/download 只 discover 不创建（{@code backendIfPresent}）——沙盒未运行
 * （未用过/已空闲回收）时返回 available=false 的可读状态，绝不把容器悄悄拉起来。要起容器请回对话里用沙盒工具。
 * <p>
 * <b>例外</b>：{@code /run} 是用户显式点了「在沙盒中运行」，创建容器正是它要的语义，故走
 * {@code backend(...)}（懒创建）；但仍受总开关与登录态约束。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/sandbox")
@RequiredArgsConstructor
@Tag(name = "沙盒", description = "会话沙盒工作区浏览（state/tree/download，只读不创建容器）与代码块沙盒执行（run）")
public class SandboxController {

    /** 回答内「在沙盒中运行」支持的语言 → 解释器与扩展名（与工作流 code 节点同口径，fail-loud 拒绝未知语言）。 */
    private static final Map<String, String[]> LANGS = Map.of(
            "python", new String[] { "python3", ".py" },
            "python3", new String[] { "python3", ".py" },
            "node", new String[] { "node", ".js" },
            "javascript", new String[] { "node", ".js" },
            "js", new String[] { "node", ".js" },
            "bash", new String[] { "bash", ".sh" },
            "sh", new String[] { "sh", ".sh" },
            "shell", new String[] { "sh", ".sh" });

    /** 单次执行超时上限（秒）：按钮触发的交互式执行不该久占沙盒。 */
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** 提交代码长度上限（字符）：超长直接拒，避免把沙盒当文件存储。 */
    private static final int MAX_CODE_CHARS = 100_000;

    private final SandboxService sandboxService;
    private final ConfigService configService;

    /** 与沙盒工具同一开关：总开关关闭时浏览端点 fail-closed 拒绝。 */
    private boolean enabled() {
        return configService.getBoolean("tool.sandbox.enabled");
    }

    @Operation(summary = "沙盒状态", description = "当前会话沙盒是否在运行（只探测不创建）+ 用户数据虚拟根路径")
    @GetMapping("/state")
    public ResultJson state(@RequestParam("sessionId") String sessionId) {
        if (!enabled()) {
            return ResultJson.error("沙盒未开启");
        }
        String uid = RequestUser.uid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", sandboxService.available(sessionId, uid));
        data.put("root", sandboxService.userDataRoot());
        return ResultJson.ok(data);
    }

    @Operation(summary = "列目录", description = "列出沙盒内某目录条目（path/isDir/size）；path 缺省为用户数据根")
    @GetMapping("/tree")
    public ResultJson tree(@RequestParam("sessionId") String sessionId, @RequestParam(value = "path", required = false) String path) {
        if (!enabled()) {
            return ResultJson.error("沙盒未开启");
        }
        String uid = RequestUser.uid();
        if (!sandboxService.available(sessionId, uid)) {
            return ResultJson.ok(availableFalse());
        }
        try {
            ProvisionerSandboxBackend backend = sandboxService.backendIfPresent(sessionId, uid);
            String dir = path == null || path.isBlank() ? sandboxService.userDataRoot() : path;
            SandboxFsBackend.LsResult r = backend.ls(dir);
            if (r.hasError()) {
                return ResultJson.error("列举失败：" + r.error);
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            if (r.entries != null) {
                for (SandboxFsBackend.FsEntry e : r.entries) {
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("path", e.path);
                    it.put("isDir", e.isDir);
                    it.put("size", e.size);
                    entries.add(it);
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("available", true);
            data.put("path", dir);
            data.put("entries", entries);
            return ResultJson.ok(data);
        } catch (Exception e) {
            // 探测与列举之间的窗口期容器被回收/出错：按不可用回显，不把异常栈抛给前端
            log.debug("[SANDBOX] 浏览目录失败 (session={}): {}", sessionId, e.getMessage());
            return ResultJson.ok(availableFalse());
        }
    }

    @Operation(summary = "下载文件", description = "下载沙盒内指定文件（字节流；目录/不存在返回错误）")
    @GetMapping("/download")
    public Object download(@RequestParam("sessionId") String sessionId, @RequestParam("path") String path,
                           jakarta.servlet.http.HttpServletResponse response) {
        if (!enabled()) {
            return ResultJson.error("沙盒未开启");
        }
        String uid = RequestUser.uid();
        try {
            ProvisionerSandboxBackend backend = sandboxService.backendIfPresent(sessionId, uid);
            List<SandboxFsBackend.DownloadResult> results = backend.downloadFiles(List.of(path));
            SandboxFsBackend.DownloadResult r = results == null || results.isEmpty() ? null : results.get(0);
            if (r == null || r.hasError() || r.content == null) {
                return ResultJson.error("下载失败：" + (r == null ? "文件不存在" : r.error));
            }
            String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
            response.setContentLength(r.content.length);
            response.getOutputStream().write(r.content);
            response.getOutputStream().flush();
            return null;
        } catch (Exception e) {
            log.debug("[SANDBOX] 下载失败 (session={}, path={}): {}", sessionId, path, e.getMessage());
            return ResultJson.error("下载失败：" + (e.getMessage() == null ? "沙盒不可用" : e.getMessage()));
        }
    }

    /**
     * 执行一段代码（回答内代码块「在沙盒中运行」按钮的后端）。
     * <p>
     * 与 {@code execute} 工具同一条物理通道（同一个 backend、同一套隔离与回收），区别只在调用方：
     * 这里是用户手点按钮、走登录态 HTTP 请求，身份直接取 {@link RequestUser#uid()}——不像工具回调那样
     * 跑在响应式 I/O 线程上读不到 ThreadLocal。
     * <p>
     * 落盘用 {@code putFile}（覆盖语义、不受 user-data 可写根守卫）而不是 {@code write}：
     * 脚本是引擎内部临时文件，落 /tmp，与工作流 code 节点同一理由——write() 会把 /tmp 拒在客户端，
     * 失败返回值若被忽略就变成执行期 {@code can't open file}（exit=2）。
     * <p>
     * 语言白名单 + 超时上限：交互式按钮触发的执行不该成为「跑十分钟的任务」通道。
     */
    @Operation(summary = "运行代码", description = "在当前会话沙盒里执行一段代码（python/node/bash），返回输出与退出码")
    @PostMapping("/run")
    public ResultJson run(@RequestBody(required = false) Map<String, Object> body) {
        if (!enabled()) {
            return ResultJson.error("沙盒未开启，请先在设置页开启「沙盒工具」");
        }
        String sessionId = str(body, "sessionId");
        String language = str(body, "language");
        String code = str(body, "code");
        String uid = RequestUser.uid();
        if (sessionId.isBlank()) {
            return ResultJson.error("缺少会话上下文，无法在沙盒中运行");
        }
        if (code.isBlank()) {
            return ResultJson.error("代码为空");
        }
        if (code.length() > MAX_CODE_CHARS) {
            return ResultJson.error("代码过长（超过 " + MAX_CODE_CHARS + " 字符）");
        }
        String[] lang = LANGS.get(language.toLowerCase(Locale.ROOT));
        if (lang == null) {
            return ResultJson.error("暂不支持在沙盒中运行 " + language + "（支持 python / node / bash）");
        }
        int timeout = intOf(body, "timeout", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
        String file = "/tmp/wq_run_" + System.currentTimeMillis() + lang[1];
        long began = System.currentTimeMillis();
        try {
            ProvisionerSandboxBackend backend = sandboxService.backend(sessionId, uid);
            backend.putFile(file, code);
            ProvisionerSandboxBackend.ExecuteResponse resp = backend.execute(lang[0] + " " + file, timeout);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exitCode", resp.exitCode());
            data.put("output", resp.output() == null ? "" : resp.output());
            data.put("truncated", resp.truncated());
            data.put("elapsedMs", System.currentTimeMillis() - began);
            return ResultJson.ok(data);
        } catch (Exception e) {
            // 沙盒不可达/拉起失败：如实回显根因消息，前端显示在输出框里（不吞错、不假装成功）
            log.warn("[SANDBOX] 运行失败 (session={}, lang={}): {}", sessionId, language, e.getMessage());
            return ResultJson.error("沙盒运行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null) return "";
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private int intOf(Map<String, Object> body, String key, int def, int min, int max) {
        if (body == null || body.get(key) == null) return def;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(body.get(key)))));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Map<String, Object> availableFalse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", false);
        return data;
    }
}
