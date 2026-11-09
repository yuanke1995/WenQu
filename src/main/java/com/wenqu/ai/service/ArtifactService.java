package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.util.PreviewTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
 * - 文件名白名单扩展名（{@link PreviewTypes#TEXT_ARTIFACT_EXTS} / {@link PreviewTypes#FILE_DELIVERY_EXTS}），
 *   防任意文件写入；清单只从 {@code PreviewTypes} 派生，别再本地抄一张
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

    /** 允许生成的文本产物扩展名（小写，无点）：与「能预览」同源，见 {@link PreviewTypes#TEXT_ARTIFACT_EXTS} */
    private static final Set<String> ALLOWED_EXTS = PreviewTypes.TEXT_ARTIFACT_EXTS;
    /** 字节级产物的扩展名白名单（沙盒文件交付）：在文本白名单上放宽到图片/PDF/Office/压缩包 */
    private static final Set<String> ALLOWED_EXTS_BINARY = PreviewTypes.FILE_DELIVERY_EXTS;
    /** 单文件内容上限（约 1MB，防止工具把超大内容写盘占满磁盘） */
    private static final int MAX_BYTES = 1024 * 1024;
    /** 文件名最大长度（含扩展名） */
    private static final int MAX_NAME_LEN = 80;
    /** 产物说明最大长度（列宽 255，留余量） */
    private static final int MAX_DESC_LEN = 200;
    /** 用户重命名显示名最大长度（列宽 120，留余量） */
    private static final int MAX_DISPLAY_NAME_LEN = 60;
    /** 一次打包下载的产物数量上限：再多就该分批，避免一个请求长时间占用服务器 */
    private static final int MAX_ZIP_ITEMS = 50;
    /** 一次打包下载的总字节上限（单个产物帽子是 1MB，50 个也远小于此值；此处防历史异常数据） */
    private static final long MAX_ZIP_BYTES = 100L * 1024 * 1024;

    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final ArtifactMapper artifactMapper;
    private final SessionMapper sessionMapper;
    private final ConfigService configService;
    /** 运行总线：产物事件也要扇给「接流回来的观众」，直发原发起通道在断线后就送不到了 */
    private final ChatRunBus chatRunBus;
    /** 工作区归属：产物由 session 反推工作区，与沙盒作用域共用同一份推导（{@link ProjectService#projectIdOfSession}） */
    private final ProjectService projectService;

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
                           ConfigService configService, ChatRunBus chatRunBus,
                           ProjectService projectService) {
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.artifactMapper = artifactMapper;
        this.sessionMapper = sessionMapper;
        this.configService = configService;
        this.chatRunBus = chatRunBus;
        this.projectService = projectService;
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
     * 返回本轮已生成的产物清单，**不消费**（接流快照用：一条流可能被多次 attach，消费会让后来者读到空）。
     * 返回的是下发副本：url 已按需重新签名（原始 URL 存内存，展示层签名——与图片同惯例）。
     */
    public List<Map<String, Object>> takeArtifacts(String sessionId) {
        return signAll(sessionId, false);
    }

    /**
     * 本轮结算：取出产物清单**并清空**本轮账（收尾落库与 done 帧用这一份）。
     * <p>必须消费：清单是按会话累计的，收尾不清空的话下一轮的 {@code appendMessage} 会把上一轮的产物
     * 再写一遍——「一个产物在同一个会话的五条回答下各挂了一张卡」就是这么来的。
     * <p>清空走 {@code list.clear()} 而不是从 map 里摘掉整个 list：并发写入方持有的还是同一个 list 实例，
     * 摘掉实例会让「结算瞬间正在落盘」的那条彻底丢失。
     */
    public List<Map<String, Object>> settleArtifacts(String sessionId) {
        return signAll(sessionId, true);
    }

    private List<Map<String, Object>> signAll(String sessionId, boolean consume) {
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
            if (consume) {
                list.clear();
            }
        }
        return out;
    }

    /** 当前会话是否仍在线（emitter 未被移除） */
    public boolean isSessionActive(String sessionId) {
        return sessionId != null && EMITTERS.containsKey(sessionId);
    }

    /**
     * 把一个会话的产物收编到另一个会话（P3a 子智能体分支）。
     * <p>为什么需要：全循环分支跑在<b>一条用完即删的临时会话</b>里（{@code chat()} 的轮级互斥按会话加锁，
     * 分支不能借父会话）。于是分支交付的产物按子会话落库、按子会话登记实时下发——子会话一删，
     * 用户手上只剩一句"已经生成好了"的文件，点开却是 404 的签名路径。收编就是把这三处一起改到父会话：
     * 库里的 session_id、内存里的会话产物清单、以及父轮 done 事件里那份汇总。
     * <p>实时 {@code artifact} 事件按父会话补发一次；父轮此刻还没登记 emitter（编排发生在生成流之前），
     * 发不出去也不报错——done 汇总那一份兜底，界面上产物卡片一定出现，只是不插在时间线中段。
     *
     * @return 收编的产物清单（含文件名与已签名 URL，供回报段展示"这一支交付了什么"）
     */
    public List<Map<String, Object>> adoptFromSession(String fromSessionId, String toSessionId) {
        if (fromSessionId == null || fromSessionId.isBlank()
                || toSessionId == null || toSessionId.isBlank()
                || fromSessionId.equals(toSessionId)) {
            return List.of();
        }
        List<Artifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getSessionId, fromSessionId)
                .eq(Artifact::getDeleted, 0)
                .orderByAsc(Artifact::getCreateTime));
        if (rows.isEmpty()) return List.of();
        int retentionDays = assetRetentionDays();
        List<Map<String, Object>> fromList = SESSION_ARTIFACTS.remove(fromSessionId);
        List<Map<String, Object>> toList = SESSION_ARTIFACTS.computeIfAbsent(toSessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Artifact row : rows) {
            artifactMapper.update(null, new LambdaUpdateWrapper<Artifact>()
                    .eq(Artifact::getId, row.getId())
                    .set(Artifact::getSessionId, toSessionId));
            // 内存清单按 id 搬过去（保留原始 URL，下发时再签）；搬不到（例如写入时不在问答链路内）
            // 就按库里的行重建一份——宁可多算一次，也不让产物从父轮的汇总里静默消失
            Map<String, Object> entry = null;
            if (fromList != null) {
                synchronized (fromList) {
                    for (Iterator<Map<String, Object>> it = fromList.iterator(); it.hasNext(); ) {
                        Map<String, Object> e = it.next();
                        if (row.getId().equals(String.valueOf(e.get("id")))) {
                            entry = e;
                            it.remove();
                            break;
                        }
                    }
                }
            }
            if (entry == null) {
                entry = new LinkedHashMap<>();
                entry.put("id", row.getId());
                entry.put("url", "/ai/" + row.getObjectKey());
                entry.put("filename", row.getFilename());
                entry.put("ext", row.getExt());
                entry.put("preview", PreviewTypes.kindOf(row.getExt()));
                entry.put("size", row.getSize());
                entry.put("description", row.getDescription() == null ? "" : row.getDescription());
            }
            toList.add(entry);
            Map<String, Object> info = toDto(row, retentionDays);
            out.add(info);
            publish(toSessionId, "artifact", JSON.toJSONString(info));
        }
        log.info("[ARTIFACT] 产物收编 {} 个：{} → {}", out.size(), fromSessionId, toSessionId);
        return out;
    }

    /**
     * 生成产物：写盘 + 落库（归属取会话的 user_id），返回可访问（已签名）的 URL。
     * 文件名为空/非法/扩展名不在白名单时抛 IllegalArgumentException（fail-loud，调用方转为工具错误返回）。
     *
     * @return { id, url, filename, ext, preview, size, description }
     */
    public Map<String, Object> write(String sessionId, String filename, String content, String description) {
        return writeBytes(sessionId, filename,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8), description, ALLOWED_EXTS);
    }

    /**
     * 字节级产物写入（沙盒文件交付 {@code deliver_artifact} 用）：与文本产物同一套落盘/登记/推送约定，
     * 差异仅在扩展名白名单放宽到沙盒典型输出（py/png/xlsx/zip 等），大小上限同为 1MB。
     *
     * @return { id, url, filename, ext, preview, size, description }
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
            throw new IllegalArgumentException("产物文件名不合法或扩展名不支持（支持 "
                    + PreviewTypes.extListHint(allowedExts) + "）");
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
        // 工作区归属：由会话反推（sessionId 这里已在手，不需要改任何方法签名）。
        // 解析不到 = 没挂工作区（null 是一等状态），不影响落库与下载。
        row.setProjectId(projectService.projectIdOfSession(sessionId));
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
        info.put("preview", PreviewTypes.kindOf(ext));
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

    /**
     * 我的产物列表（按 uid 归属；keyword 匹配文件名/显示名/说明）。
     * <p>url 已按需签名；expireTime 按当前全局保留天数动态算出。
     *
     * @param projectId 可选：只看某个工作区的产物。{@code null}=不限工作区（现状行为）；
     *                  传 {@code "__unassigned__"} 只看没挂工作区的（存量产物）。
     */
    public List<Map<String, Object>> list(String uid, String keyword, String projectId) {
        return list(uid, keyword, projectId, null, null, null, null);
    }

    /** 保持原签名（工作区过滤是新增可选维度，不该逼所有调用方改） */
    public List<Map<String, Object>> list(String uid, String keyword) {
        return list(uid, keyword, null);
    }

    /**
     * 「我的产物」完整查询：关键词 + 工作区 + 类型 + 时间范围 + 即将清理 + 排序。
     *
     * <p>过滤一律在服务端做（产物可能很多，前端筛会把整表拉回来）。排序只接受白名单，
     * 不把前端字符串拼进 ORDER BY。
     *
     * @param kind        page/doc/sheet/image/code/archive/other；空或 all = 不限
     * @param timeRange   7d/30d/90d/mtd；空或 all = 不限
     * @param expiring    只看 7 天内将被清理的产物（保留天数 ≤0 时天然为空）
     * @param sort        time_desc（默认）/ time_asc / size_desc / size_asc / name_asc
     */
    public List<Map<String, Object>> list(String uid, String keyword, String projectId,
                                          String kind, String timeRange, Boolean expiring, String sort) {
        if (uid == null || uid.isBlank()) return List.of();
        int retentionDays = assetRetentionDays();
        if (Boolean.TRUE.equals(expiring) && retentionDays <= 0) return List.of();
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Artifact> w = new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getUid, uid)
                .eq(Artifact::getDeleted, 0);

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // 说明里常有关键词，只搜文件名会漏；三者按「或」组合，外层仍与本查询其它条件是「与」
            w.and(q -> q.like(Artifact::getFilename, kw)
                    .or().like(Artifact::getDisplayName, kw)
                    .or().like(Artifact::getDescription, kw));
        }
        if (UNASSIGNED_PROJECT.equals(projectId)) {
            w.isNull(Artifact::getProjectId);
        } else if (projectId != null && !projectId.isBlank()) {
            w.eq(Artifact::getProjectId, projectId);
        }
        if (kind != null && !kind.isBlank() && !"all".equalsIgnoreCase(kind)) {
            String k = kind.trim().toLowerCase(Locale.ROOT);
            if ("other".equals(k)) {
                // ext 为 NULL 或不在任何已知分组里；NOT IN 对 NULL 不成立，所以先显式兜住 NULL
                w.and(q -> q.isNull(Artifact::getExt)
                        .or().notIn(Artifact::getExt, PreviewTypes.ARTIFACT_KIND_EXTS));
            } else {
                Set<String> exts = PreviewTypes.extsOfKind(k);
                if (exts.isEmpty()) return List.of();
                w.in(Artifact::getExt, exts);
            }
        }
        LocalDateTime start = timeRangeStart(timeRange, now);
        if (start != null) w.ge(Artifact::getCreateTime, start);
        if (Boolean.TRUE.equals(expiring)) {
            // 到期时间 = create_time + retentionDays；未来 7 天内到期 ⇒ create_time ≤ now+7-retention。
            // 已过期的（清理任务还没跑到）不算「即将清理」，否则概览数字会一直虚高
            LocalDateTime earliest = now.minusDays(retentionDays);
            LocalDateTime deadline = now.plusDays(7).minusDays(retentionDays);
            w.ge(Artifact::getCreateTime, earliest).le(Artifact::getCreateTime, deadline);
        }

        String sortKey = sortKey(sort);
        switch (sortKey) {
            case "time_asc" -> w.orderByAsc(Artifact::getCreateTime);
            case "size_desc" -> w.orderByDesc(Artifact::getSize).orderByDesc(Artifact::getCreateTime);
            case "size_asc" -> w.orderByAsc(Artifact::getSize).orderByDesc(Artifact::getCreateTime);
            case "name_asc" -> { /* 名称要按显示名（display_name 可能为 NULL），查出后内存排序 */ }
            default -> w.orderByDesc(Artifact::getCreateTime);
        }

        List<Artifact> rows = artifactMapper.selectList(w);
        if ("name_asc".equals(sortKey)) {
            rows.sort(Comparator.comparing(ArtifactService::displayNameOf, String.CASE_INSENSITIVE_ORDER));
        }
        Map<String, String> sessionTitles = sessionTitles(rows);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Artifact row : rows) {
            out.add(toDto(row, retentionDays, sessionTitles));
        }
        return out;
    }

    /** 时间范围起点；null = 不限 */
    private static LocalDateTime timeRangeStart(String timeRange, LocalDateTime now) {
        if (timeRange == null || timeRange.isBlank() || "all".equalsIgnoreCase(timeRange)) return null;
        return switch (timeRange.trim().toLowerCase(Locale.ROOT)) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            case "mtd" -> LocalDate.now().withDayOfMonth(1).atStartOfDay();
            default -> null;
        };
    }

    /** 排序白名单：只认五个枚举，其余一律回落到最新优先 */
    private static String sortKey(String sort) {
        if (sort == null) return "time_desc";
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "time_asc", "size_desc", "size_asc", "name_asc" -> sort.trim().toLowerCase(Locale.ROOT);
            default -> "time_desc";
        };
    }

    /** 批量取会话标题：去重后一次 selectBatchIds，避免逐行查会话造成 N+1 */
    private Map<String, String> sessionTitles(List<Artifact> rows) {
        Set<String> ids = new HashSet<>();
        for (Artifact row : rows) {
            if (row.getSessionId() != null && !row.getSessionId().isBlank()) ids.add(row.getSessionId());
        }
        if (ids.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Session s : sessionMapper.selectBatchIds(ids)) {
                if (s != null && s.getId() != null) {
                    out.put(s.getId(), s.getTitle() == null ? "" : s.getTitle());
                }
            }
        } catch (Exception e) {
            // 标题只是辅助信息；查不到就退化成「来源会话已删除」，不影响列表本身
            log.warn("[ARTIFACT] 会话标题批量读取失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 某个会话在指定时刻之后生成的产物（目标模式的进展检测与证据自动收集）。
     * <p>只按 uid 校验归属，不暴露别人的产物；{@code since} 为空则不限时间。
     */
    public List<Artifact> listBySession(String uid, String sessionId, LocalDateTime since) {
        if (uid == null || uid.isBlank() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            LambdaQueryWrapper<Artifact> w = new LambdaQueryWrapper<Artifact>()
                    .eq(Artifact::getUid, uid)
                    .eq(Artifact::getSessionId, sessionId)
                    .eq(Artifact::getDeleted, 0)
                    .orderByAsc(Artifact::getCreateTime);
            if (since != null) w.ge(Artifact::getCreateTime, since);
            return artifactMapper.selectList(w);
        } catch (Exception e) {
            log.warn("[ARTIFACT] 按会话读取产物失败 session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 没挂工作区这一档的查询占位符（比空串更明确：null 在 MyBatis wrapper 里会被当成"不传"） */
    public static final String UNASSIGNED_PROJECT = "__unassigned__";

    /**
     * 移动单个产物到另一个工作区，或摘掉归属（{@code projectId} 空/{@code __none__}）。
     *
     * <p>产物归用户不归会话，但工作区归属是"从哪个工作区交出来的"这件事的唯一记录——
     * 用户在产物页整理时要有改的机会（归错比没归更难纠正：用户没法知道系统分对了没有）。
     * <p>归档区可作为目标（整理动作）；校验口径与{@link ProjectService#resolveMoveTarget} 同一份。
     */
    public void moveProject(String uid, String artifactId, String projectId) {
        Artifact row = getOwned(artifactId, uid);   // 不存在/他人/已删在这里 fail-loud
        if (row == null) {
            throw new IllegalArgumentException("产物不存在或已删除");
        }
        String target = projectService.resolveMoveTarget(uid, projectId);
        if (Objects.equals(target, row.getProjectId())) return;
        // 置空必须走 wrapper 显式 set：updateById 的 NOT_NULL 策略会跳过 null 列
        artifactMapper.update(null, new LambdaUpdateWrapper<Artifact>()
                .eq(Artifact::getId, artifactId)
                .set(Artifact::getProjectId, target));
        log.info("[ARTIFACT] 产物移动工作区 uid={} id={} {} → {}", uid, artifactId, row.getProjectId(), target);
    }

    /**
     * 批量移动产物到同一个工作区。逐条复用归属校验，返回 moved/skipped；
     * 目标工作区先解析一次——目标本身不合法时整批 400，不产生「部分移动」的半成品。
     */
    public Map<String, Object> moveProjectBatch(String uid, List<String> ids, String projectId) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("未选择要移动的产物");
        }
        projectService.resolveMoveTarget(uid, projectId);   // 目标不合法先 fail-loud
        int moved = 0;
        List<String> skipped = new ArrayList<>();
        for (String id : ids) {
            try {
                moveProject(uid, id, projectId);
                moved++;
            } catch (RuntimeException e) {
                skipped.add(id);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * 重命名产物：只改 {@code display_name}，不动 filename / objectKey / 签名地址。
     * <p>空白（null / 空串 / 纯空格）= 清空显示名，回退到原文件名去后缀。
     */
    public Map<String, Object> rename(String uid, String artifactId, String displayName) {
        Artifact row = getOwned(artifactId, uid);
        if (row == null) throw new IllegalArgumentException("产物不存在或已删除");
        String name = normalizeDisplayName(displayName, row.getExt());
        artifactMapper.update(null, new LambdaUpdateWrapper<Artifact>()
                .eq(Artifact::getId, artifactId)
                .set(Artifact::getDisplayName, name));
        row.setDisplayName(name);
        log.info("[ARTIFACT] 重命名 uid={} id={} displayName={}", uid, artifactId, name);
        // 返回值带上来源会话标题：调用方若直接用它替换行数据，来源 chip 不会变成「会话已删除」
        return toDto(row, assetRetentionDays(), sessionTitles(Collections.singletonList(row)));
    }

    /**
     * 「我的产物」概览统计：全局口径（不随列表筛选变化）。
     * <p>一次查出该用户的未删除产物后在内存聚合：数量小、口径与列表完全同源，
     * 也避免为每个指标各写一条 SQL 后出现「概览说 23、列表共 22」。
     */
    public Map<String, Object> stats(String uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (uid == null || uid.isBlank()) {
            out.put("count", 0);
            out.put("totalSize", 0L);
            out.put("expiring7", 0);
            out.put("expiring30", 0);
            out.put("monthAdded", 0);
            out.put("byKind", Map.of());
            return out;
        }
        int retentionDays = assetRetentionDays();
        List<Artifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getUid, uid)
                .eq(Artifact::getDeleted, 0));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long totalSize = 0;
        int expiring7 = 0;
        int expiring30 = 0;
        int monthAdded = 0;
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (String k : List.of("page", "doc", "sheet", "image", "code", "archive", "other")) {
            byKind.put(k, 0);
        }
        for (Artifact row : rows) {
            totalSize += row.getSize() == null ? 0 : Math.max(0, row.getSize());
            String kind = PreviewTypes.artifactKindOf(row.getExt());
            byKind.merge(kind, 1, Integer::sum);
            LocalDateTime created = row.getCreateTime();
            if (created == null) continue;
            if (!created.isBefore(monthStart)) monthAdded++;
            if (retentionDays > 0) {
                LocalDateTime expire = created.plusDays(retentionDays);
                if (!expire.isBefore(now) && !expire.isAfter(now.plusDays(7))) expiring7++;
                if (!expire.isBefore(now) && !expire.isAfter(now.plusDays(30))) expiring30++;
            }
        }
        out.put("count", rows.size());
        out.put("totalSize", totalSize);
        out.put("expiring7", expiring7);
        out.put("expiring30", expiring30);
        out.put("monthAdded", monthAdded);
        out.put("byKind", byKind);
        return out;
    }

    /** 打包下载的一条条目（file 已确认存在） */
    public record ZipItem(Path file, String entryName) {
    }

    /** 打包下载计划：先校验与收集，再交给流式写出 */
    public record ZipPlan(List<ZipItem> items, long totalBytes) {
    }

    /**
     * 批量下载前置校验：逐条取回属于本人的产物并确认文件仍在磁盘，收集成 ZIP 计划。
     *
     * <p><b>先校验后开流</b>：一旦开始写 ZIP 响应头，中途失败只能给用户一个损坏的包；
     * 所以这里把所有「不存在/已删/无权/文件已清理/超限」都在开流前处理掉。
     * <p>取不回的条目静默跳过（与 refresh-sign 同一口径，不泄露他人产物是否存在）；
     * 一个都取不回时抛可读错误，由控制器返回 400。
     */
    public ZipPlan prepareZip(String uid, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("未选择要下载的产物");
        }
        if (ids.size() > MAX_ZIP_ITEMS) {
            throw new IllegalArgumentException("一次最多下载 " + MAX_ZIP_ITEMS + " 个产物，请分批操作");
        }
        Path root = baseDir();
        List<ZipItem> items = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        long total = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            Artifact row;
            try {
                row = getOwned(id, uid);
            } catch (IllegalArgumentException e) {
                continue;   // 他人的产物：跳过，不暴露存在性
            }
            if (row == null || row.getObjectKey() == null || row.getObjectKey().isBlank()) continue;
            Path file = root.resolve(row.getObjectKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            long size = row.getSize() == null ? 0 : Math.max(0, row.getSize());
            total += size;
            if (total > MAX_ZIP_BYTES) {
                throw new IllegalArgumentException("所选产物超过 100MB 上限，请分批下载");
            }
            items.add(new ZipItem(file, uniqueZipName(zipNameOf(row), usedNames)));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("所选产物已不存在或文件已被清理");
        }
        return new ZipPlan(items, total);
    }

    /** 流式写出 ZIP：逐个文件复制，不把整包读进内存 */
    public void writeZip(ZipPlan plan, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipItem item : plan.items()) {
                zip.putNextEntry(new ZipEntry(item.entryName()));
                Files.copy(item.file(), zip);
                zip.closeEntry();
            }
        }
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
     * <p><b>为什么需要</b>：签名 URL 有效期固定 1 小时（{@code IMAGES_AUTH_EXPIRE_SECONDS=3600}），
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
     * 读一份产物的<b>原文</b>，给预览台里「读字」那一档（Markdown / 代码 / 分隔符表）用。
     *
     * <p><b>入参是产物 id，不是路径也不是 URL</b>：与 {@link #refreshSignatures} 同一口径——
     * 给路径就等于开放「读任意文件」的能力（产物根目录之外也在内）。id 归属在库里逐条 fail-closed。
     *
     * <p><b>类型闸在 {@link PreviewTypes}（服务端那一份）</b>：xlsx/zip 这类不按文本读，
     * 前端也不该来问；两边清单对不上时前端会拿到一句人话的错误而不是半截二进制。
     *
     * <p><b>只读前 {@link PreviewTypes#TEXT_BODY_CAP} 字节</b>：产物本身有 1MB 落盘帽，
     * 但一次「看一眼」不该把 1MB 塞进 markdown-it；截断由前端显式说明并引导下载。
     *
     * @return { filename, ext, size, cap, truncated, content }
     * @throws IllegalArgumentException 产物不存在/已删/无权/类型不支持文本预览/文件已被清理
     */
    public Map<String, Object> readContent(String uid, String artifactId) {
        Artifact row = getOwned(artifactId, uid);
        if (row == null) {
            throw new IllegalArgumentException("产物不存在或已删除");
        }
        String ext = PreviewTypes.extOf(row.getFilename());
        if (!PreviewTypes.isReadable(ext)) {
            throw new IllegalArgumentException("该产物不按文本预览，请下载后查看");
        }
        Path file = baseDir().resolve(row.getObjectKey()).normalize();
        if (!file.startsWith(baseDir()) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("产物文件已被清理，无法预览");
        }
        try {
            long size = Files.size(file);
            byte[] buf = new byte[(int) Math.min(size, PreviewTypes.TEXT_BODY_CAP)];
            int got;
            try (java.io.InputStream in = Files.newInputStream(file)) {
                got = in.readNBytes(buf, 0, buf.length);
            }
            boolean truncated = size > buf.length;
            String content = new String(buf, 0, got, StandardCharsets.UTF_8);
            // 截在半个汉字中间时末尾会剩一个替换符，留着像文件坏了
            if (truncated && !content.isEmpty()
                    && content.charAt(content.length() - 1) == '\uFFFD') {
                content = content.substring(0, content.length() - 1);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", row.getFilename());
            m.put("ext", ext);
            m.put("preview", PreviewTypes.kindOf(ext));
            m.put("size", size);
            m.put("cap", (long) PreviewTypes.TEXT_BODY_CAP);
            m.put("truncated", truncated);
            m.put("content", content);
            return m;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取产物失败：" + e.getMessage());
        }
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
     * 产物保留天数（用户资产档 {@code cleanup.assetRetentionDays}，≤0 = 不清理）。
     * <p>界面（列表里的「有效期至」）与清理任务都读这一个数——分两处算就会出现「写着还有 30 天、
     * 实际昨天就被删了」。
     */
    private int assetRetentionDays() {
        return configService.getInt("cleanup.assetRetentionDays",
                ConfigDefaults.CLEANUP_ASSET_RETENTION_DAYS);
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
        // 总线优先：本轮的执行与那条通道已经解耦，产物卡片要送到「此刻挂着的每个观众」——
        // 只发登记时那条 emitter，刷新/换设备接流回来的人会缺一整段产物（done 才补上，中间像丢了东西）
        if (chatRunBus.publishToSession(sessionId, eventType, payload)) return;
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
        return toDto(row, retentionDays, null);
    }

    /**
     * 列表 DTO。加了三样列表页需要、但库里没有现成列的东西：
     * displayName（重命名的显示名，没重命名就回退文件名去后缀）、kind（类型分组）、
     * sessionTitle/sessionExists（来源会话标题与是否还在）。
     */
    private Map<String, Object> toDto(Artifact row, int retentionDays, Map<String, String> sessionTitles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("filename", row.getFilename());
        m.put("originalFilename", row.getFilename());
        m.put("displayName", displayNameOf(row));
        m.put("ext", row.getExt());
        m.put("kind", PreviewTypes.artifactKindOf(row.getExt()));
        m.put("preview", PreviewTypes.kindOf(row.getExt()));
        m.put("size", row.getSize());
        m.put("description", row.getDescription() == null ? "" : row.getDescription());
        m.put("sessionId", row.getSessionId());
        String sid = row.getSessionId();
        if (sid == null || sid.isBlank() || sessionTitles == null) {
            m.put("sessionTitle", null);
            m.put("sessionExists", false);
        } else {
            m.put("sessionTitle", sessionTitles.get(sid));
            m.put("sessionExists", sessionTitles.containsKey(sid));
        }
        // 工作区归属：null=没挂工作区（存量产物）。前端据此分组；**不**把 null 归进任何工作区，
        // 因为"错误归属"比"没挂工作区"更难纠正（用户没法知道系统把哪份文件分错了）
        m.put("projectId", row.getProjectId());
        m.put("createTime", row.getCreateTime() == null ? null : row.getCreateTime().toString());
        // 预计清理时间：不落库、按当前全局保留天数即时算，保证与 cleanupExpired 口径同源
        // （落库会在管理员改 retentionDays 后变成陈旧数据）；retentionDays ≤ 0 = 永不清理 → null
        m.put("expireTime", expireTimeOf(row, retentionDays));
        m.put("url", imageUrlSigner.signUrl("/ai/" + row.getObjectKey()));
        return m;
    }

    /** 列表展示名：用户重命名优先；否则文件名去掉扩展名（扩展名由类型 chip 单独展示） */
    private static String displayNameOf(Artifact row) {
        String dn = row.getDisplayName();
        if (dn != null && !dn.isBlank()) return dn;
        return stripExt(row.getFilename());
    }

    /** 去掉最后一个扩展名；无扩展名或点是首字符时原样返回 */
    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 重命名显示名：去控制字符、去尾部扩展名（前端不允许改扩展名，用户粘进来时自动剥掉）、限长。
     * 返回 null = 清空显示名（回退原文件名）。
     */
    private static String normalizeDisplayName(String raw, String ext) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\p{Cntrl}", "").trim();
        if (s.isEmpty()) return null;
        if (ext != null && !ext.isBlank()) {
            String suffix = "." + ext.toLowerCase(Locale.ROOT);
            if (s.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length()).trim();
            }
        }
        if (s.isEmpty()) return null;
        return s.length() <= MAX_DISPLAY_NAME_LEN ? s : s.substring(0, MAX_DISPLAY_NAME_LEN);
    }

    /** ZIP 条目名：显示名（或原名去后缀）+ 扩展名；路径分隔符/控制字符替换成下划线 */
    private static String zipNameOf(Artifact row) {
        String base = row.getDisplayName() != null && !row.getDisplayName().isBlank()
                ? row.getDisplayName() : stripExt(row.getFilename());
        String ext = row.getExt();
        String name = base + (ext == null || ext.isBlank() ? "" : "." + ext);
        name = name.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return "artifact";
        return name.length() <= 120 ? name : name.substring(0, 120);
    }

    /** 同名条目自动加 (1)(2)…，避免解压时互相覆盖 */
    private static String uniqueZipName(String name, Set<String> used) {
        if (!used.contains(name)) {
            used.add(name);
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 1;
        String candidate;
        do {
            candidate = base + "(" + n + ")" + ext;
            n++;
        } while (used.contains(candidate));
        used.add(candidate);
        return candidate;
    }

    /** 预计清理时间（ISO 字符串）；永不清理或无创建时间返回 null。 */
    private static String expireTimeOf(Artifact row, int retentionDays) {
        if (retentionDays <= 0 || row.getCreateTime() == null) return null;
        return row.getCreateTime().plusDays(retentionDays).toString();
    }

    private Path baseDir() {
        return Paths.get(properties.getImages().getDir()).toAbsolutePath().normalize();
    }

    /** 该成员的产物分片根（{@code {images.dir}/artifacts/{uid}}）——DR 清单按租户枚举时用 */
    public Path userArtifactDir(String uid) {
        return baseDir().resolve("artifacts").resolve(com.wenqu.ai.sandbox.SandboxPaths.uidDirname(uid));
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
