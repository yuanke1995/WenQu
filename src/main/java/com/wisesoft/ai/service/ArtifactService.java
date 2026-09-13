package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 产物交付（present_artifacts 模式）：模型在回答中调用工具生成文件，
 * 落盘到「会话级」隔离目录 data/artifacts/{sessionId}/，并通过 SSE 实时下发产物卡片（可预览/下载）。
 * <p>
 * 安全约束：
 * - 文件名白名单扩展名（md/txt/csv/json/html/htm），防任意文件写入
 * - 文件名经净化（去路径分隔符/特殊字符），防目录穿越
 * - 产物只落当前会话目录，与会话隔离
 * <p>
 * 实时下发：{@link #registerEmitter} 在问答开始时登记 sessionId→emitter，工具执行时
 * {@link #publish} 用同一 emitter 发送 artifact 事件；问答结束 {@link #unregisterEmitter} 清理。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ArtifactService {

    /** 允许生成的产物扩展名（小写，无点） */
    private static final Set<String> ALLOWED_EXTS = Set.of("md", "txt", "csv", "json", "html", "htm");
    /** 单文件内容上限（约 1MB，防止工具把超大内容写盘占满磁盘） */
    private static final int MAX_BYTES = 1024 * 1024;
    /** 文件名最大长度（含扩展名） */
    private static final int MAX_NAME_LEN = 80;

    private final AiAppProperties properties;
    private final ImageUrlSigner imageUrlSigner;

    /** 会话级 emitter 注册表：问答开始时登记，结束清理；供工具在流式执行中实时下发产物事件 */
    private static final ConcurrentHashMap<String, SseEmitter> EMITTERS = new ConcurrentHashMap<>();
    /** 会话级产物记录：问答期间生成的产物（done 事件汇总下发兜底，避免流式 artifact 事件丢失） */
    private static final ConcurrentHashMap<String, java.util.List<java.util.Map<String, Object>>> SESSION_ARTIFACTS =
            new ConcurrentHashMap<>();

    public ArtifactService(AiAppProperties properties, ImageUrlSigner imageUrlSigner) {
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
    }

    /** 问答开始时登记：sessionId → emitter（用于工具实时下发 artifact 事件） */
    public void registerEmitter(String sessionId, SseEmitter emitter) {
        if (sessionId != null) {
            EMITTERS.put(sessionId, emitter);
        }
    }

    /** 问答结束时清理：移除 sessionId 的 emitter 注册与产物记录 */
    public void unregisterEmitter(String sessionId) {
        if (sessionId != null) {
            EMITTERS.remove(sessionId);
            SESSION_ARTIFACTS.remove(sessionId);
        }
    }

    /**
     * 返回本会话期间已生成的产物清单（done 事件汇总下发兜底）；无则空列表。不消费/清空。
     * 返回的是下发副本：url 已按需重新签名（原始 URL 存内存，展示层签名——与图片同惯例）。
     */
    public java.util.List<java.util.Map<String, Object>> takeArtifacts(String sessionId) {
        java.util.List<java.util.Map<String, Object>> list = sessionId == null ? null : SESSION_ARTIFACTS.get(sessionId);
        if (list == null || list.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>(list.size());
        synchronized (list) {
            for (java.util.Map<String, Object> e : list) {
                java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(e);
                copy.put("url", imageUrlSigner.signUrl(String.valueOf(e.get("url"))));
                out.add(copy);
            }
        }
        return out;
    }

    /** 当前会话是否仍在线（emitter 未被移除） */
    public boolean isSessionActive(String sessionId) {
        return sessionId != null && EMITTERS.containsKey(sessionId);
    }

    /**
     * 生成产物：把文本内容写盘到 {images.dir}/artifacts/{sessionId}/ 下，返回可访问（已签名）的 URL。
     * 文件名为空/非法/扩展名不在白名单时抛 IllegalArgumentException（fail-loud，调用方转为工具错误返回）。
     *
     * @return { url, filename, size } 的 URL（/ai/artifacts/{sessionId}/{filename}，鉴权开启时带签名）
     */
    public String write(String sessionId, String filename, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少会话标识");
        }
        String safeName = sanitizeFilename(filename);
        if (safeName == null) {
            throw new IllegalArgumentException("产物文件名不合法或格式不支持（支持 md/txt/csv/json/html）");
        }
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("产物内容过大（超过 1MB）");
        }
        try {
            Path dir = Paths.get(properties.getImages().getDir(), "artifacts", sessionId);
            Files.createDirectories(dir);
            Path file = dir.resolve(safeName);
            Files.write(file, bytes);
            String url = "/ai/artifacts/" + sessionId + "/" + safeName;
            // 记录到会话产物清单（原始 URL 存内存，下发时再签名——与图片同惯例：库里存原始、展示层签名）
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("url", url);
            entry.put("filename", safeName);
            entry.put("size", bytes.length);
            SESSION_ARTIFACTS.computeIfAbsent(sessionId, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(entry);
            return imageUrlSigner.signUrl(url);
        } catch (IOException e) {
            log.warn("[FAIL-LOUD] 产物落盘失败 session={} file={}: {}", sessionId, safeName, e.getMessage());
            throw new IllegalStateException("产物保存失败: " + e.getMessage());
        }
    }

    /**
     * 通过 SSE 实时下发一条产物事件（工具执行时调用）。客户端断开或未登记 emitter 时静默忽略。
     *
     * @param eventType 事件类型（"artifact"）
     * @param payload   JSON 字符串（含 url/filename/format/size 等）
     */
    public void publish(String sessionId, String eventType, String payload) {
        if (sessionId == null) return;
        SseEmitter emitter = EMITTERS.get(sessionId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data("{\"type\":\"" + eventType + "\",\"content\":" + payload
                            + ",\"sessionId\":\"" + sessionId + "\"}"));
        } catch (IOException | IllegalStateException e) {
            log.warn("[FAIL-LOUD] 产物 SSE 下发失败 session={}: {}", sessionId, e.getMessage());
            EMITTERS.remove(sessionId);
        }
    }

    /** 返回当前会话产物目录是否存在（用于查询接口，可选） */
    public boolean hasArtifacts(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        Path dir = Paths.get(properties.getImages().getDir(), "artifacts", sessionId);
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /** 净化文件名：去路径/非法字符、限长、强约束扩展名。返回 null 表示不合法。 */
    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LEN) {
            trimmed = trimmed.substring(0, MAX_NAME_LEN);
        }
        // 去路径分隔符与危险字符，防目录穿越/命令注入
        String cleaned = trimmed.replaceAll("[/\\\\:\"'<>|?*\\x00-\\x1f]", "_");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return null;
        // 强制扩展名白名单
        int dot = cleaned.lastIndexOf('.');
        String ext = dot > 0 ? cleaned.substring(dot + 1).toLowerCase() : "";
        if (!ALLOWED_EXTS.contains(ext)) {
            // 无扩展名 → 默认 .md；非法扩展名 → 拒绝
            if (ext.isEmpty()) {
                cleaned = cleaned + ".md";
            } else {
                return null;
            }
        }
        return cleaned;
    }

    /** 当前时间（秒，测试/日志用） */
    long epochSeconds() {
        return Instant.now().getEpochSecond();
    }
}
