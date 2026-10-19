package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
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
 * <p>占用的磁盘由两道治理收住：保留期（{@code cleanup.toolSpillRetentionDays}）删过期的，
 * 每人配额（{@code tool.spillMaxMbPerUser}）在写入后把该用户目录压回上限内、按落盘时间淘汰最旧的。
 * 被淘汰只影响「查看完整输出」能否读回原文，卡片上的预览与这一轮的结论不受影响。
 *
 * <p>读回侧的两句实话：一次最多给 {@value #MAX_READ_CHARS} 字符量级的<b>一段</b>（超出用 {@code next} 续读，
 * 见 {@link #read(String, String, int, int)}），检索走 {@link #search(String, String, String)}——
 * 它扫的是盘上那份<b>整份</b>原文，所以「那句报错在 300 万字的日志里」这种找法不依赖前端已经读到哪儿。
 *
 * <p>刻意不做（不是没做完）：
 * <ul>
 *   <li><b>模型侧的输出上限不动</b>（沙盒那道帽、工具侧截断进上下文那一段）——那是上下文预算问题，
 *       归属「历史投影切换 / PTC」那条线，动它会改变模型看到的内容形状；本服务只管<b>给人看的那一份</b>；</li>
 *   <li>不落数据库：原文可能几 MB，进库会把消息表拖肥，磁盘 + 保留期清理更合适；</li>
 *   <li>不建倒排索引：一次扫描换一次「查那句报错」，量级够用（盘上有 4M 字符的落盘上限兜着）。</li>
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
    /** 每人配额默认值（MB）：与业务上传上限同一量级，够排障用又不至于让一个账号吃穿磁盘 */
    private static final int DEFAULT_QUOTA_MB = 200;

    private final AppProperties properties;
    private final ConfigService configService;

    public ToolSpillService(AppProperties properties, ConfigService configService) {
        this.properties = properties;
        this.configService = configService;
    }

    /**
     * 外溢一份工具原文。
     *
     * @return spillId；内容为空或落盘失败时返回 null（调用方按原有「截断展示」继续）
     */
    public String spill(String sessionId, String uid, String toolName, String text) {
        if (text == null || text.isEmpty() || uid == null || uid.isBlank()) return null;
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
            enforceQuota(dir, id);
            return id;
        } catch (Exception e) {
            log.warn("[SPILL] 工具原文外溢失败（退回截断展示）: tool={} {}", toolName, e.getMessage());
            return null;
        }
    }

    /**
     * 按 id 读回原文（仅归属人可读），整份一次给到就读满 {@link #MAX_READ_CHARS}。
     *
     * @return {@code {tool,size,chars,content,truncated,outOfBand}}；不存在或非本人返回 null
     */
    public Map<String, Object> read(String id, String uid) {
        return read(id, uid, 0, MAX_READ_CHARS);
    }

    /**
     * 分段读回（仅归属人可读）：{@code offset} 是字符偏移，返回里的 {@code next} 给前端「继续读下一段」。
     * <p>为什么要有这一段：{@link #read(String, String)} 一次最多 512K 字，超过就在 512K 处又截一次——
     * 「查看完整输出」自己变成一个新的截断点，用户看得见「后面未包含」却没有第二个入口。
     * 磁盘上那份是全的，这里只是把读口的分段能力接上。
     */
    public Map<String, Object> read(String id, String uid, int offset, int limit) {
        if (id == null || !ID.matcher(id).matches() || uid == null || uid.isBlank()) return null;
        try {
            Path file = dirOf(uid).resolve(id + ".txt");
            Path metaFile = dirOf(uid).resolve(id + ".json");
            if (!Files.isRegularFile(file) || !Files.isRegularFile(metaFile)) return null;
            JSONObject meta = JSON.parseObject(Files.readString(metaFile, StandardCharsets.UTF_8));
            if (meta == null || !uid.equals(meta.getString("uid"))) return null;   // 目录已按 uid 哈希隔离，这里再校验一次归属
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int from = Math.max(0, Math.min(offset, content.length()));
            int size = Math.max(1, Math.min(limit <= 0 ? MAX_READ_CHARS : limit, MAX_READ_CHARS));
            int to = Math.min(content.length(), from + size);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("tool", meta.getString("tool") == null ? "" : meta.getString("tool"));
            out.put("chars", meta.getIntValue("chars", content.length()));      // 工具当时产出的整份字数（留存可能更短）
            out.put("size", content.length());                                   // 盘上这份的字数
            out.put("from", from);
            out.put("to", to);
            out.put("content", content.substring(from, to));
            out.put("more", to < content.length());
            out.put("next", to < content.length() ? to : -1);
            // 兼容基础版的字段名：老前端按 truncated 理解「这一次没读全」
            out.put("truncated", to < content.length());
            out.put("outOfBand", meta.getBooleanValue("outOfBand", false));
            return out;
        } catch (Exception e) {
            log.warn("[SPILL] 读取外溢原文失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 检索命中上限（条）：一次给太多屏幕也放不下，剩下的靠「还有 N 处」这句话交代 */
    private static final int MAX_SEARCH_HITS = 200;
    /** 单条命中回给的行文本上限（字符）：命中行可能是一整坨压缩后的 JSON，按行给会撑爆弹窗 */
    private static final int MAX_HIT_CHARS = 400;

    /**
     * 服务端检索（仅归属人可查）：在盘上那份<b>整份</b>原文里按行找关键字，返回命中行号与字符偏移。
     * <p>为什么要在服务端做：前端只能筛「已经读回来的那一段」，而一次读回上限 512K 字——
     * 一份 300 万字的日志里那句报错在第 280 万字处，前端筛什么都筛不到。行号 + 偏移让
     * 「命中 → 跳到那一段」这条链路闭合。
     *
     * @return {@code {tool,size,q,total,matches:[{line,offset,text}],scanned,limited}}；不存在/非本人返回 null
     */
    public Map<String, Object> search(String id, String uid, String q) {
        if (q == null || q.isBlank() || id == null || !ID.matcher(id).matches()
                || uid == null || uid.isBlank()) return null;
        Path file = dirOf(uid).resolve(id + ".txt");
        Path metaFile = dirOf(uid).resolve(id + ".json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(metaFile)) return null;
        JSONObject meta;
        try {
            meta = JSON.parseObject(Files.readString(metaFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[SPILL] 检索读元信息失败 id={}: {}", id, e.getMessage());
            return null;
        }
        if (meta == null || !uid.equals(meta.getString("uid"))) return null;   // 与 read() 同一条归属判据
        String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        int total = 0;
        long line = 0;
        int offset = 0;
        boolean limited = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String row;
            while ((row = reader.readLine()) != null) {
                line++;
                if (row.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    total++;
                    if (hits.size() < MAX_SEARCH_HITS) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("line", line);
                        hit.put("offset", offset);
                        hit.put("text", row.length() > MAX_HIT_CHARS ? row.substring(0, MAX_HIT_CHARS) + "…" : row);
                        hits.add(hit);
                    } else {
                        limited = true;
                    }
                }
                offset += row.length() + 1;   // +1 = 刚读掉的那个换行
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", meta.getString("tool") == null ? "" : meta.getString("tool"));
            out.put("chars", meta.getIntValue("chars", 0));
            out.put("size", meta.getIntValue("stored", 0));
            out.put("q", q.trim());
            out.put("total", total);
            out.put("lines", line);
            out.put("matches", hits);
            out.put("limited", limited);
            out.put("outOfBand", meta.getBooleanValue("outOfBand", false));
            return out;
        } catch (Exception e) {
            log.warn("[SPILL] 检索外溢原文失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 一次给的行数上限：一屏读得完，也把弹窗的 DOM 控制在几百个节点里 */
    private static final int MAX_WINDOW_LINES = 500;
    /** 跳转时给命中行留的上文行数（命中行不至于贴在窗口顶上） */
    private static final int CONTEXT_BEFORE_LINES = 20;

    /**
     * 按<b>行</b>窗口读回（仅归属人可读）：原文超过一次读回上限时，弹窗翻的是行段而不是字符段——
     * 按字符跳「第 12,345 行」只能靠滚动条估位置，按行跳才落得准。
     *
     * @param startLine 1-based 起始行（会自动往前留上文，见 {@code before}）
     * @param count     最多给几行（上限 {@value #MAX_WINDOW_LINES}）
     * @return {@code {tool,fromLine,toLine,lines,total,totalLines,content,before}}；不存在/非本人返回 null
     */
    public Map<String, Object> readLines(String id, String uid, int startLine, int count) {
        if (id == null || !ID.matcher(id).matches() || uid == null || uid.isBlank()) return null;
        Path file = dirOf(uid).resolve(id + ".txt");
        Path metaFile = dirOf(uid).resolve(id + ".json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(metaFile)) return null;
        int want = Math.max(1, Math.min(startLine, 1_000_000_000));
        int take = Math.max(1, Math.min(count <= 0 ? 200 : count, MAX_WINDOW_LINES));
        // 上文行数不能顶穿窗口本身：只要 6 行时留 20 行上文，窗口就整个落在命中行<b>之前</b>，
        // 点命中跳过去看到的是隔壁几行而不是那一行（2026-10-12 真机抓到，探针用 50 行没覆盖到这条）
        int before = Math.min(CONTEXT_BEFORE_LINES, (take - 1) / 2);
        int from = Math.max(1, want - before);
        int to = from + take - 1;
        List<String> window = new ArrayList<>();
        long totalLines = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String row;
            long line = 0;
            while ((row = reader.readLine()) != null) {
                line++;
                if (line >= from && line <= to) window.add(row);
            }
            totalLines = line;
            JSONObject meta = JSON.parseObject(Files.readString(metaFile, StandardCharsets.UTF_8));
            if (meta == null || !uid.equals(meta.getString("uid"))) return null;   // 与 read()/search() 同一条归属判据
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", meta.getString("tool") == null ? "" : meta.getString("tool"));
            out.put("chars", meta.getIntValue("chars", 0));
            out.put("size", meta.getIntValue("stored", 0));
            out.put("fromLine", window.isEmpty() ? 0 : from);
            out.put("toLine", window.isEmpty() ? 0 : from + window.size() - 1);
            out.put("lines", window.size());
            out.put("total", totalLines);
            out.put("totalLines", totalLines);
            out.put("content", String.join("\n", window));
            out.put("before", want - from);   // 命中行前面垫了几行上文（前端据此高亮与「贴顶但看得见上下文」）
            out.put("outOfBand", meta.getBooleanValue("outOfBand", false));
            return out;
        } catch (Exception e) {
            log.warn("[SPILL] 按行读回外溢原文失败 id={}: {}", id, e.getMessage());
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
        List<Path> orphanMeta = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path userDir : dirs.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(userDir)) {
                    for (Path f : files.toList()) {
                        String name = f.getFileName().toString();
                        if (name.endsWith(".json")) {
                            // 孤儿元信息：此前删除把 meta 拼成了「x.txt.json」，而写的是「x.json」——
                            // 也就是说保留期清理一直在留孤儿。原文没了一份 json 就没有任何读口，
                            // 这里顺手把旧账一次结清（新的删除已由 deleteWithMeta 走对名字）
                            if (isOrphanMeta(f)) orphanMeta.add(f);
                            continue;
                        }
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
                deleteWithMeta(f);
                removed++;
            } catch (Exception ignored) {
                // 单个删不掉不影响其余（下轮再试）
            }
        }
        for (Path f : orphanMeta) {
            try {
                if (Files.deleteIfExists(f)) removed++;
            } catch (Exception ignored) {
                // 同上
            }
        }
        if (removed > 0) log.info("[SPILL] 已清理过期工具外溢原文 {} 份（保留 {} 天，含历史遗留的孤儿元信息 {} 份）",
                removed, days, orphanMeta.size());
        return removed;
    }

    /** meta 有没有对应的原文：没有就是历史删除漏下的孤儿 */
    private static boolean isOrphanMeta(Path metaFile) {
        String name = metaFile.getFileName().toString();
        Path txt = metaFile.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".txt");
        return !Files.exists(txt);
    }

    /** 每人配额（MB）：0 或负数 = 不限量，与保留天数的「0 = 不清理」同一口径 */
    public int quotaMb() {
        return configService.getInt("tool.spillMaxMbPerUser", DEFAULT_QUOTA_MB);
    }

    /**
     * 写入后把该用户目录压回配额内：按落盘时间从最旧的开始淘汰。
     * <p>刚写入的那一份不参与淘汰——单份原文就可能大过整份配额，删掉它等于让这条能力对白开。
     * 淘汰只发生在排障副本上：卡片上的预览、这一轮的结论、消息本身都不受影响，
     * 只是那份原文再也读不回来（读口按「不存在」处理）。
     */
    private void enforceQuota(Path dir, String keepId) {
        int mb = quotaMb();
        if (mb <= 0) return;
        long limit = mb * 1024L * 1024L;
        List<Path> texts;
        try (var files = Files.list(dir)) {
            texts = files.filter(p -> p.getFileName().toString().endsWith(".txt")).toList();
        } catch (Exception e) {
            log.warn("[SPILL] 配额检查读目录失败（下轮写入再试）: {}", e.getMessage());
            return;
        }
        long total = 0;
        for (Path p : texts) total += sizeOrZero(p);
        if (total <= limit) return;
        List<Path> oldestFirst = texts.stream()
                .filter(p -> !p.getFileName().toString().equals(keepId + ".txt"))
                .sorted(java.util.Comparator.comparingLong(ToolSpillService::mtimeOrZero))
                .toList();
        int removed = 0;
        for (Path p : oldestFirst) {
            if (total <= limit) break;
            long size = sizeOrZero(p);
            try {
                deleteWithMeta(p);
                total -= size;
                removed++;
            } catch (Exception ignored) {
                // 单个删不掉不影响其余（下轮写入再试）
            }
        }
        if (removed > 0) {
            log.info("[SPILL] 超出每人配额：上限 {}MB，淘汰最旧原文 {} 份（目录 {}）", mb, removed, dir.getFileName());
        }
    }

    /** 原文与同名元信息一起删。meta 是「同一个 basename 换 .json 后缀」，不是 {@code x.txt.json} */
    private static void deleteWithMeta(Path file) throws java.io.IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(metaOf(file));
    }

    private static Path metaOf(Path txtFile) {
        String name = txtFile.getFileName().toString();
        return txtFile.resolveSibling(name.substring(0, name.length() - ".txt".length()) + ".json");
    }

    /** 清理任务与本类的淘汰会并发碰到同一个文件，读不到就当 0（宁可少删一轮，不抛穿出去） */
    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long mtimeOrZero(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0;
        }
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
