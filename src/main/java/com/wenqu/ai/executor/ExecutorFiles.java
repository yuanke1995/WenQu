package com.wenqu.ai.executor;

import com.wenqu.ai.model.ProjectRoot;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区文件页/预览/导出用的执行器文件操作。
 *
 * <p>与 {@code ExecutorFilesystem} 的区别：那条路服务模型（框架工具），这条路服务<b>用户在页面上的文件管理</b>
 * （列表、上传、新建、移动、删除、下载、预览、导出）。两者走同一台执行器、同一套 roots 围栏，
 * 区别只在 {@code userInitiated}——页面上的动作是用户自己点的，执行器端不再逐次弹终端确认。
 *
 * <p>所有操作都带完整 roots 清单，执行器独立再校验一次路径；服务端不做「路径拼接后就信了」的事。
 */
@Service
public class ExecutorFiles {

    private static final long TIMEOUT_MS = 60_000;

    private final ExecutorRegistry registry;

    public ExecutorFiles(ExecutorRegistry registry) {
        this.registry = registry;
    }

    public boolean online(String executorId) {
        return executorId != null && !executorId.isBlank() && registry.online(executorId);
    }

    /**
     * 一次文件操作。路径错误转 {@link IllegalArgumentException}（控制器 400），
     * 执行器离线/超时转 {@link IllegalStateException}（控制器 503）。
     */
    public ExecutorRegistry.Result call(String executorId, List<ProjectRoot> roots, String op,
                                        Map<String, Object> args, boolean userInitiated) {
        if (!online(executorId)) {
            throw new IllegalStateException("本机执行器不在线：先在那台电脑上启动 wenqu-agent 再试");
        }
        Map<String, Object> payload = new LinkedHashMap<>(args);
        payload.put("roots", rootPayload(roots));
        payload.put("policy", "workspace-write");
        if (userInitiated) payload.put("userInitiated", true);
        ExecutorRegistry.Result r = registry.call(executorId, op, payload, TIMEOUT_MS);
        if (r.ok()) return r;
        String code = r.code() == null ? "E_IO" : r.code();
        String msg = r.message() == null ? "执行器返回失败" : r.message();
        if ("E_UNSUPPORTED".equals(code)) {
            throw new IllegalStateException("本机执行器版本过旧：请在那台电脑上重启 wenqu-agent（拉取最新脚本）后重试");
        }
        if ("E_PATH_OUTSIDE".equals(code) || "E_INVALID".equals(code) || "E_EXISTS".equals(code)
                || "E_NOT_FOUND".equals(code)) {
            throw new IllegalArgumentException(msg);
        }
        throw new IllegalStateException(code + "：" + msg);
    }

    public static List<Map<String, Object>> rootPayload(List<ProjectRoot> roots) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (roots != null) {
            for (ProjectRoot r : roots) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("alias", r.getAlias());
                one.put("path", r.getPath());
                one.put("primary", r.getPrimaryRoot() != null && r.getPrimaryRoot() == ProjectRoot.PRIMARY);
                out.add(one);
            }
        }
        return out;
    }

    public Map<String, Object> stat(String executorId, List<ProjectRoot> roots, String absPath) {
        return call(executorId, roots, "stat", Map.of("path", absPath), false).data();
    }

    /** 路径存在与否：只把「不存在」当 false；离线/越界照常抛出（不能把故障读成空目录）。 */
    public boolean exists(String executorId, List<ProjectRoot> roots, String absPath) {
        if (!online(executorId)) {
            throw new IllegalStateException("本机执行器不在线：先在那台电脑上启动 wenqu-agent 再试");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", absPath);
        payload.put("roots", rootPayload(roots));
        payload.put("policy", "workspace-write");
        ExecutorRegistry.Result r = registry.call(executorId, "stat", payload, TIMEOUT_MS);
        if (r.ok()) return true;
        if ("E_NOT_FOUND".equals(r.code())) return false;
        throw new IllegalStateException((r.code() == null ? "E_IO" : r.code()) + "："
                + (r.message() == null ? "执行器返回失败" : r.message()));
    }

    public List<Map<String, Object>> list(String executorId, List<ProjectRoot> roots, String absPath) {
        Object raw = call(executorId, roots, "list", Map.of("path", absPath), false).data().get("entries");
        return maps(raw);
    }

    public List<Map<String, Object>> tree(String executorId, List<ProjectRoot> roots, String absPath,
                                          int maxFiles, int maxDepth) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absPath);
        args.put("maxFiles", maxFiles);
        args.put("maxDepth", maxDepth);
        return maps(call(executorId, roots, "tree", args, false).data().get("entries"));
    }

    /** 读一段字节；length<=0 表示读到文件尾。返回 {@code {bytes, totalBytes}}。 */
    public ReadBytes readRange(String executorId, List<ProjectRoot> roots, String absPath, long offset, int length) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absPath);
        args.put("offset", offset);
        args.put("length", length);
        Map<String, Object> data = call(executorId, roots, "readBytes", args, false).data();
        String b64 = data.get("contentBase64") == null ? "" : String.valueOf(data.get("contentBase64"));
        long total = data.get("totalBytes") instanceof Number n ? n.longValue() : 0L;
        return new ReadBytes(Base64.getDecoder().decode(b64), total);
    }

    public record ReadBytes(byte[] bytes, long totalBytes) {
    }

    /** 读文本文件全文（截断到 maxBytes；<=0 表示不截断），UTF-8 解码。文件夹或读失败由调用方兜底。 */
    public String readText(String executorId, List<ProjectRoot> roots, String absPath, int maxBytes) {
        Map<String, Object> st = call(executorId, roots, "stat", Map.of("path", absPath), false).data();
        if (Boolean.TRUE.equals(st.get("isDir"))) throw new IllegalArgumentException("这是一个文件夹，不能按文本读取");
        long size = asLong(st.get("size"));
        int want = maxBytes <= 0 ? Integer.MAX_VALUE : (int) Math.min(size, (long) maxBytes);
        byte[] buf = readRange(executorId, roots, absPath, 0, want).bytes();
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** 整段写文本到执行器上的绝对路径（op "write"，不弹终端确认）。父目录不存在由执行器自动补建。 */
    public void writeText(String executorId, List<ProjectRoot> roots, String absPath, String text) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absPath);
        args.put("content", text == null ? "" : text);
        call(executorId, roots, "write", args, false);
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public void writeChunk(String executorId, List<ProjectRoot> roots, String absPath, long offset, byte[] bytes) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absPath);
        args.put("offset", offset);
        args.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
        call(executorId, roots, "writeChunk", args, true);
    }

    public void mkdir(String executorId, List<ProjectRoot> roots, String absPath) {
        call(executorId, roots, "mkdir", Map.of("path", absPath), true);
    }

    public void move(String executorId, List<ProjectRoot> roots, String fromAbs, String toAbs, boolean replace) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", fromAbs);
        args.put("to", toAbs);
        args.put("replace", replace);
        call(executorId, roots, "move", args, true);
    }

    public void remove(String executorId, List<ProjectRoot> roots, String absPath) {
        call(executorId, roots, "remove", Map.of("path", absPath), true);
    }

    /** 在用户那台电脑上用系统文件管理器打开一个源文件夹。 */
    public void openPath(String executorId, List<ProjectRoot> roots, String absPath) {
        call(executorId, roots, "openPath", Map.of("path", absPath), true);
    }

    // ==================== 每轮文件改动（turn changes） ====================
    //
    // 轮前/轮后快照与逐文件差异，全部由执行器在本机算（内容不出用户电脑）；这里只是三根管道。
    // turnStart/turnEnd 读操作不弹终端确认，也不参与页面写闸。

    /** 轮前快照：对工作区每个源文件夹定位 git 仓库并写一棵快照树（不在仓库里的源文件夹走捕获兜底）。 */
    public Map<String, Object> turnStart(String executorId, List<ProjectRoot> roots, String turnId) {
        return call(executorId, roots, "turnStart", Map.of("turnId", turnId), false).data();
    }

    /** 轮末对比：再拍一次快照并 diff，返回 {supported, files, total, added, deleted}。 */
    public Map<String, Object> turnEnd(String executorId, List<ProjectRoot> roots, String turnId) {
        return call(executorId, roots, "turnEnd", Map.of("turnId", turnId), false).data();
    }

    /** 取轮内某个文件的差异（执行器现算 hunk；快照数据过期回 E_NOT_FOUND → 400）。 */
    public Map<String, Object> turnDiff(String executorId, List<ProjectRoot> roots, String turnId, String path) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("turnId", turnId);
        args.put("path", path);
        return call(executorId, roots, "turnDiff", args, false).data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
