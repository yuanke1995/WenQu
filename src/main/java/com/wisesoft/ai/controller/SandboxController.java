package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.ai.sandbox.SandboxFsBackend;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.SandboxService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙盒工作区浏览（右栏「沙盒」卡的数据源）：浏览/下载当前会话沙盒里的文件。
 * <p>
 * 归属与隔离：scope = (sessionId, uid)，sandboxId 由 (uid, sessionId) 派生——探别人的会话 id
 * 只会按**自己的 uid** 派生 sandboxId，天然找不到别人的容器，无需额外越权判定。
 * <p>
 * <b>无副作用原则</b>：浏览只 discover 不创建（{@code backendIfPresent}）——沙盒未运行（未用过/
 * 已空闲回收）时返回 available=false 的可读状态，绝不把容器悄悄拉起来；要起容器请回对话里用沙盒工具。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/sandbox")
@RequiredArgsConstructor
@Tag(name = "沙盒", description = "会话沙盒工作区浏览（state/tree/download，只读不创建容器）")
public class SandboxController {

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

    private Map<String, Object> availableFalse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", false);
        return data;
    }
}
