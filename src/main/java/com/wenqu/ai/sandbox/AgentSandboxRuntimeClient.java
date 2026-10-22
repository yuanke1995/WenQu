package com.wenqu.ai.sandbox;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 沙盒 runtime 的 HTTP 实现（对应参考实现 {@code agent_sandbox} SDK 0.0.30 的
 * {@code Sandbox} 对象暴露的 {@code file.*} / {@code shell.*} 端点表面）。
 *
 * <p>逐字对齐 SDK 的 wire 契约（自 Fern 生成代码提取，端点与字段名一字不改）：
 * <ul>
 *   <li>{@code POST v1/file/read} → {@code data.content} / {@code data.encoding}</li>
 *   <li>{@code POST v1/file/write} → {@code data.file} / {@code data.bytes_written}</li>
 *   <li>{@code POST v1/file/str_replace_editor} → {@code StrReplaceEditorResult}</li>
 *   <li>{@code POST v1/file/list} → {@code data.files[].{path,is_directory,size,modified_time}}</li>
 *   <li>{@code POST v1/file/find} → {@code data.files[]}（字符串路径列表）</li>
 *   <li>{@code GET  v1/file/download?path=} → FileResponse 字节流</li>
 *   <li>{@code POST v1/file/upload} → multipart（{@code file} 文件段 + {@code path} 表单段）</li>
 *   <li>{@code POST v1/shell/exec} → {@code ShellCommandResult.output} / {@code exit_code}</li>
 * </ul>
 *
 * <p>统一响应包装为 {@code {success, message, data, hint}}；枚举取值与 SDK 一致：
 * {@code command} ∈ {view, create, str_replace, insert, undo_edit}、
 * {@code replace_mode} ∈ {ALL, FIRST, LAST}、{@code encoding} ∈ {utf-8, base64, raw}。
 *
 * <h3>必要替换</h3>
 * <ul>
 *   <li>Python {@code agent_sandbox} SDK（httpx 生成的客户端）→ JDK {@link HttpClient} +
 *       fastjson2 手工编解码。SDK 不在本工程 Java 依赖中，故按 wire 契约重建。</li>
 *   <li>{@code ApiError} → {@link SandboxProvisionerException}，且<b>消息格式逐字保持 SDK 的
 *       {@code __str__}</b>（{@code "headers: ..., status_code: N, body: ..."}）：上层
 *       {@code ProvisionerSandboxBackend#isMissingFileError} 依赖其中的 {@code "status_code: 404"}
 *       子串判定「文件不存在」，改格式会静默破坏该分支。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ul>
 *   <li>SDK 的浏览器 / Jupyter / NodeJS / MCP / 技能 等其余端点面未照搬——本工程消费方
 *       （{@link SandboxRuntimeClient}）只需要文件与 shell 两组。</li>
 *   <li>{@code shell.exec} 的异步会话（{@code async_mode} + {@code v1/shell/wait}）未启用：
 *       与参考实现的同步调用点保持一致。{@code status != completed}（实测到点回
 *       {@code running} 或 {@code no_change_timeout}，两种都带 output/exit_code 双「没结果」）
 *       单独还原成 {@code notCompleted} 字段带出去，不与「跑完了、没输出」共用一个形状。</li>
 * </ul>
 */
public class AgentSandboxRuntimeClient implements SandboxRuntimeClient {

    private static final String READ_PATH = "/v1/file/read";
    private static final String WRITE_PATH = "/v1/file/write";
    private static final String STR_REPLACE_EDITOR_PATH = "/v1/file/str_replace_editor";
    private static final String LIST_PATH = "/v1/file/list";
    private static final String FIND_PATH = "/v1/file/find";
    private static final String DOWNLOAD_PATH = "/v1/file/download";
    private static final String UPLOAD_PATH = "/v1/file/upload";
    private static final String EXEC_PATH = "/v1/shell/exec";

    private static final int DOWNLOAD_CHUNK_BYTES = 64 * 1024;

    /**
     * shell 执行在命令超时之外多给的读超时余量：runtime 到点会自己回一份「还在跑」，
     * 这截余量就是留给它回来的窗口——不留，两边同值抢跑，谁赢看运气。
     */
    private static final Duration EXEC_READ_SLACK = Duration.ofSeconds(15);

    /** 沙盒 runtime 的连接池与线程池由全进程共享（HttpClient 线程安全）。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            // 显式固定 HTTP/1.1，理由同 SandboxProvisionerClient：runtime 服务同为 uvicorn 系，
            // 默认 HTTP/2 的 h2c 升级请求会被拒绝且 body 被丢（2026-09-27 实测 provisioner 侧踩坑）。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String authHeader;
    private final Duration defaultTimeout;

    public AgentSandboxRuntimeClient(String sandboxUrl, String token, Duration defaultTimeout) {
        this.baseUrl = (sandboxUrl == null ? "" : sandboxUrl.strip()).replaceAll("/+$", "");
        this.authHeader = "Bearer " + token;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public SandboxReadFileResult readFile(String file, Integer startLine, Integer endLine) {
        JSONObject body = new JSONObject();
        body.put("file", file);
        if (startLine != null) {
            body.put("start_line", startLine);
        }
        if (endLine != null) {
            body.put("end_line", endLine);
        }
        JSONObject payload = postJson(READ_PATH, body, null);
        requireSuccess(payload, "read", file);
        JSONObject data = dataOf(payload);
        if (data == null) {
            return new SandboxReadFileResult(new byte[0], null);
        }
        String content = data.getString("content");
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        return new SandboxReadFileResult(bytes, data.getString("encoding"));
    }

    @Override
    public SandboxWriteResult writeFile(String file, String content) {
        return writeFile(file, content, null);
    }

    @Override
    public SandboxWriteResult writeFile(String file, String content, String encoding) {
        JSONObject body = new JSONObject();
        body.put("file", file);
        body.put("content", content);
        if (encoding != null && !encoding.isEmpty()) {
            body.put("encoding", encoding);
        }
        return writeResultOf(postJson(WRITE_PATH, body, null));
    }

    @Override
    public SandboxWriteResult strReplaceEditor(
            String command, String path, String oldStr, String newStr, String replaceMode) {
        JSONObject body = new JSONObject();
        body.put("command", command);
        body.put("path", path);
        if (oldStr != null) {
            body.put("old_str", oldStr);
        }
        if (newStr != null) {
            body.put("new_str", newStr);
        }
        if (replaceMode != null && !replaceMode.isEmpty()) {
            body.put("replace_mode", replaceMode);
        }
        return writeResultOf(postJson(STR_REPLACE_EDITOR_PATH, body, null));
    }

    @Override
    public SandboxListPathResult listPath(String path, boolean recursive, boolean includeSize) {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("recursive", recursive);
        body.put("include_size", includeSize);
        JSONObject payload = postJson(LIST_PATH, body, null);
        // 「路径不存在」「那是文件不是目录」都回 HTTP 200 + success:false（真机实测），
        // 只扒 data.files 会把失败读成一个空目录。
        requireSuccess(payload, "list", path);
        JSONObject data = dataOf(payload);
        List<SandboxFileEntry> entries = new ArrayList<>();
        JSONArray files = data == null ? null : data.getJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                JSONObject item = files.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                entries.add(new SandboxFileEntry(
                        item.getString("path"),
                        Boolean.TRUE.equals(item.getBoolean("is_directory")),
                        item.getLong("size"),
                        item.getString("modified_time")));
            }
        }
        return new SandboxListPathResult(entries);
    }

    @Override
    public SandboxFindFilesResult findFiles(String path, String glob) {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("glob", glob);
        JSONObject data = dataOf(postJson(FIND_PATH, body, null));
        List<String> files = new ArrayList<>();
        JSONArray items = data == null ? null : data.getJSONArray("files");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String item = items.getString(i);
                if (item != null) {
                    files.add(item);
                }
            }
        }
        return new SandboxFindFilesResult(files);
    }

    @Override
    public Iterable<byte[]> downloadFile(String path, Duration timeout) {
        String url = baseUrl + DOWNLOAD_PATH + "?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .GET();
        applyTimeout(builder, timeout == null ? defaultTimeout : timeout);
        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exc) {
            throw new SandboxProvisionerException("failed to download sandbox file " + path, exc);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new SandboxProvisionerException("failed to download sandbox file " + path, exc);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), drain(response.body()));
        }
        return new ChunkStream(response.body());
    }

    @Override
    public SandboxWriteResult uploadFile(File source, String path) {
        byte[] payload;
        try {
            payload = Files.readAllBytes(source.toPath());
        } catch (IOException exc) {
            throw new SandboxProvisionerException("failed to read upload source " + source, exc);
        }
        // multipart/form-data 手工拼装（对应 SDK 的 force_multipart=True：file 文件段 + path 表单段）。
        String boundary = "----WenquSandboxBoundary" + UUID.randomUUID().toString().replace("-", "");
        String fileName = source.getName();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if (path != null) {
                buffer.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"path\"\r\n\r\n"
                        + path + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            buffer.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            buffer.write(payload);
            buffer.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exc) {
            throw new SandboxProvisionerException("failed to encode upload payload for " + fileName, exc);
        }
        String url = baseUrl + UPLOAD_PATH;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()));
        applyTimeout(builder, defaultTimeout);
        JSONObject payloadJson = unwrap(sendString(builder.build(), "upload " + fileName));
        JSONObject data = dataOf(payloadJson);
        boolean success = payloadJson != null
                && (Boolean.TRUE.equals(payloadJson.getBoolean("success"))
                        || (data != null && Boolean.TRUE.equals(data.getBoolean("success"))));
        String message = payloadJson == null ? null : payloadJson.getString("message");
        if (message == null && data != null) {
            message = data.getString("file_path");
        }
        return new SandboxWriteResult(success, message);
    }

    @Override
    public SandboxExecResult execCommand(String command, Duration timeout, boolean truncate) {
        JSONObject body = new JSONObject();
        body.put("command", command);
        Duration readTimeout;
        if (timeout != null) {
            double seconds = timeout.toMillis() / 1000.0;
            body.put("timeout", seconds);
            // runtime 除了这个总闸，另有一道「输出多少秒没变化就放弃」的闸，缺省 120 秒，
            // 且它同样是请求字段（2026-10-22 实测：静默 130 秒的命令不带这个字段 120s 就被回
            // no_change_timeout，带上 600 就正常跑完、退出码 0）。不把它抬到调用方要的上限，
            // 模型显式传的 timeout 只有一半说话权——真机那条跑 6 次 chromium 的截图脚本
            // 就是这么在 119.4 秒被掐的，而它请求的是 300。
            body.put("no_change_timeout", seconds);
            // 读超时比命令超时多等一截：两边同值时谁先到点是掷骰子。2026-10-22 实测那次 30 秒的命令
            // 就是 Java 侧先炸（整 30.013s），把 runtime 那句结构化的「还在跑」读成了「沙盒不可达」。
            readTimeout = timeout.plus(EXEC_READ_SLACK);
        } else {
            readTimeout = defaultTimeout;
        }
        body.put("truncate", truncate);
        JSONObject data = dataOf(postJson(EXEC_PATH, body, readTimeout));
        if (data == null) {
            return new SandboxExecResult(null, null, true, null);
        }
        // 服务端语义：output / exit_code 仅在 status=completed 时有值；到点没跑完回的是
        // status=running（请求的总闸）或 status=no_change_timeout（输出静默闸）+ 两个「没结果」。
        // 判据按「不等于 completed」收，别按某一种 status 收：2026-10-22 那轮就是只认 running，
        // 让 no_change_timeout 穿着「跑完了」的外衣一路走到模型面前。
        String status = data.getString("status");
        boolean notCompleted = !"completed".equalsIgnoreCase(status);
        Integer exitCode = data.getInteger("exit_code");
        // 负数是 runtime 自己「这一趟没拿到码」的哨兵（no_change_timeout 那份实测回 -1），
        // 不是 shell 退出码（真码 0-255）。原样带上去，模型看到的就是一行孤零零的
        // 「Exit code: -1」，长得跟「命令自己返回了 -1」一模一样，分不清是崩了还是被切了。
        if (exitCode != null && exitCode < 0) {
            exitCode = null;
        }
        return new SandboxExecResult(data.getString("output"), exitCode, notCompleted, status);
    }

    // ==================== HTTP 基础设施 ====================

    private JSONObject postJson(String path, JSONObject body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
        applyTimeout(builder, timeout == null ? defaultTimeout : timeout);
        return unwrap(sendString(builder.build(), path));
    }

    private String sendString(HttpRequest request, String label) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException exc) {
            throw new SandboxProvisionerException("sandbox runtime request failed: " + label, exc);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new SandboxProvisionerException("sandbox runtime request interrupted: " + label, exc);
        }
    }

    private static JSONObject unwrap(String body) {
        if (body == null || body.isEmpty()) {
            return new JSONObject();
        }
        JSONObject parsed;
        try {
            parsed = JSONObject.parseObject(body);
        } catch (RuntimeException exc) {
            throw new SandboxProvisionerException("invalid sandbox runtime response: " + body, exc);
        }
        return parsed == null ? new JSONObject() : parsed;
    }

    /** 统一响应包装的 {@code data} 载荷（对应 SDK 的 {@code response.data}）。 */
    /**
     * runtime 对失败回的是 <b>HTTP 200 + {@code success:false}</b>（真机实测），不认这一层就会把失败读成结果：
     * 读不存在的文件＝「空文件、成功」，列不存在的目录＝「空目录、成功」。
     * <p>{@code not_found} 必须还原成 {@link FileNotFoundError}：流式执行那三处轮询
     * （{@code drainLines} / {@code tryReadExitCode} / {@code waitForMarker}）都靠它判「标记文件还没落盘」，
     * 换成其它异常会把轮询直接打断。
     */
    private static void requireSuccess(JSONObject payload, String label, String path) {
        if (payload != null && Boolean.TRUE.equals(payload.getBoolean("success"))) {
            return;
        }
        JSONObject data = payload == null ? null : payload.getJSONObject("data");
        String message = data != null && data.getString("message") != null
                ? data.getString("message")
                : (payload == null ? "empty response" : payload.getString("message"));
        if (data != null && "not_found".equals(data.getString("error_type"))) {
            throw new FileNotFoundError(path);
        }
        throw new SandboxProvisionerException(
                "沙盒 " + label + " 失败（" + path + "）：" + message);
    }

    private static JSONObject dataOf(JSONObject payload) {
        return payload == null ? null : payload.getJSONObject("data");
    }

    private static SandboxWriteResult writeResultOf(JSONObject payload) {
        boolean success = payload != null && Boolean.TRUE.equals(payload.getBoolean("success"));
        return new SandboxWriteResult(success, payload == null ? null : payload.getString("message"));
    }

    /**
     * 构造与 SDK {@code ApiError.__str__} 同形的异常消息。
     *
     * <p><b>格式不可改</b>：上层以 {@code "status_code: 404"} 子串判定文件不存在。
     */
    private static SandboxProvisionerException apiError(int statusCode, String body) {
        return new SandboxProvisionerException(
                "headers: {}, status_code: " + statusCode + ", body: " + (body == null ? "" : body));
    }

    private static void applyTimeout(HttpRequest.Builder builder, Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            builder.timeout(timeout);
        }
    }

    private static String drain(InputStream stream) {
        try (InputStream closeable = stream) {
            return new String(closeable.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            return "";
        }
    }

    /** 下载流的弃用兜底：迭代中途异常或调用方放弃迭代（如导出限长、上层 catch 吞错）时，
     *  没有任何代码路径会再调 close()，HTTP 连接只能靠这里在对象不可达后释放 */
    private static final Cleaner CLEANER = Cleaner.create();

    /** 分块读取沙盒文件流（对应 SDK {@code download_file} 的 {@code Iterator[bytes]}）。
     *  关闭三重保障：迭代自然耗尽即关（ChunkIterator.next）、读流异常先关再抛（ChunkIterator.readChunk）、
     *  其余弃用路径由 {@link #CLEANER} 兜底。 */
    private static final class ChunkStream implements Iterable<byte[]> {

        private final InputStream stream;
        private boolean consumed;

        ChunkStream(InputStream stream) {
            this.stream = stream;
            // 动作只捕获构造参数（流引用），不得捕获 this——否则本对象永远不可达，兜底失效
            CLEANER.register(this, () -> {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // best-effort close
                }
            });
        }

        @Override
        public Iterator<byte[]> iterator() {
            if (consumed) {
                return List.<byte[]>of().iterator();
            }
            consumed = true;
            return new ChunkIterator(stream);
        }
    }

    private static final class ChunkIterator implements Iterator<byte[]> {

        private final InputStream stream;
        private byte[] next;

        ChunkIterator(InputStream stream) {
            this.stream = stream;
            this.next = readChunk();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public byte[] next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            byte[] current = next;
            next = readChunk();
            if (next == null) {
                close();
            }
            return current;
        }

        private byte[] readChunk() {
            try {
                byte[] buffer = new byte[DOWNLOAD_CHUNK_BYTES];
                int read = stream.read(buffer);
                if (read < 0) {
                    return null;
                }
                if (read == buffer.length) {
                    return buffer;
                }
                byte[] trimmed = new byte[read];
                System.arraycopy(buffer, 0, trimmed, 0, read);
                return trimmed;
            } catch (IOException exc) {
                close();
                throw new SandboxProvisionerException("failed to read sandbox file stream", exc);
            }
        }

        private void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
    }
}
