package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.service.ConfigService;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionExportSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 框架会话事件日志的<b>归档出口</b>（接框架的 {@code SessionExportSink}）。
 *
 * <p><b>为什么用框架这条通道而不是自己拷日志目录</b>：框架把导出做成了一条<b>带持久游标的 outbox</b>——
 * {@code JournalSessionLog} 为每个 sink 单独存一份水位（{@code exports/<hash(sink名)>.json}），
 * {@code SessionLogExporter#drain} 只送水位之后的增量、送成功才推水位。于是这三件事都是白拿的：
 * 崩溃后从原水位重投（至少一次，重投事件不重跑执行）、每轮心跳与 flush 顺带补送、以及不用自己猜
 * 「哪些搬过了」。自己拷 {@code ~/.wenqu/agent-journal} 就得重造这套游标，还造不稳。
 *
 * <p><b>它解决的具体问题</b>：会话状态与事件日志落宿主本地目录（{@code LocalJournalStorage}），
 * 换机器/重建容器就等于丢历史，而这份日志是唯一能回答「那一轮到底发生了什么」的东西——正文库里也有，
 * 工具入参、思考段、待办与裁决的时刻<b>只在日志里</b>。归档把它按会话摊成一份可搬运、可 grep 的 JSONL。
 *
 * <p><b>刻意不做</b>：
 * <ul>
 *   <li>不截断在线日志——框架的 journal SPI 没有截断入口（只有 {@code SessionMigration} 那套
 *       「导出→按 baseline 重建」的路子），要做就得连会话恢复语义一起改；</li>
 *   <li>不给界面下载入口——归档是运维件，路径与格式写进部署手册。</li>
 * </ul>
 */
@Component
public class SessionLogArchive {

    private static final Logger log = LoggerFactory.getLogger(SessionLogArchive.class);

    /**
     * sink 名，同时是框架导出游标在每条会话日志里的键（{@code exports/<hash(name)>}）。
     * <b>改名等于让所有存量会话从 0 重导一遍</b>，不要动。
     */
    static final String SINK_NAME = "wenqu-session-archive";

    /** 保留期缺省值（天）：与产物、会话事件账本那几条同一量级，≤0 = 不清理 */
    private static final int DEFAULT_KEEP_DAYS = 90;

    private final ConfigService configService;

    public SessionLogArchive(ConfigService configService) {
        this.configService = configService;
    }

    /** 总开关（默认开）：保留策略随默认值出厂（keepDays=90 + 每日清理），盘占用有界；关闭即放弃正文之外的全部过程明细 */
    public boolean enabled() {
        return configService.getBoolean("chat.sessionLogArchive.enabled");
    }

    /**
     * 取一条会话的 sink。<b>每条会话各一个</b>：{@code SessionEvent} 里没有会话号（只有
     * seq/type/runId/turnId），写到哪个文件只能由 sink 自己带着归属——框架的导出游标本来也是
     * 按会话各存一份，两边同形。
     */
    public SessionExportSink sinkFor(String userId, String sessionId) {
        return new Sink(userId, sessionId);
    }

    /** 归档根目录：显式配置优先，否则落框架状态目录旁的 {@code session-archive/}。 */
    public Path root() {
        String dir = configService.get("chat.sessionLogArchive.dir");
        if (dir != null && !dir.isBlank()) return Path.of(dir.trim()).toAbsolutePath().normalize();
        String state = configService.get("chat.agentscopeStateDir");
        if (state == null || state.isBlank()) state = "user-data/agentscope";
        return Path.of(state.trim()).toAbsolutePath().normalize().resolve("session-archive");
    }

    /** 一条会话的归档文件：{@code {root}/{归属号}/{会话号}.jsonl} */
    public Path fileOf(String userId, String sessionId) {
        return root().resolve(token(userId)).resolve(token(sessionId) + ".jsonl");
    }

    /**
     * 删除超过保留期的归档文件（保留期 {@code chat.sessionLogArchive.keepDays}，≤0 = 不清理）。
     *
     * <p>按<b>文件修改时间</b>判而不是按文件名里的时间判：一条会话可以聊上几个月，按创建时间会把它
     * 正在写的归档删掉。目录名是编码后的归属号，不带时间语义。
     *
     * @return 删掉的归档文件数
     */
    public int cleanupExpired() {
        int days = configService.getInt("chat.sessionLogArchive.keepDays", DEFAULT_KEEP_DAYS);
        if (days <= 0) return 0;
        Path root = root();
        if (!Files.isDirectory(root)) return 0;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        int deleted = 0;
        try (Stream<Path> users = Files.list(root)) {
            for (Path user : users.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(user)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                        if (Files.getLastModifiedTime(f).toMillis() >= cutoff) continue;
                        Files.deleteIfExists(f);
                        deleted++;
                    }
                }
                try (Stream<Path> left = Files.list(user)) {
                    if (left.findAny().isEmpty()) Files.deleteIfExists(user);
                }
            }
        } catch (IOException e) {
            log.warn("[ARCHIVE] 归档清理扫描失败（下轮重试）: {}", e.toString());
        }
        return deleted;
    }

    /**
     * 归属号 → 路径片段。
     *
     * <p><b>为什么不直接用原值</b>：userId 与 sessionId 都来自请求，直接拼进路径就是目录穿越。
     * 这里与框架日志同一做法——URL-safe Base64 去 padding，结果只剩字母数字与 {@code -_}。
     */
    private static String token(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((raw == null || raw.isBlank() ? "guest" : raw).getBytes(StandardCharsets.UTF_8));
    }

    /** 读一份归档最后一行的 seq（没有文件返回 -1）：只读尾部 8KB，不整份进内存。 */
    static long tailSeq(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return -1L;
        long size = Files.size(file);
        if (size == 0) return -1L;
        long from = Math.max(0L, size - 8192L);
        byte[] buf = new byte[(int) (size - from)];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            raf.readFully(buf);
        }
        String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            try {
                Long seq = JSON.parseObject(l).getLong("seq");
                if (seq != null) return seq;
            } catch (Exception ignored) {
                // 最后一行可能是崩溃时写了一半的：往前找一条完整的
            }
        }
        return -1L;
    }

    private final class Sink implements SessionExportSink {

        private final String userId;
        private final String sessionId;
        /** 已写盘的最高 seq：框架至少一次投递，重投的都是这段连续尾巴，按水位去重即可 */
        private long lastSeq = -1L;
        private boolean primed;

        private Sink(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }

        @Override
        public String name() {
            return SINK_NAME;
        }

        @Override
        public void accept(SessionEvent event) {
            Path file = fileOf(userId, sessionId);
            // 串行在这一个 sink 上：一条会话在本进程里只有一个常驻实例、一个 recorder，
            // 而框架侧另有一道 exporting 闸（同一时刻只有一个 drain）。多副本共写同一份归档时
            // 最坏是行序交错——tailSeq 会跳过写坏的那一行，游标去重兜住重复，不丢事件。
            synchronized (this) {
                try {
                    if (!primed) {
                        lastSeq = tailSeq(file);
                        primed = true;
                    }
                    if (event.seq() <= lastSeq) return;
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, line(event) + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    lastSeq = event.seq();
                } catch (IOException e) {
                    // 抛出去让框架保持游标不动（下轮心跳重投这一批），不吞——吞了就是归档静默少一段
                    throw new UncheckedIOException("会话日志归档写入失败: " + file, e);
                }
            }
        }

        /**
         * 一行一条事件：信封字段摊平，载荷按原 JSON 嵌进去。
         * <p>不把 {@code payloadJson} 当字符串塞：那样整份文件满屏 {@code \"}，而归档的主要用途就是
         * 被人 grep 和被脚本读。信封用 JSONObject 组装，不手工拼字符串（控制字符会拼出非法 JSON）。
         */
        private String line(SessionEvent e) {
            JSONObject o = new JSONObject();
            o.put("seq", e.seq());
            o.put("at", e.occurredAt());
            o.put("eventId", e.eventId());
            o.put("type", e.type());
            o.put("runId", e.executionRunId());
            o.put("turnId", e.turnId());
            o.put("required", e.required());
            o.put("payload", JSON.parse(e.payloadJson()));
            return o.toJSONString();
        }
    }
}
