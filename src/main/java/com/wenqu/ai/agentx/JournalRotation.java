package com.wenqu.ai.agentx;

import com.wenqu.ai.sandbox.LocalJournalStorage;
import com.wenqu.ai.service.ConfigService;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogExporter;
import io.agentscope.core.session.SessionMigration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话事件日志的<b>轮转</b>：一条会话的日志长到会让「起一轮」变慢时，把它换到新的一代。
 *
 * <p><b>要治的病（实测账，2026-10-23 本机）</b>：框架每轮开始都要把这条会话的事件日志<b>从头读好几遍</b>。
 * 最狠的一处是 {@code SessionInteractions#pending}——它按 256 条一批从 seq 0 走到 head，
 * 而 {@code JournalSessionLog#scan} 每一批都从 head 沿父链<b>往回走</b>到那一批的位置，
 * 于是每轮的读次数 ≈ 事件数² ÷ (512 × 每个 commit 装的事件数)。同一条会话今晚实测：
 * 3.5k 事件→1.1 秒、14k→4.7 秒、35k→23.5 秒、57k→<b>49.3 秒</b>，全部花在模型收到第一个字之前。
 * 用户看到的就是「正在溯源，用时 1 分 32 秒」而屏幕上什么都没有。
 *
 * <p><b>为什么只能靠轮转</b>：事件里 98% 是 {@code model/chunk}（每个流式增量一条，实测 84,820 条 / 51MB），
 * 而它由框架在模型适配器边界无条件写入，宿主既不能少写也不能改写——commit 带父链与逐段 SHA-256，
 * 抽掉中间任何一条事件都会让 {@code scan} 报 {@code Broken committed prefix}。
 * 落盘节奏那道闸（200ms 一颗 commit）只改得到分母，分子是<b>平方</b>项：真正无界的是事件数。
 * 所以唯一的根治是给事件数设界。
 *
 * <p><b>用的就是框架自己那套分代口子</b>：{@code SessionMigration.exportSnapshot} 从旧链导出
 * {@code AgentState}（框架每轮都落 {@code state/checkpoint}，所以导出的就是当前生效的上下文），
 * {@code importBaseline} 把它作为唯一一条 {@code migration/baseline} 事件种进一份<b>空</b>日志——
 * 框架的 fork 功能走的正是这两个入口，不是自造的旁路。旧那一代整目录搬到 {@code rotated/} 下留档，
 * 搬之前先按框架的导出游标把它补送进归档（游标存在旧目录里，搬完就送不到了）。
 *
 * <p><b>要认的账</b>：轮转点之前的原始事件不再被框架的 {@code session_history} / {@code session_search}
 * 看见（它们读的就是这份日志）。用户看得见的历史不受影响——消息正文在库、逐轮过程在事件账本、
 * 全量明细在归档 JSONL，三处都不读这份日志。阈值因此按「起轮税不超过约两秒」定，而不是按轮数定。
 *
 * <p><b>只在静默点动</b>：{@code exportSnapshot} 自己会拒（有写者租约、有未结算的执行、
 * 有没裁决的卡时抛异常），加上调用点选在「上一轮已收口、本轮还没起」的起轮缝里，
 * 同一条会话由轮级互斥串起来，不存在两个循环同时看着一份日志搬。搬不动就这一轮不轮转，下一轮再试。
 */
@Component
public class JournalRotation {

    private static final Logger log = LoggerFactory.getLogger(JournalRotation.class);

    /**
     * 默认阈值（事件条数）。定这一档的依据就是上面那条实测曲线：12k 事件对应约 1.5 秒起轮税，
     * 而这条会话里一个重活轮就产 8k 条——也就是大约一轮一转，界是硬的而不是「聊久了慢慢变慢」。
     */
    private static final int DEFAULT_ROTATE_EVENTS = 12_000;

    /** 搬走的那一代默认留 30 天：归档 JSONL 是长期件，这份留着只为「刚轮转完就要查原始事件」那几天。 */
    private static final int DEFAULT_ROTATED_KEEP_DAYS = 30;

    /** 搬走的那一代挂在 root 下的这个目录名，同时是清理任务的扫描根。 */
    private static final String ROTATED_DIR = "rotated";

    /** 新一代先在这里种好，再改名接上活路径（失败面里活路径始终是完整的一份旧日志）。 */
    private static final String STAGING_DIR = "staging";

    private final ConfigService configService;
    private final SessionLogArchive archive;
    private volatile LocalJournalStorage storage;

    public JournalRotation(ConfigService configService, SessionLogArchive archive) {
        this.configService = configService;
        this.archive = archive;
    }

    /** 与 {@code ProvisionerSandboxFilesystem} 同一个 root：两处各自算就会搬走不是问答正在写的那一份。 */
    private LocalJournalStorage storage() {
        LocalJournalStorage s = storage;
        if (s == null) {
            synchronized (this) {
                s = storage;
                if (s == null) {
                    s = new LocalJournalStorage(LocalJournalStorage.defaultRoot());
                    storage = s;
                }
            }
        }
        return s;
    }

    /**
     * 起轮前看一眼这条会话的日志要不要换代。任何一步不满足就安静跳过——它是省时间的，
     * 不是保正确性的，绝不该把用户这一轮问不出去。
     *
     * @param agentId 框架侧的 agentId（拼日志路径用，必须与建实例时那份一致）
     */
    public void rotateIfNeeded(String userId, String sessionId, String agentId) {
        int threshold = configService.getInt("chat.agentscopeJournalRotateEvents", DEFAULT_ROTATE_EVENTS);
        if (threshold <= 0 || userId == null || userId.isBlank()
                || sessionId == null || sessionId.isBlank() || agentId == null || agentId.isBlank()) {
            return;
        }
        String path = new SessionKey(userId, agentId, sessionId).storagePath();
        LocalJournalStorage store = storage();
        long seq;
        String owner;
        try {
            SessionLog.Head head = new JournalSessionLog(store, path).head();
            seq = head.seq();
            owner = head.owner();
        } catch (RuntimeException e) {
            // 日志本身读不动（链断了/头坏了）：那是另一件事，交给框架自己报，这里不叠一条噪音
            log.warn("[FAIL-LOUD] 会话事件日志读头失败，本轮不做轮转 session={}: {}", sessionId, e.toString());
            return;
        }
        if (seq < threshold) return;
        if (owner != null) {
            log.info("[ROTATE] 会话事件日志已有写者（上一轮还没撒手），本轮不轮转: session={} seq={}", sessionId, seq);
            return;
        }
        long beganAt = System.currentTimeMillis();
        long drainMs = 0L;
        long exportMs = 0L;
        String stamp = Long.toString(System.currentTimeMillis());
        String aside = ROTATED_DIR + "/" + path + "/" + stamp;
        String staging = STAGING_DIR + "/" + path + "/" + stamp;
        try {
            // 1) 先把旧那一代按框架的游标补送干净：游标与已送水位都存在旧目录里，搬走之后就再也送不出去了。
            //    正常情况这是几条事件的零头（框架每轮心跳都在增量送）；但一条会话若在归档上线之前
            //    就攒了几万条事件没送过，这一笔旧账会在换代这一刻一次付清——2026-10-24 真机实测
            //    一条 4.2 万事件的闲置会话补送 4.2 万行 JSONL 花了整 18 秒，那一轮首帧因此是 18.9 秒。
            //    这不是换代的固定成本：同一条会话下一轮起轮只花 0.4 秒（实测），且下一次换代只送零头。
            //    分三段计时落日志，是为了让「这一轮为什么慢」下次不用再查一遍。
            if (archive.enabled()) {
                long d0 = System.currentTimeMillis();
                new SessionLogExporter(new JournalSessionLog(store, path), archive.sinkFor(userId, sessionId)).drain();
                drainMs = System.currentTimeMillis() - d0;
            }
            // 2) 导出当前状态（这一步自己会拒：有未结算执行/没裁决的卡/没有可恢复 baseline）
            long e0 = System.currentTimeMillis();
            SessionMigration.ExportedState snapshot =
                    SessionMigration.exportSnapshot(new JournalSessionLog(store, path));
            exportMs = System.currentTimeMillis() - e0;
            // 3) 先在旁边把新一代种好，再动活路径。反过来做会留一条难看的失败面：搬走了、baseline 却没种上，
            //    活路径就成了空日志——框架看它就是「一条从没聊过的会话」，上下文静默清零。
            SessionMigration.importBaseline(new JournalSessionLog(store, staging), snapshot.state(),
                    "wenqu:rotate:" + sessionId, snapshot.asOfSeq());
            // 4) 两次改名：活路径→留档、staging→活路径。同盘 rename 是原子的，中间空窗只有一瞬且能搬回
            if (!store.relocateSessionDir(path, aside)) {
                deleteTree(store.sessionDir(staging));
                log.info("[ROTATE] 没能搬开会话事件日志目录（已存在或不在），本轮不轮转: session={} seq={}",
                        sessionId, seq);
                return;
            }
            if (!store.relocateSessionDir(staging, path)) {
                store.relocateSessionDir(aside, path);   // 原样搬回：宁可这一轮继续交起轮税，也不留一份空日志
                log.warn("[FAIL-LOUD] 换代最后一步没接上，已把旧代搬回原位（本轮起轮仍会偏慢）session={} seq={}",
                        sessionId, seq);
                return;
            }
            log.info("[ROTATE] 会话事件日志已换代: session={} 旧 seq={} → 新 seq=1（导出自 seq={}）旧代 {} "
                            + "总用时 {}ms（归档补送 {}ms + 导出状态 {}ms + 两次改名 {}ms）",
                    sessionId, seq, snapshot.asOfSeq(), aside,
                    System.currentTimeMillis() - beganAt, drainMs, exportMs,
                    Math.max(0L, System.currentTimeMillis() - beganAt - drainMs - exportMs));
        } catch (Exception e) {
            // 不冒泡：轮转失败只是这一轮继续交起轮税，把异常抛进问答就是把答案换成一条报错
            try {
                deleteTree(storage().sessionDir(STAGING_DIR));
            } catch (RuntimeException | IOException ignored) {
                // staging 没建起来，清理个空
            }
            log.warn("[FAIL-LOUD] 会话事件日志换代未成功（本轮继续用旧日志，起轮仍会偏慢）session={} seq={}: {}",
                    sessionId, seq, e.toString());
        }
    }

    /**
     * 删除超过保留期的旧代次日志目录（保留期 {@code chat.agentscopeJournalRotateKeepDays}，≤0 = 不清理）。
     *
     * <p>按目录的修改时间判：一次换代之后没人再写它，mtime 就是搬走的时刻。
     * 两个根一起扫：{@code rotated/} 是搬走的旧代，{@code staging/} 里只剩换代失败留下的残渣（正常路径上
     * 它会被改名接进活路径，留在那儿的就是垃圾）。
     *
     * @return 删掉的目录数
     */
    public int cleanupRotated() {
        int days = configService.getInt("chat.agentscopeJournalRotateKeepDays", DEFAULT_ROTATED_KEEP_DAYS);
        if (days <= 0) return 0;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        int cleaned = 0;
        for (String which : new String[]{ROTATED_DIR, STAGING_DIR}) {
            Path root = LocalJournalStorage.defaultRoot().resolve(which);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> leaves = Files.walk(root)) {
                // 结构固定是 {rotated|staging}/agents/<agent>/sessions/<user>/<session>/<时间戳>：只认带时间戳的叶子
                for (Path dir : leaves.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().matches("\\d{13}"))
                        .sorted(Comparator.comparingInt(p -> -p.getNameCount()))
                        .toList()) {
                    if (Files.getLastModifiedTime(dir).toMillis() >= cutoff) continue;
                    deleteTree(dir);
                    cleaned++;
                }
            } catch (IOException e) {
                log.warn("[ROTATE] {} 清理扫描失败（下轮重试）: {}", which, e.toString());
            }
        }
        if (cleaned > 0) log.info("[ROTATE] 已清理 {} 个过期的会话事件日志代次（含换代残渣）", cleaned);
        return cleaned;
    }

    private void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
