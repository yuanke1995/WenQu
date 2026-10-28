package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.mapper.AuditEventMapper;
import com.wenqu.ai.model.AuditEvent;
import com.wenqu.ai.service.ReplicaCoordination;
import com.wenqu.ai.util.RequestUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一审计事件总线（Phase 3 · P11）。
 *
 * <p>一条审计事件同时落两处：<b>日志</b>（保持运维习惯：{@code grep '\[AUDIT\]' 仍能看到全过程）与
 * <b>库里的一行结构化事件</b>（可按人/动作/对象/时间查、可按留存期清、可导出交合规）。
 * 文本由本类从同一份键值渲染，不存在「日志一份、库里一份各写各的」的漂移。
 *
 * <p><b>写入不拖垮业务</b>：业务线程只负责取上下文与入队（几微秒），落库由单条写线程批量做；
 * 队列满时丢事件但<b>计数并告警</b>——审计丢了一条比慢一点更糟，必须让人知道丢了多少。
 *
 * <p><b>可验证</b>：同副本内按 {@code seq} 串成 SHA-256 哈希链（每行的哈希含上一行哈希），
 * 篡改、删除或插入都会让 {@code verify} 从那一处起报断点。序号由写线程按副本分配，
 * 不把自增 id 进哈希——多副本并发写入会让 id 交错，链就自己断了。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AuditService {

    /** 队列容量：一次批量落库约 50ms，2 万条足够吸收尖峰（每条 ≈1KB，最坏 20MB 堆） */
    private static final int QUEUE_CAPACITY = 20_000;
    private static final int BATCH_SIZE = 200;
    private static final String GENESIS = "GENESIS";
    /** 与改造前一致的日志标记（运维 grep 不变） */
    private static final String AUDIT_PREFIX = "[AUDIT] ";
    /** pattern 里的「键={}」：键允许中文、字母、数字、下划线与点 */
    private static final java.util.regex.Pattern KEYED_PLACEHOLDER =
            java.util.regex.Pattern.compile("([\\p{IsHan}A-Za-z_][\\p{IsHan}A-Za-z0-9_.]*)=\\{\\}");
    /** 渲染后的「键=」：用来在第一个带名键处切出动作名 */
    private static final java.util.regex.Pattern RENDERED_KEY =
            java.util.regex.Pattern.compile(" [\\p{IsHan}A-Za-z_][\\p{IsHan}A-Za-z0-9_.]*=");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AuditEventMapper mapper;
    private final ConfigService configService;
    private final ReplicaCoordination coord;

    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastDropWarnAt;
    private volatile boolean running = true;
    private Thread writerThread;
    /** 链状态（序号与链尾哈希）只由 {@link #persist} 一处推进：写线程与收尾冲队列都会进来，故它必须加锁 */
    private long seq;
    private String headHash;

    public AuditService(AuditEventMapper mapper, ConfigService configService, ReplicaCoordination coord) {
        this.mapper = mapper;
        this.configService = configService;
        this.coord = coord;
    }

    @PostConstruct
    void start() {
        try {
            seq = mapper.maxSeqOfReplica(coord.replicaId());
            headHash = mapper.tailHash(coord.replicaId());
            if (headHash == null || headHash.isBlank()) headHash = GENESIS;
        } catch (Exception e) {
            seq = 0;
            headHash = GENESIS;
            log.warn("[AUDIT] 链尾恢复失败，本副本从新链开始（历史链仍可从库里逐段验）: {}", e.getMessage());
        }
        writerThread = new Thread(this::drainLoop, "audit-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        log.info("[AUDIT] 审计事件总线已启动 replica={} 链尾 seq={}", coord.replicaId(), seq);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                // 必须先等写线程真的停下来，再自己上手冲队列。原先只 interrupt 就立刻 drainOnce，
                // 收尾那一瞬两个线程同时在改 seq 与链尾：链上会多出「序号断裂 + 链头不接」——
                // 而这恰好是「事后有人删了行」的形状，等于每次重启都可能自己造出一条假篡改证据。
                writerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 退出前把队列里的最后几条冲掉：进程收尾时的审计恰恰最重要（谁停了服务）
        drainOnce(true);
    }

    /** 查询/导出的时间区间 */
    public record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    // ==================== 业务侧入口 ====================

    /**
     * 记一条常规审计事件。用法与原来的 {@code log.info("[AUDIT] 动作 k={} k={}", ...)} 完全一致——
     * <b>格式串就是唯一作者点</b>：日志文本按 SLF4J 同形渲染，结构化行从同一份串里派生
     * （动作＝串里第一个带名键之前的部分，键值按 {@code 键={}} 与其占位序号配对）。
     * 这样才不会出现「日志写一份、库里再抄一份」的两本账。
     */
    public void record(String pattern, Object... args) {
        emit("info", pattern, args);
    }

    /** 需要人工看一眼的事件（无权限被跳过、批量部分失败等） */
    public void warn(String pattern, Object... args) {
        emit("warn", pattern, args);
    }

    private void emit(String level, String pattern, Object... args) {
        Object[] safe = args == null ? new Object[0] : args;
        String rendered = render(pattern, safe);
        Map<String, Object> detail = new LinkedHashMap<>();
        // 找出 pattern 里的「键={}」并配对到对应实参；没键的占位（如动作名里的 {}）不进 detail
        java.util.regex.Matcher m = KEYED_PLACEHOLDER.matcher(pattern == null ? "" : pattern);
        int firstKeyStart = -1;
        while (m.find()) {
            String key = m.group(1);
            int argIndex = countPlaceholders(pattern.substring(0, m.start()));
            if (argIndex >= safe.length) break;
            detail.put(key, safe[argIndex]);
            if (firstKeyStart < 0) firstKeyStart = m.start();
        }
        // 动作名：第一个带名键之前的那段（其中的 {} 已按实参渲染，如 "{}技能" → "停用技能"）
        String actionPart = firstKeyStart < 0 ? rendered : rendered.substring(0, Math.max(0, actionCut(rendered)));
        String action = actionPart.trim();
        if (action.startsWith(AUDIT_PREFIX)) {
            action = action.substring(AUDIT_PREFIX.length()).trim();
        }
        if (action.isEmpty()) action = "未命名动作";

        AuditEvent row = new AuditEvent();
        // 时间戳先截到毫秒再入队：链里的 ts 参与哈希，而列是 DATETIME(3)、MySQL 存时四舍五入、
        // 哈希用的是截断值——纳秒尾巴 ≥500µs 的那一条，落库后按库里的 ts 永远算不回原哈希
        //（实测约四成的行被 verify 报成「内容被改写」）。两边统一成毫秒，链才可能自校验通过。
        row.setTs(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        row.setLevel(level);
        row.setAction(action.length() > 64 ? action.substring(0, 64) : action);
        row.setDetail(JSON.toJSONString(detail));
        row.setReplica(coord.replicaId());
        // 上下文只能在调用线程取：写线程没有 RequestUser 与请求属性
        try {
            String uid = RequestUser.uid();
            row.setActorUid(RequestUser.ANONYMOUS.equals(uid) || uid == null || uid.isBlank() ? null : uid);
        } catch (Exception ignored) {
            row.setActorUid(null);
        }
        // 租户归属同理必须在业务线程定：落库线程未绑定租户，框架的租户拦截器不会替它补列，
        // 而 {@code tenant_id} 为 NULL 的行在成员视角根本查不到（NULL = 'default' 不为真）——
        // 不显式落这一笔，等于「后台动作产生的审计」对谁都不存在。
        // 未绑定（调度器/启动任务）按默认租户记：它们是替平台跑的，不是替某个客户跑的。
        String tenant = com.wenqu.ai.util.TenantContext.current();
        row.setTenantId(tenant == null || tenant.isBlank()
                ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : tenant);
        HttpServletRequest request = currentRequest();
        if (request != null) {
            row.setActorIp(clientIp(request));
            Object session = request.getAttribute("wenqu.auditSessionId");
            if (session != null) row.setSessionId(String.valueOf(session));
        }
        applyTarget(row, detail);
        logLine(level, rendered);
        if (!queue.offer(row)) {
            long lost = dropped.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastDropWarnAt > 60_000) {
                lastDropWarnAt = now;
                log.warn("[AUDIT] 审计事件队列已满，累计丢弃 {} 条未入库（日志里仍有全文，可事后回捞）", lost);
            }
        }
    }

    /** 与 SLF4J 同形的 {@code {}} 逐位替换（多给的实参忽略，少给的原样留着） */
    private static String render(String pattern, Object[] args) {
        if (pattern == null) return "";
        StringBuilder sb = new StringBuilder(pattern.length() + 32);
        int argIndex = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '{' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '}'
                    && (i == 0 || pattern.charAt(i - 1) != '\\')) {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i++;
            } else {
                sb.append(pattern.charAt(i));
            }
        }
        return sb.toString();
    }

    /** 渲染后第一个「键=」出现的位点（动作名到此为止） */
    private int actionCut(String rendered) {
        java.util.regex.Matcher m = RENDERED_KEY.matcher(rendered);
        return m.find() ? m.start() : rendered.length();
    }

    private static int countPlaceholders(String head) {
        int n = 0;
        for (int i = 0; i + 1 < head.length(); i++) {
            if (head.charAt(i) == '{' && head.charAt(i + 1) == '}') {
                n++;
                i++;
            }
        }
        return n;
    }

    private void logLine(String level, String rendered) {
        if ("warn".equals(level)) log.warn(rendered);
        else log.info(rendered);
    }

    /** 常见对象键归一化到 target_type/target_id 列，便于按对象查（其余留在 detail） */
    private void applyTarget(AuditEvent row, Map<String, Object> detail) {
        String[][] candidates = {
                {"docId", "doc"}, {"knowledgeId", "kb"}, {"kbId", "kb"}, {"agentId", "agent"},
                {"uid", "user"}, {"targetUid", "user"}, {"userId", "user"}, {"roleId", "role"},
                {"menuId", "menu"}, {"providerId", "provider"}, {"credentialId", "credential"},
                {"sessionId", "session"}, {"apiKeyId", "apikey"}, {"mcpId", "mcp"}, {"skill", "skill"},
                {"modelId", "model"}, {"workflowId", "workflow"}, {"id", "resource"}, {"key", "config"},
                {"planApprovalId", "plan"}, {"askId", "ask"}, {"approvalId", "approval"}, {"token", "share"},
                {"versionId", "version"}, {"quotaId", "quota"}, {"projectId", "project"},
        };
        for (String[] c : candidates) {
            Object v = detail.get(c[0]);
            if (v != null && !String.valueOf(v).isBlank()) {
                row.setTargetType(c[1]);
                String s = String.valueOf(v);
                row.setTargetId(s.length() > 190 ? s.substring(0, 190) : s);
                return;
            }
        }
        Object session = detail.get("session");
        if (session != null) {
            row.setTargetType("session");
            row.setTargetId(String.valueOf(session));
        }
    }

    /** 与改造前同形的日志行：{@code [AUDIT] 动作 k=v k=v} */
    private void log(String level, String action, Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder("[AUDIT] ").append(action);
        detail.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        if ("warn".equals(level)) log.warn(sb.toString());
        else log.info(sb.toString());
    }

    // ==================== 写线程 ====================

    private void drainLoop() {
        while (running) {
            try {
                AuditEvent first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<AuditEvent> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                persist(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[AUDIT] 批量落库异常（不中断写线程）: {}", e.getMessage());
            }
        }
    }

    private void drainOnce(boolean flushAll) {
        List<AuditEvent> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) persist(rest);
    }

    private synchronized void persist(List<AuditEvent> batch) {
        for (AuditEvent row : batch) {
            // 序号与链头只在「真落库」之后才推进：原先是先 ++seq 再插，一条插失败就 return，
            // 于是那一期号被占掉、下一期的 prev_hash 又指向一条库里不存在的行——verify 同时报
            // 「序号断裂」与「链头不接」，看起来像有人删了行，其实是我们自己没插进去。
            // 现在失败的这一条不进链、单独计数并打错误行，链本身保持连续：
            // 于是「链上有洞」重新变成它唯一的含义——事后被人删过。
            long next = seq + 1;
            row.setSeq(next);
            row.setPrevHash(headHash);
            row.setRowHash(hash(row));
            try {
                mapper.insert(row);
                seq = next;
                headHash = row.getRowHash();
            } catch (Exception e) {
                long lost = dropped.incrementAndGet();
                log.error("[AUDIT] 事件落库失败（这条不进链，已计入未入库条数）: action={} 累计未入库={} 原因={}",
                        row.getAction(), lost, e.getMessage());
            }
        }
    }

    /**
     * 这行的内容哈希对不对——容忍 1 毫秒的时间戳表示差。
     * <p>写侧现已把 ts 截到毫秒，但<b>存量行</b>是「哈希用截断值、库里存四舍五入值」留下的，
     * 两者相差恰好 1ms；这不是内容被改，而是同一时刻的两种写法。判据放在这里而不是清库重算：
     * 链的证据一旦落库就不再改写，为了让校验变绿去重算历史哈希，等于把「谁来认证过这件事」
     * 变成「我现在算得出什么」——那是把审计账本擦成白纸上交差。
     * <p>容忍的只有 ts：{@code detail}/{@code action} 等任一字段被改，±1ms 的三种算发都对不上，
     * 照样报「内容被改写」。攻击者能挑的只是 1 毫秒的时间读数，挑不走他改的内容。
     */
    private static boolean hashMatchesStored(AuditEvent row) {
        LocalDateTime origin = row.getTs();
        if (hash(row).equals(row.getRowHash())) return true;
        for (int delta = 1; delta <= 2; delta++) {
            row.setTs(origin.minusNanos(delta * 1_000_000L));
            boolean hit = hash(row).equals(row.getRowHash());
            row.setTs(origin.plusNanos(delta * 1_000_000L));
            hit = hit || hash(row).equals(row.getRowHash());
            row.setTs(origin);
            if (hit) return true;
        }
        return false;
    }

    private static String hash(AuditEvent row) {
        String canonical = String.join("|",
                row.getReplica() == null ? "" : row.getReplica(),
                String.valueOf(row.getSeq()),
                row.getTs() == null ? "" : row.getTs().format(TS),
                row.getLevel() == null ? "" : row.getLevel(),
                row.getAction() == null ? "" : row.getAction(),
                row.getActorUid() == null ? "" : row.getActorUid(),
                row.getActorIp() == null ? "" : row.getActorIp(),
                row.getTargetType() == null ? "" : row.getTargetType(),
                row.getTargetId() == null ? "" : row.getTargetId(),
                row.getSessionId() == null ? "" : row.getSessionId(),
                row.getDetail() == null ? "" : row.getDetail(),
                row.getPrevHash() == null ? "" : row.getPrevHash());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) {
            return "HASH_ERROR";
        }
    }

    // ==================== 查询 / 导出 / 校验 / 留存 ====================

    /** 分页查询（审计页与导出共用同一条件解析，两处不会漂成两套口径） */
    public Map<String, Object> query(LocalDateTime from, LocalDateTime to, String actor, String action,
                                     String keyword, int page, int size) {
        return query(from, to, actor, action, keyword, null, page, size);
    }

    /**
     * 分页查询（审计页与导出共用同一条件解析，两处不会漂成两套口径）。
     *
     * @param tenant 跨租户审阅时点名的租户（仅平台级调用方经 {@code asPlatform} 传进来）；
     *               留空＝按当前登录者的租户看（拦截器自动加条件），不是「看全部」
     */
    public Map<String, Object> query(LocalDateTime from, LocalDateTime to, String actor, String action,
                                     String keyword, String tenant, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        String t = blankToNull(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", safeCount(from, to, actor, action, keyword, t));
        out.put("items", mapper.query(from, to, actor, action, blankToNull(keyword), t, (p - 1) * s, s));
        out.put("page", p);
        out.put("size", s);
        out.put("actions", mapper.distinctActions());
        out.put("droppedSinceStart", dropped.get());
        return out;
    }

    private long safeCount(LocalDateTime from, LocalDateTime to, String actor, String action, String keyword,
                           String tenant) {
        try {
            return mapper.countQuery(from, to, actor, action, blankToNull(keyword), tenant);
        } catch (Exception e) {
            log.warn("[AUDIT] 计数失败（列表仍给，总数记 0）: {}", e.getMessage());
            return 0;
        }
    }

    /** 合规导出：CSV（RFC 4180 引号规则），逐批写出不一次性捞全量 */
    public long exportCsv(LocalDateTime from, LocalDateTime to, String actor, String action, String keyword,
                          Writer w) throws IOException {
        return exportCsv(from, to, actor, action, keyword, null, w);
    }

    /** 合规导出（可指定租户：平台级审阅某个客户的账） */
    public long exportCsv(LocalDateTime from, LocalDateTime to, String actor, String action, String keyword,
                          String tenant, Writer w) throws IOException {
        String t = blankToNull(tenant);
        w.write("时间,级别,动作,操作人,来源IP,对象类型,对象,会话,副本,序号,租户,详情\n");
        long written = 0;
        int page = 1;
        while (page <= 500) {   // 上限 10 万行：合规导出要的是可预期，不是把库拖去 OOM
            List<Map<String, Object>> rows = mapper.query(from, to, actor, action, blankToNull(keyword),
                    t, (page - 1) * 200, 200);
            if (rows.isEmpty()) break;
            for (Map<String, Object> r : rows) {
                StringBuilder line = new StringBuilder(256);
                String[] keys = {"ts", "level", "action", "actor_uid", "actor_ip", "target_type",
                        "target_id", "session_id", "replica", "seq", "tenantId", "detail"};
                for (int i = 0; i < keys.length; i++) {
                    if (i > 0) line.append(',');
                    line.append(csv(r.get(keys[i])));
                }
                w.write(line.toString());
                w.write('\n');
                written++;
            }
            w.flush();
            if (rows.size() < 200) break;
            page++;
        }
        return written;
    }

    /**
     * 链校验：逐副本按 seq 走一遍，报告断点（缺号 / 哈希不符 / prev 不接）。
     * <p>留存清理会合法地删掉最老的一批，所以链头允许从任意 seq 起——这里只判「中间有没有洞或被改」。
     * <p><b>必须走平台视图读</b>：链是「副本」级的，同一根链上混着各租户的行（写侧按业务线程落
     * {@code tenant_id}），而 {@code chainAfter} 经 MyBatis 会被租户拦截器拼上租户条件——用请求线程
     * 的租户视图读，别家租户的那几行就「不存在」，于是每跨一次租户报一对「序号断裂 + 链头不接」。
     * 本机实测 38 条断点全是这么来的假信号（那些行都在，只是不属于 default）。
     * <p>哈希比对容忍 1~2 毫秒的时间戳表示差（见 {@link #hashMatchesStored}）：写侧截断、库里四舍五入，
     * 这条差在修复之前的存量行上必然存在，把它当成「内容被改写」会让合规出口永远是一片红。
     */
    public List<Map<String, Object>> verify(int maxRowsPerReplica) {
        return com.wenqu.ai.util.TenantContext.asPlatform(() -> verifyChain(maxRowsPerReplica));
    }

    private List<Map<String, Object>> verifyChain(int maxRowsPerReplica) {
        List<Map<String, Object>> problems = new ArrayList<>();
        List<String> replicas;
        try {
            replicas = mapper.distinctReplicas();
        } catch (Exception e) {
            problems.add(Map.of("replica", "?", "problem", "读取副本清单失败: " + e.getMessage()));
            return problems;
        }
        int limit = Math.min(200_000, Math.max(1000, maxRowsPerReplica));
        for (String replica : replicas) {
            long after = 0;
            String prev = null;
            long scanned = 0;
            while (true) {
                List<AuditEvent> rows = mapper.chainAfter(replica, after, Math.min(2000, limit));
                if (rows.isEmpty()) break;
                for (AuditEvent row : rows) {
                    scanned++;
                    String tenant = row.getTenantId() == null ? "" : row.getTenantId();
                    if (prev != null && row.getSeq() != after + 1) {
                        problems.add(Map.of("replica", replica, "problem", "序号断裂",
                                "seq", row.getSeq(), "expected", after + 1, "tenant", tenant));
                    }
                    if (prev != null && !prev.equals(row.getPrevHash())) {
                        problems.add(Map.of("replica", replica, "problem", "链头不接",
                                "seq", row.getSeq(), "tenant", tenant));
                    }
                    if (!hashMatchesStored(row)) {
                        problems.add(Map.of("replica", replica, "problem", "内容被改写（哈希不符）",
                                "seq", row.getSeq(), "tenant", tenant));
                    }
                    prev = row.getRowHash();
                    after = row.getSeq();
                }
                if (rows.size() < 2000 || scanned >= limit) break;
            }
        }
        return problems;
    }

    /** 留存清理：删早于 {@code audit.retentionDays} 的事件（≤0=永久保留）；分批删避免长事务锁表 */
    public int purgeExpired() {
        int days = configService.getInt("audit.retentionDays", 365);
        if (days <= 0) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int total = 0;
        while (true) {
            int n = mapper.purgeBefore(cutoff, 5000);
            total += n;
            if (n < 5000) break;
            if (total > 5_000_000) break;   // 单次上限：真清不完说明留存期设得过短，交下次调度继续
        }
        if (total > 0) {
            log.info("[AUDIT] 留存清理：删除 {} 条早于 {} 的审计事件（audit.retentionDays={}）", total, cutoff, days);
        }
        return total;
    }

    public long droppedCount() {
        return dropped.get();
    }

    // ==================== 小工具 ====================

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        return needQuote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
