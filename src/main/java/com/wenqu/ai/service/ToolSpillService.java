package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 工具大输出的<b>外溢存储</b>（spill）：展示层不再「截断即丢」。
 *
 * <p>要解决的问题：一条工具结果（沙盒 {@code execute} 的输出、{@code read_file} 读到的整份文件、
 * MCP 返回的一大坨 JSON）远比能塞进气泡的量多。此前超过 8KB 就直接砍尾——卡片上写着结果、
 * 尾部却什么都没有，用户既不知道少了多少，也没地方去看剩下的部分；排查「智能体为什么这么答」时
 * 这份原文恰恰是唯一证据。
 *
 * <p>做法：超限的<b>完整原文</b>落到 {@code data/tool-spill/<uid哈希>/<id>.txt}，随消息与 SSE 只留
 * 预览加一个 {@code spillId}，卡片给「查看完整输出」按需读回来（读取按人隔离：id 是 32 位 hex，
 * 归属写在同名 {@code .json} 里，非本人一律查不到）。写失败退回原样截断——这是旁路，绝不影响问答。
 *
 * <p>刻意不做（基础版的边界，别当成已经做完）：
 * <ul>
 *   <li><b>模型侧的输出上限不动</b>（沙盒 262144 字节、工具侧截断进上下文那一段）——那是上下文预算问题，
 *       归属「历史投影切换 / PTC」那条线，动它会改变模型看到的内容形状；本服务只管<b>给人看的那一份</b>；</li>
 *   <li>不做服务端检索/高亮（原表述里的「可搜」由前端在已读回的全文里做关键字过滤，够用且不引入索引）；</li>
 *   <li>不落数据库：原文可能几 MB，进库会把消息表拖肥，磁盘 + 保留期清理更合适。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ToolSpillService {

    /** 存放子目录（相对 data 根，与产物、聊天附件同一棵目录树） */
    private static final String SUB_DIR = "tool-spill";
    /** id 形态：32 位无横线 hex。读取前必须校验，否则 id 会变成路径穿越入口 */
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    /** 单份外溢原文的落盘上限（字符）：再大的只留前段并在 meta 里标 outOfBand，避免一个异常工具写穿磁盘 */
    private static final int MAX_STORED_CHARS = 4 * 1024 * 1024;
    /** 读回给前端的体积上限：气泡里贴几 MB 文本浏览器会卡，超出按 outOfBand 说明 */
    private static final int MAX_READ_CHARS = 512 * 1024;

    private final AppProperties properties;
    private final ConfigService configService;

    public ToolSpillService(AppProperties properties, ConfigService configService) {
        this.properties = properties;
        this.configService = configService;
    }

    /**
     * 外溢一份工具原文。
     *
     * @return spillId；开关关闭、内容为空或落盘失败时返回 null（调用方按原有「截断展示」继续）
     */
    public String spill(String sessionId, String uid, String toolName, String text) {
        if (!enabled() || text == null || text.isEmpty() || uid == null || uid.isBlank()) return null;
        try {
            String id = UUID.randomUUID().toString().replace("-", "");
            Path dir = dirOf(uid);
            Files.createDirectories(dir);
            boolean clipped = text.length() > MAX_STORED_CHARS;
            String stored = clipped ? text.substring(0, MAX_STORED_CHARS) : text;
            Path file = dir.resolve(id + ".txt");
            // CREATE_NEW：同名即失败（id 是随机的，撞上等于有 bug），宁可报错也不要静默盖掉一份已有原文
            Files.writeString(file, stored, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            JSONObject meta = new JSONObject();
            meta.put("tool", toolName == null ? "" : toolName);
            meta.put("session", sessionId == null ? "" : sessionId);
            meta.put("uid", uid);
            meta.put("chars", text.length());
            meta.put("stored", stored.length());
            meta.put("outOfBand", clipped);
            meta.put("at", System.currentTimeMillis());
            Files.writeString(dir.resolve(id + ".json"), meta.toJSONString(), StandardCharsets.UTF_8);
            return id;
        } catch (Exception e) {
            log.warn("[SPILL] 工具原文外溢失败（退回截断展示）: tool={} {}", toolName, e.getMessage());
            return null;
        }
    }

    /**
     * 按 id 读回原文（仅归属人可读）。
     *
     * @return {@code {tool,size,chars,content,truncated,outOfBand}}；不存在/非本人/开关关闭返回 null
     */
    public Map<String, Object> read(String id, String uid) {
        if (id == null || !ID.matcher(id).matches() || uid == null || uid.isBlank()) return null;
        try {
            Path file = dirOf(uid).resolve(id + ".txt");
            Path metaFile = dirOf(uid).resolve(id + ".json");
            if (!Files.isRegularFile(file) || !Files.isRegularFile(metaFile)) return null;
            JSONObject meta = JSON.parseObject(Files.readString(metaFile, StandardCharsets.UTF_8));
            if (meta == null || !uid.equals(meta.getString("uid"))) return null;   // 目录已按 uid 哈希隔离，这里再校验一次归属
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean overReadCap = content.length() > MAX_READ_CHARS;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("tool", meta.getString("tool") == null ? "" : meta.getString("tool"));
            out.put("chars", meta.getIntValue("chars", content.length()));
            out.put("size", content.length());
            out.put("content", overReadCap ? content.substring(0, MAX_READ_CHARS) : content);
            out.put("truncated", overReadCap);
            out.put("outOfBand", meta.getBooleanValue("outOfBand", false));
            return out;
        } catch (Exception e) {
            log.warn("[SPILL] 读取外溢原文失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 过期清理（ScheduleCenter 周期调用）：按文件修改时间删超过保留期的原文与其元信息 */
    public int purgeExpired() {
        int days = configService.getInt("cleanup.toolSpillRetentionDays", 7);
        if (days <= 0) return 0;
        long deadline = System.currentTimeMillis() - days * 24L * 3600_000L;
        Path root = Paths.get(properties.getImages().getDir(), SUB_DIR);
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        List<Path> doomed = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path userDir : dirs.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(userDir)) {
                    for (Path f : files.toList()) {
                        if (f.getFileName().toString().endsWith(".json")) continue;   // 元信息随原文一起删
                        if (Files.getLastModifiedTime(f).toMillis() > deadline) continue;
                        doomed.add(f);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SPILL] 外溢目录遍历失败（下轮重试）: {}", e.getMessage());
            return 0;
        }
        for (Path f : doomed) {
            try {
                String name = f.getFileName().toString();
                Files.deleteIfExists(f);
                Files.deleteIfExists(f.resolveSibling(name + ".json"));
                removed++;
            } catch (Exception ignored) {
                // 单个删不掉不影响其余（下轮再试）
            }
        }
        if (removed > 0) log.info("[SPILL] 已清理过期工具外溢原文 {} 份（保留 {} 天）", removed, days);
        return removed;
    }

    /** 总开关：关掉后不再外溢，卡片回到「截断展示」，已落的原文仍可读直到保留期结束 */
    public boolean enabled() {
        return configService.getBoolean("tool.spillEnabled", true);
    }

    private Path dirOf(String uid) {
        return Paths.get(properties.getImages().getDir(), SUB_DIR, hashUid(uid));
    }

    /** 目录名 = uid 的 SHA-256 前 16 位（uid 可能含中文/特殊字符，直接做目录名会带来路径与编码坑） */
    private static String hashUid(String uid) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(uid.hashCode());
        }
    }
}
