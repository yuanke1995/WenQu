package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.mapper.ArtifactMapper;
import com.wisesoft.ai.mapper.SessionMapper;
import com.wisesoft.ai.model.Artifact;
import com.wisesoft.ai.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 产物交付（present_artifacts 模式）：模型在回答中调用工具生成文件，
 * 落盘到 {@code {images.dir}/artifacts/{uid}/{yyyyMM}/{id}_{名称}} 并**登记进 c_ai_artifact**，
 * 通过 SSE 实时下发产物卡片（可预览/下载）。
 * <p>
 * 归属口径：产物归**用户**（取产生它的会话的 {@code c_ai_session.user_id}），不归会话——
 * 会话可以被清理，但成果仍在「我的产物」里可查可下；因此本服务同时提供列表 / 删除 / 超期清理。
 * 文件名带产物 id 前缀，**同一会话重复生成同名文件不再互相覆盖**（旧实现按 {sessionId}/{文件名} 落盘，
 * 第二次生成会覆盖第一个且 URL 相同，历史产物直接丢失）。
 * <p>
 * 安全约束：
 * - 文件名白名单扩展名（md/txt/csv/json/html/htm），防任意文件写入
 * - 文件名经净化（去路径分隔符/特殊字符），防目录穿越
 * - 单文件上限 1MB；列表/删除按 uid 归属校验（管理员可越权）
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
    /** 产物说明最大长度（列宽 255，留余量） */
    private static final int MAX_DESC_LEN = 200;

    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final ArtifactMapper artifactMapper;
    private final SessionMapper sessionMapper;

    /** 会话级 emitter 注册表：问答开始时登记，结束清理；供工具在流式执行中实时下发产物事件 */
    private static final ConcurrentHashMap<String, SseEmitter> EMITTERS = new ConcurrentHashMap<>();
    /** 会话级产物记录：问答期间生成的产物（done 事件汇总下发兜底，避免流式 artifact 事件丢失） */
    private static final ConcurrentHashMap<String, List<Map<String, Object>>> SESSION_ARTIFACTS =
            new ConcurrentHashMap<>();

    public ArtifactService(AppProperties properties, ImageUrlSigner imageUrlSigner,
                           ArtifactMapper artifactMapper, SessionMapper sessionMapper) {
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.artifactMapper = artifactMapper;
        this.sessionMapper = sessionMapper;
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
    public List<Map<String, Object>> takeArtifacts(String sessionId) {
        List<Map<String, Object>> list = sessionId == null ? null : SESSION_ARTIFACTS.get(sessionId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        synchronized (list) {
            for (Map<String, Object> e : list) {
                Map<String, Object> copy = new LinkedHashMap<>(e);
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
     * 生成产物：写盘 + 落库（归属取会话的 user_id），返回可访问（已签名）的 URL。
     * 文件名为空/非法/扩展名不在白名单时抛 IllegalArgumentException（fail-loud，调用方转为工具错误返回）。
     *
     * @return { id, url, filename, ext, size, description }
     */
    public Map<String, Object> write(String sessionId, String filename, String content, String description) {
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
        String uid = resolveOwner(sessionId);
        String id = UUID.randomUUID().toString().replace("-", "");
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        // id 前缀 + 用户/月份分片：既防同名覆盖，也避免单目录堆到几十万文件
        String objectKey = "artifacts/" + uid + "/" + month + "/" + id + "_" + safeName;
        Path file = baseDir().resolve(objectKey);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            log.warn("[FAIL-LOUD] 产物落盘失败 session={} file={}: {}", sessionId, safeName, e.getMessage());
            throw new IllegalStateException("产物保存失败: " + e.getMessage());
        }

        String ext = extOf(safeName);
        Artifact row = new Artifact();
        row.setId(id);
        row.setUid(uid);
        row.setSessionId(sessionId);
        row.setFilename(safeName);
        row.setExt(ext);
        row.setSize(bytes.length);
        row.setObjectKey(objectKey);
        row.setDescription(description == null || description.isBlank() ? null : trimTo(description, MAX_DESC_LEN));
        row.setDeleted(0);
        row.setCreateTime(LocalDateTime.now());
        artifactMapper.insert(row);

        String rawUrl = "/ai/" + objectKey;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("url", imageUrlSigner.signUrl(rawUrl));
        info.put("filename", safeName);
        info.put("ext", ext);
        info.put("size", bytes.length);
        info.put("description", row.getDescription() == null ? "" : row.getDescription());
        // 记录到会话产物清单（原始 URL 存内存，下发时再签名——与图片同惯例：库里存原始、展示层签名）
        Map<String, Object> entry = new LinkedHashMap<>(info);
        entry.put("url", rawUrl);
        SESSION_ARTIFACTS.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(entry);
        return info;
    }

    /** 我的产物列表（按 uid 归属，时间倒序；keyword 匹配文件名）。url 已按需签名。 */
    public List<Map<String, Object>> list(String uid, String keyword) {
        if (uid == null || uid.isBlank()) return List.of();
        LambdaQueryWrapper<Artifact> w = new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getUid, uid)
                .eq(Artifact::getDeleted, 0)
                .orderByDesc(Artifact::getCreateTime);
        if (keyword != null && !keyword.isBlank()) {
            w.like(Artifact::getFilename, keyword.trim());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Artifact row : artifactMapper.selectList(w)) {
            out.add(toDto(row));
        }
        return out;
    }

    /** 取单个产物（校验归属；管理员可越权）。返回 null = 不存在或已删除。 */
    public Artifact getOwned(String id, String uid, boolean admin) {
        Artifact row = id == null ? null : artifactMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getDeleted())) return null;
        if (!admin && !Objects.equals(row.getUid(), uid)) {
            throw new IllegalArgumentException("无权访问他人的产物");
        }
        return row;
    }

    /** 删除产物（软删 + 删除文件）；无归属校验失败抛异常，返回 false = 不存在/已删除。 */
    public boolean softDelete(String id, String uid, boolean admin) {
        Artifact row = getOwned(id, uid, admin);
        if (row == null) return false;
        deleteFileQuietly(row.getObjectKey());
        row.setDeleted(1);
        artifactMapper.updateById(row);
        log.info("[ARTIFACT] 已删除产物 id={} uid={} file={}", id, row.getUid(), row.getFilename());
        return true;
    }

    /** 超期产物清理（供调度任务调用）：删文件 + 软删记录，返回清理条数。retentionDays ≤ 0 = 不清理。 */
    public int cleanupExpired(int retentionDays) {
        if (retentionDays <= 0) return 0;
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        List<Artifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getDeleted, 0)
                .lt(Artifact::getCreateTime, before));
        int n = 0;
        for (Artifact row : rows) {
            deleteFileQuietly(row.getObjectKey());
            row.setDeleted(1);
            artifactMapper.updateById(row);
            n++;
        }
        return n;
    }

    /** 本会话是否已产生产物（查库，供会话视图标记） */
    public boolean hasArtifacts(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return artifactMapper.selectCount(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getSessionId, sessionId)
                .eq(Artifact::getDeleted, 0)) > 0;
    }

    /**
     * 通过 SSE 实时下发一条产物事件（工具执行时调用）。客户端断开或未登记 emitter 时静默忽略。
     *
     * @param eventType 事件类型（"artifact"）
     * @param payload   JSON 字符串（含 id/url/filename/size 等）
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

    /** 产物归属用户 = 产生它的会话的 user_id（会话是归属的单一来源，工具不需要另传 uid） */
    private String resolveOwner(String sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        String uid = session == null ? null : session.getUserId();
        if (uid == null || uid.isBlank()) {
            throw new IllegalStateException("无法确定产物归属：会话 " + sessionId + " 不存在或缺少归属用户");
        }
        return uid;
    }

    private Map<String, Object> toDto(Artifact row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("filename", row.getFilename());
        m.put("ext", row.getExt());
        m.put("size", row.getSize());
        m.put("description", row.getDescription() == null ? "" : row.getDescription());
        m.put("sessionId", row.getSessionId());
        m.put("createTime", row.getCreateTime() == null ? null : row.getCreateTime().toString());
        m.put("url", imageUrlSigner.signUrl("/ai/" + row.getObjectKey()));
        return m;
    }

    private Path baseDir() {
        return Paths.get(properties.getImages().getDir()).toAbsolutePath().normalize();
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static String trimTo(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 删除产物文件；文件已不在也不报错（记录仍要标删除，否则列表里留着死链接） */
    private void deleteFileQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            Files.deleteIfExists(baseDir().resolve(objectKey));
        } catch (IOException e) {
            log.warn("[FAIL-LOUD] 产物文件删除失败 key={}: {}", objectKey, e.getMessage());
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
