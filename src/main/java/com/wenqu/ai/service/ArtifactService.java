package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Session;
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
import java.util.HashSet;
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
    /** 字节级产物的扩展名白名单（沙盒文件交付）：在文本白名单上放宽到沙盒典型输出（脚本/代码/图片/表格/压缩包） */
    private static final Set<String> ALLOWED_EXTS_BINARY = Set.of(
            "md", "txt", "csv", "json", "html", "htm",
            "py", "java", "log", "png", "jpg", "jpeg", "svg", "xlsx", "xls", "pdf", "zip");
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
    private final ConfigService configService;

    /** 会话级 emitter 注册表：问答开始时登记，结束清理；供工具在流式执行中实时下发产物事件 */
    private static final ConcurrentHashMap<String, SseEmitter> EMITTERS = new ConcurrentHashMap<>();
    /** 会话级产物记录：问答期间生成的产物（done 事件汇总下发兜底，避免流式 artifact 事件丢失） */
    private static final ConcurrentHashMap<String, List<Map<String, Object>>> SESSION_ARTIFACTS =
            new ConcurrentHashMap<>();
    /** 会话级产物生成监听（主流程登记，问答结束清理）：产物落盘即回调 (sessionId, 产物在本轮清单中的下标)，
     *  主流程据此把产物卡片按「生成时刻」插进回答时间线——否则刷新后产物只能堆在气泡底部。 */
    private static final ConcurrentHashMap<String, java.util.function.BiConsumer<String, Integer>> ARTIFACT_LISTENERS =
            new ConcurrentHashMap<>();

    public ArtifactService(AppProperties properties, ImageUrlSigner imageUrlSigner,
                           ArtifactMapper artifactMapper, SessionMapper sessionMapper,
                           ConfigService configService) {
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.artifactMapper = artifactMapper;
        this.sessionMapper = sessionMapper;
        this.configService = configService;
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
            ARTIFACT_LISTENERS.remove(sessionId);
        }
    }

    /**
     * 登记产物生成监听：问答主流程在流开始前登记，产物落盘时同步拿到它在「本轮产物清单」里的下标，
     * 用于把产物卡片插入回答时间线的正确位置（生成时刻）。问答结束随 unregisterEmitter 一并清理。
     */
    public void registerArtifactListener(String sessionId, java.util.function.BiConsumer<String, Integer> listener) {
        if (sessionId != null && listener != null) {
            ARTIFACT_LISTENERS.put(sessionId, listener);
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
        return writeBytes(sessionId, filename,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8), description, ALLOWED_EXTS);
    }

    /**
     * 字节级产物写入（沙盒文件交付 {@code deliver_artifact} 用）：与文本产物同一套落盘/登记/推送约定，
     * 差异仅在扩展名白名单放宽到沙盒典型输出（py/png/xlsx/zip 等），大小上限同为 1MB。
     *
     * @return { id, url, filename, ext, size, description }
     */
    public Map<String, Object> writeBytes(String sessionId, String filename, byte[] bytes, String description) {
        return writeBytes(sessionId, filename, bytes, description, ALLOWED_EXTS_BINARY);
    }

    private Map<String, Object> writeBytes(String sessionId, String filename, byte[] bytes,
                                           String description, Set<String> allowedExts) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少会话标识");
        }
        String safeName = sanitizeFilename(filename, allowedExts);
        if (safeName == null) {
            throw new IllegalArgumentException("产物文件名不合法或扩展名不支持");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("产物内容为空");
        }
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
        List<Map<String, Object>> list =
                SESSION_ARTIFACTS.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        int artifactIndex;
        synchronized (list) {
            list.add(entry);
            artifactIndex = list.size() - 1;
        }
        // 通知主流程：产物在时间线上的位置（下标）——监听缺失（非问答链路写入）时跳过，不影响交付
        java.util.function.BiConsumer<String, Integer> listener = ARTIFACT_LISTENERS.get(sessionId);
        if (listener != null) {
            try {
                listener.accept(sessionId, artifactIndex);
            } catch (Exception e) {
                log.warn("[ARTIFACT] 时间线登记失败（不影响交付）session={} file={}: {}", sessionId, safeName, e.getMessage());
            }
        }
        return info;
    }

    /** 我的产物列表（按 uid 归属，时间倒序；keyword 匹配文件名）。url 已按需签名；expireTime 按当前全局保留天数动态算出。 */
    public List<Map<String, Object>> list(String uid, String keyword) {
        if (uid == null || uid.isBlank()) return List.of();
        int retentionDays = configService.getInt("artifact.retentionDays", ConfigDefaults.ARTIFACT_RETENTION_DAYS);
        LambdaQueryWrapper<Artifact> w = new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getUid, uid)
                .eq(Artifact::getDeleted, 0)
                .orderByDesc(Artifact::getCreateTime);
        if (keyword != null && !keyword.isBlank()) {
            w.like(Artifact::getFilename, keyword.trim());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Artifact row : artifactMapper.selectList(w)) {
            out.add(toDto(row, retentionDays));
        }
        return out;
    }

    /** 取单个产物（校验归属，数据按 userId 隔离）。返回 null = 不存在或已删除。 */
    public Artifact getOwned(String id, String uid) {
        Artifact row = id == null ? null : artifactMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getDeleted())) return null;
        if (!Objects.equals(row.getUid(), uid)) {
            throw new IllegalArgumentException("无权访问他人的产物");
        }
        return row;
    }

    /**
     * 按产物 id 批量换发**新鲜签名**的下载地址（对话页专用）。
     * <p><b>为什么需要</b>：签名 URL 有效期 {@code images.authExpireSeconds} 只有 1 小时，
     * 而产物页每次进页面都调{@link #list} 现场重签、永远拿到新地址；对话页的卡片却用的是
     * SSE 下发 / 历史接口里那一份**签好就不变**的 URL —— 页面开着超过一小时后再点，
     * {@code ImageAuthInterceptor} 判 expire 过期直接 401，表现为「对话里的产物下载不下来」，
     * 同一文件在「我的产物」页却正常。前端在渲染卡片前调本方法换一批新签名即可根治。
     * <p><b>为什么以 id 为入参而不是 url</b>：以 url 为入参等于开放"给任意路径签名"的能力，
     * 会把产物鉴权降级成万能签名器（可签出别人的产物、甚至 images 下的文档截图）。
     * 以 id 为入参则归属校验天然在库里，且逐条fail-closed。
     * <p><b>口径</b>：逐条按 uid 校验归属，取不回的（不存在/已删/超期清理/无权）直接跳过，
     * 不报错也不返回——前端无需处理部分失败，签名是「尽力刷新」而非交付必需。
     *
     * @param ids 产物 id 列表（去重、去空）
     * @return 能取回的产物视图：{id, filename, url（已签名）}；顺序与入参一致
     */
    public List<Map<String, Object>> refreshSignatures(String uid, List<String> ids) {
        if (uid == null || uid.isBlank() || ids == null || ids.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || !seen.add(id)) continue;
            Artifact row = null;
            try {
                row = getOwned(id, uid); // 归属不符抛 IllegalArgumentException
            } catch (IllegalArgumentException e) {
                continue; // 别人的产物：静默跳过，不泄露其存在性
            }
            // 文件已不在磁盘（超期清理只删了文件、或部署换机丢了卷）：给链接也是必然 404，跳过
            if (row == null || !Files.isRegularFile(baseDir().resolve(row.getObjectKey()).normalize())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("filename", row.getFilename());
            m.put("url", imageUrlSigner.signUrl("/ai/" + row.getObjectKey()));
            out.add(m);
        }
        return out;
    }

    /**
     * 删除产物（逻辑删除 + 删除文件）；无归属校验失败抛异常，返回 false = 不存在/已删除。
     * <p>
     * <b>落库必须走 {@code deleteById}</b>：{@code deleted} 是本工程全局的 {@code logic-delete-field}，
     * MyBatis-Plus 为逻辑删除实体生成的 {@code updateById} 语句会**把该列从 SET 子句里剔除**
     * （只在 WHERE 上补 {@code deleted=0}）⇒「{@code row.setDeleted(1)} + {@code updateById}」是静默无效的：
     * 文件被删掉、记录却仍是未删除状态，于是列表里留着一条下载必然 404 的行。
     */
    public boolean softDelete(String id, String uid) {
        Artifact row = getOwned(id, uid);
        if (row == null) return false;
        deleteFileQuietly(row.getObjectKey());
        artifactMapper.deleteById(row.getId());
        log.info("[ARTIFACT] 已删除产物 id={} uid={} file={}", id, row.getUid(), row.getFilename());
        return true;
    }

    /**
     * 超期产物清理（供调度任务调用）：删文件 + 逻辑删记录，返回清理条数。retentionDays ≤ 0 = 不清理。
     * <p>落库同 {@link #softDelete}：只能走 {@code deleteById}，{@code updateById} 改不动 {@code deleted}。
     */
    public int cleanupExpired(int retentionDays) {
        if (retentionDays <= 0) return 0;
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        List<Artifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getDeleted, 0)
                .lt(Artifact::getCreateTime, before));
        int n = 0;
        for (Artifact row : rows) {
            deleteFileQuietly(row.getObjectKey());
            artifactMapper.deleteById(row.getId());
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
     * 分享链接下载产物：凭分享会话取回**属于该会话**的产物文件。
     *
     * <p><b>入参是库里的产物 url，不是访客给的路径</b>：调用方
     * （{@code SessionShareService}）按会话内产物的固定序号取出该条记录，
     * 访客全程只能提供一个整数下标。这既避免了把 {@code artifacts/{uid}/…} 里的
     * uid 暴露到分享出去的 URL 上，也从根上消除了"路径是访客可控输入"这个攻击面——
     * 目录穿越、任意文件读在结构上就不成立，而不是靠过滤拦下来。
     *
     * <p><b>归属校验（fail-closed）</b>：产物可能已被软删、被超期清理、或文件已不在磁盘，
     * 任一情况都返回 null（调用方按 404），不给访客一个点开必然失败的下载入口。
     *
     * @return 可下载的产物（文件已在磁盘上）；不通过校验或文件已不在返回 null
     */
    public SharedArtifact readForShare(String sessionId, String storedUrl) {
        if (sessionId == null || sessionId.isBlank() || storedUrl == null || storedUrl.isBlank()) {
            return null;
        }
        String rel = toObjectKey(storedUrl);
        if (rel == null) return null;
        String[] seg = rel.split("/");
        // artifacts/{x}/{y}（遗留布局：x=sessionId）或 artifacts/{uid}/{yyyyMM}/{id}_{name}（现行）
        if (seg.length != 3 && seg.length != 4) return null;

        // 库里有行：归属与存活一律以库为准（最强判据），不看路径字面
        Artifact row = findByObjectKey(rel);
        if (row != null) {
            if (row.getDeleted() != null && row.getDeleted() == 1) return null;
            if (!sessionId.equals(row.getSessionId())) return null;
            return materialize(row.getObjectKey(), row.getFilename());
        }
        // 库里无行：只可能是产物表建立之前的遗留布局，要求目录段严格等于本会话 id
        if (seg.length == 3 && sessionId.equals(seg[1])) {
            return materialize(rel, seg[2]);
        }
        return null;
    }

    /** 分享下载用的产物视图（file 已确认存在） */
    public record SharedArtifact(Path file, String filename) {
    }

    /**
     * 库里存的产物 url → 产物根目录下的相对路径。存的是原始 url（签名在展示层现签），
     * 形如 {@code /ai/artifacts/…}；可能带 query（历史脏数据）故先剥掉。
     * 越界形态（不以 artifacts/ 开头、含穿越段）一律 null。
     */
    private String toObjectKey(String storedUrl) {
        String p = storedUrl;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.startsWith("/ai/")) p = p.substring("/ai/".length());
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isBlank() || !p.startsWith("artifacts/")) return null;
        if (p.contains("..") || p.contains("\\") || p.contains("\0")) return null;
        for (String s : p.split("/")) {
            if (s.isBlank() || s.equals(".") || s.equals("..")) return null;
        }
        // 二次保险：解析后必须仍在产物根目录内
        Path root = baseDir();
        if (!root.resolve(p).normalize().startsWith(root)) return null;
        return p;
    }

    /** 按 objectKey 回表（精确匹配，不做模糊前缀——宁可查不到也不猜） */
    private Artifact findByObjectKey(String objectKey) {
        List<Artifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getObjectKey, objectKey)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 确认文件确实在磁盘上才返回（表在文件不在 = 访客拿到一个必然 404 的下载入口） */
    private SharedArtifact materialize(String objectKey, String filename) {
        if (objectKey == null || objectKey.isBlank()) return null;
        Path f = baseDir().resolve(objectKey).normalize();
        if (!f.startsWith(baseDir()) || !Files.isRegularFile(f)) return null;
        String name = (filename == null || filename.isBlank())
                ? f.getFileName().toString() : filename;
        return new SharedArtifact(f, name);
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

    private Map<String, Object> toDto(Artifact row, int retentionDays) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("filename", row.getFilename());
        m.put("ext", row.getExt());
        m.put("size", row.getSize());
        m.put("description", row.getDescription() == null ? "" : row.getDescription());
        m.put("sessionId", row.getSessionId());
        m.put("createTime", row.getCreateTime() == null ? null : row.getCreateTime().toString());
        // 预计清理时间：不落库、按当前全局保留天数即时算，保证与 cleanupExpired 口径同源
        // （落库会在管理员改 retentionDays 后变成陈旧数据）；retentionDays ≤ 0 = 永不清理 → null
        m.put("expireTime", expireTimeOf(row, retentionDays));
        m.put("url", imageUrlSigner.signUrl("/ai/" + row.getObjectKey()));
        return m;
    }

    /** 预计清理时间（ISO 字符串）；永不清理或无创建时间返回 null。 */
    private static String expireTimeOf(Artifact row, int retentionDays) {
        if (retentionDays <= 0 || row.getCreateTime() == null) return null;
        return row.getCreateTime().plusDays(retentionDays).toString();
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
        return sanitizeFilename(name, ALLOWED_EXTS);
    }

    private String sanitizeFilename(String name, Set<String> allowedExts) {
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
        if (!allowedExts.contains(ext)) {
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
