package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 运行总线的<b>跨副本层</b>（Phase 0 · P1）：把「这一轮在哪个副本上跑」「此刻还有哪些观众、分别在哪些副本」
 * 「事件帧」「给远端观众的权威快照」「叫停指令」这五件事搬到 Redis。
 *
 * <p>切分口径：{@code SseEmitter} 是一条 socket，搬不走——观众连在哪个副本，扇出就必须发生在那个副本。
 * 所以<b>执行与权威快照仍留在宿主副本</b>（跑出这一轮的那个进程），跨副本只搬协调态与事件：
 * <ul>
 *   <li>注册表 {@code wenqu:run:{会话}}：宿主 id、轮次、归属、最新序号，带 TTL 由宿主续；</li>
 *   <li>观众存在性 {@code wenqu:run:views:{会话}}：每个副本写自己那一份（本地观众 + 本副本服务的远端观众），
 *       TTL 20 秒——副本崩溃后僵尸观众自动消失，宿主不会永远以为还有人看；</li>
 *   <li>事件帧 {@code wenqu:bus:frames}：<b>只有确实有远端观众时才发</b>，逐字增量不因此多一次 Redis 写；</li>
 *   <li>快照 {@code wenqu:bus:snap:req}：远端观众接流时向宿主<b>要</b>一份，不在 Redis 里另存一份正文；</li>
 *   <li>指令 {@code wenqu:bus:cmd}：叫停/中止打到任意副本都转给宿主执行，宿主才是本轮状态的主人。</li>
 * </ul>
 *
 * <p>租约/广播/请求应答这些机制都在 {@link ReplicaCoordination}，本类只留总线语义。
 * Redis 抖动时整体降级为「只看得见本副本」，本副本行为与改造前一致。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RunBusCluster {

    private static final String RUN_KEY = "wenqu:run:";
    private static final String VIEWS_KEY = "wenqu:run:views:";
    private static final String CH_FRAMES = "wenqu:bus:frames";
    private static final String CH_WATCH = "wenqu:bus:watch";
    private static final String CH_SNAP_REQ = "wenqu:bus:snap:req";
    private static final String CH_SNAP_REP = "wenqu:bus:snap:rep:";
    private static final String CH_CMD = "wenqu:bus:cmd";

    /** 注册表存活时间：宿主每次巡检续期，宿主消失后最迟这么久被判「这一轮没了」 */
    private static final Duration RUN_TTL = Duration.ofMinutes(30);
    private static final Duration VIEWS_TTL = Duration.ofSeconds(20);
    /** 远端接流等宿主回快照的上限：超时就如实回 running:false，不编一个空快照糊弄前端 */
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(3);

    private final StringRedisTemplate redis;
    private final ReplicaCoordination coord;

    /** 本副本服务的远端观众：sessionId → 连在本副本、轮却跑在别的副本上的通道 */
    private final Map<String, List<RemoteSub>> remoteSubs = new ConcurrentHashMap<>();
    /** 宿主侧「此刻该不该广播帧」：由巡检与 watch 通知共同维护，避免每帧一次 Redis 读 */
    private final Set<String> publishing = ConcurrentHashMap.newKeySet();

    private volatile Function<String, Map<String, Object>> snapshotProvider;
    private volatile BiConsumer<String, JSONObject> commandHandler;
    private volatile BiConsumer<String, Integer> viewersChanged = (session, left) -> {
    };
    private volatile String myReplyChannel;

    private record RemoteSub(SseEmitter emitter, String userId, long fromSeq) {
    }

    public RunBusCluster(StringRedisTemplate redis, ReplicaCoordination coord) {
        this.redis = redis;
        this.coord = coord;
    }

    /** 本副本身份标识（写进注册表，宿主据此认出「这条是我自己发的」） */
    public String replicaId() {
        return coord.replicaId();
    }

    @PostConstruct
    void start() {
        myReplyChannel = coord.replyChannel(CH_SNAP_REP);
        coord.listen(CH_FRAMES, this::onFrame);
        coord.listen(CH_WATCH, this::onWatch);
        coord.listen(CH_SNAP_REQ, this::onSnapshotRequest);
        coord.listen(CH_CMD, this::onCommand);
        log.info("[BUS] 跨副本层已就绪 replica={}", replicaId());
    }

    /** 装配宿主侧能力（由 ChatRunBus 在自己的初始化里调，避免两个 bean 构造期成环） */
    public void bind(Function<String, Map<String, Object>> snapshotProvider,
                     BiConsumer<String, JSONObject> commandHandler,
                     BiConsumer<String, Integer> onViewerCountChanged) {
        this.snapshotProvider = snapshotProvider;
        this.commandHandler = commandHandler;
        this.viewersChanged = onViewerCountChanged;
    }

    // ==================== 注册表 ====================

    /** 开轮：登记宿主、轮次与归属（会话轮级互斥由上层保证，这里覆盖写 + 起 TTL） */
    public void registerRun(String sessionId, String turnId, String userId) {
        try {
            String key = RUN_KEY + sessionId;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("replica", replicaId());
            fields.put("turnId", turnId == null ? "" : turnId);
            fields.put("userId", userId == null ? "" : userId);
            fields.put("began", String.valueOf(System.currentTimeMillis()));
            fields.put("seq", "0");
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, RUN_TTL);
        } catch (Exception e) {
            log.warn("[BUS] 登记轮次失败（降级为本副本可见）: session={} {}", sessionId, e.getMessage());
        }
    }

    /** 巡检续期并回写最新序号 */
    public void refreshRun(String sessionId, long seq) {
        try {
            String key = RUN_KEY + sessionId;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("seq", String.valueOf(seq));
            fields.put("replica", replicaId());
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, RUN_TTL);
        } catch (Exception ignored) {
            // 续期失败下一拍再来；本副本仍在跑这一轮
        }
    }

    /** 轮收尾：摘注册表、停止广播 */
    public void unregisterRun(String sessionId) {
        publishing.remove(sessionId);
        try {
            redis.delete(RUN_KEY + sessionId);
        } catch (Exception e) {
            log.warn("[BUS] 摘除轮次登记失败: session={} {}", sessionId, e.getMessage());
        }
    }

    /** 查这一轮的登记信息；null=没有轮在跑（或 Redis 不可用，由上层退回本地判据） */
    public Map<String, String> lookupRun(String sessionId) {
        if (sessionId == null) return null;
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RUN_KEY + sessionId);
            if (raw == null || raw.isEmpty()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 该轮是否由<b>别的</b>副本主持（本副本自己跑的走本地路径，不经这一层） */
    public boolean hostedElsewhere(String sessionId) {
        Map<String, String> run = lookupRun(sessionId);
        return run != null && !replicaId().equals(run.get("replica"));
    }

    // ==================== 观众存在性 ====================

    /** 本副本此刻的观众总数（本地通道 + 本副本代管的远端通道）写进自己的那格 */
    public void writeViewersHere(String sessionId, int count) {
        try {
            String key = VIEWS_KEY + sessionId;
            if (count <= 0) {
                redis.opsForHash().delete(key, replicaId());
            } else {
                redis.opsForHash().put(key, replicaId(), String.valueOf(count));
            }
            redis.expire(key, VIEWS_TTL);
        } catch (Exception ignored) {
            // 写失败最多让宿主早一拍判定「没人看」，宽限本身有「等人工裁决就顺延」兜着
        }
    }

    private void clearViewersHere(String sessionId) {
        try {
            redis.opsForHash().delete(VIEWS_KEY + sessionId, replicaId());
        } catch (Exception ignored) {
            // 进程都要走了，交给 TTL
        }
    }

    /** 其它副本上还有多少观众（宽限判定的输入） */
    public int remoteViewerCount(String sessionId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(VIEWS_KEY + sessionId);
            int total = 0;
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                if (replicaId().equals(String.valueOf(e.getKey()))) continue;
                try {
                    total += Integer.parseInt(String.valueOf(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // 脏值不计入，宁可少算也不要把「有人看」算成永远为真
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 宿主巡检用：刷新「该不该广播帧」，并把本副本观众数续进存在性表 */
    public void hostPulse(String sessionId, int localViewers) {
        if (remoteViewerCount(sessionId) > 0) {
            publishing.add(sessionId);
        } else {
            publishing.remove(sessionId);
        }
        writeViewersHere(sessionId, localViewers);
    }

    public boolean shouldBroadcast(String sessionId) {
        return publishing.contains(sessionId);
    }

    // ==================== 事件帧 ====================

    /** 宿主广播一条帧；没有远端观众时一个字节都不发 */
    public void publishFrame(String sessionId, long seq, String frame) {
        if (!publishing.contains(sessionId)) return;
        coord.broadcast(CH_FRAMES, envelope(sessionId, seq, frame));
    }

    /** 本轮结束：通知所有副本上的远端观众收尾（不然那条通道会一直挂着转圈） */
    public void publishClosed(String sessionId, long seq) {
        if (!publishing.contains(sessionId)) return;
        coord.broadcast(CH_FRAMES,
                envelope(sessionId, seq, ChatRunBus.frame("bus_closed", "", sessionId, seq, null)));
    }

    private Map<String, Object> envelope(String sessionId, long seq, String frame) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("session", sessionId);
        envelope.put("seq", seq);
        envelope.put("frame", frame);
        return envelope;
    }

    private void onFrame(JSONObject envelope, String from) {
        String session = envelope.getString("session");
        long seq = envelope.getLongValue("seq");
        String frameText = envelope.getString("frame");
        List<RemoteSub> subs = remoteSubs.get(session);
        if (subs == null || subs.isEmpty() || frameText == null) return;
        boolean closed = frameText.contains("\"bus_closed\"");
        String type = null;
        for (RemoteSub sub : List.copyOf(subs)) {
            if (sub.fromSeq() >= seq) continue;   // 那段已经在给它的首个快照里了
            try {
                if (closed) {
                    sub.emitter().complete();
                    removeRemoteSub(session, sub.emitter());
                    continue;
                }
                if (type == null) {
                    type = JSON.parseObject(frameText).getString("type");
                }
                sub.emitter().send(SseEmitter.event().name(type).data(frameText));
            } catch (Exception e) {
                removeRemoteSub(session, sub.emitter());
            }
        }
    }

    /** 别的副本刚挂了远端观众上来：宿主立刻开始广播，不等下一拍巡检（否则中间那几秒的逐字增量会缺） */
    private void onWatch(JSONObject obj, String from) {
        String session = obj.getString("session");
        Map<String, String> run = lookupRun(session);
        if (run != null && replicaId().equals(run.get("replica"))) {
            publishing.add(session);
        }
    }

    // ==================== 快照请求/应答 ====================

    /** 远端接流：向宿主副本要一份权威快照；拿不到返回 null（调用方如实回 running:false） */
    public Map<String, Object> requestSnapshot(String sessionId) {
        JSONObject reply = coord.request(CH_SNAP_REQ,
                Map.of("session", sessionId, "_replyTo", myReplyChannel), SNAPSHOT_TIMEOUT);
        if (reply == null) {
            log.warn("[BUS] 等宿主快照超时/失败: session={}", sessionId);
            return null;
        }
        JSONObject snap = reply.getJSONObject("snapshot");
        return snap == null ? null : new LinkedHashMap<>(snap);
    }

    private void onSnapshotRequest(JSONObject req, String from) {
        String session = req.getString("session");
        Function<String, Map<String, Object>> provider = snapshotProvider;
        if (provider == null) return;
        Map<String, Object> snap = provider.apply(session);
        if (snap == null) return;   // 不是本副本主持的轮，不该由我回答
        coord.respond(req, Map.of("snapshot", snap));
    }

    // ==================== 控制指令（叫停/中止） ====================

    /** 把指令转给宿主副本执行 */
    public boolean forwardCommand(String sessionId, String op, Map<String, Object> payload) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("op", op);
        cmd.put("session", sessionId);
        cmd.put("payload", payload == null ? Map.of() : payload);
        coord.broadcast(CH_CMD, cmd);
        return true;
    }

    private void onCommand(JSONObject cmd, String from) {
        String session = cmd.getString("session");
        Map<String, String> run = lookupRun(session);
        if (run == null || !replicaId().equals(run.get("replica"))) return;   // 不是本副本主持，丢掉
        BiConsumer<String, JSONObject> handler = commandHandler;
        if (handler == null) return;
        JSONObject payload = cmd.getJSONObject("payload");
        handler.accept(cmd.getString("op"), payload == null ? new JSONObject() : payload);
    }

    // ==================== 本副本服务的远端观众 ====================

    /** 登记一个「轮在别的副本上、通道连在本副本」的观众；fromSeq=接流时刻宿主已发到的序号 */
    public void addRemoteViewer(String sessionId, SseEmitter emitter, String userId, long fromSeq) {
        remoteSubs.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                .add(new RemoteSub(emitter, userId, fromSeq));
        writeViewersHere(sessionId, remoteSubs.get(sessionId).size());
        coord.broadcast(CH_WATCH, Map.of("session", sessionId));
    }

    public void removeRemoteSub(String sessionId, SseEmitter emitter) {
        List<RemoteSub> subs = remoteSubs.get(sessionId);
        if (subs == null) return;
        subs.removeIf(s -> s.emitter() == emitter);
        int left = subs.size();
        if (left == 0) {
            remoteSubs.remove(sessionId);
            clearViewersHere(sessionId);
        } else {
            writeViewersHere(sessionId, left);
        }
        viewersChanged.accept(sessionId, left);
    }

    /** 远端观众的心跳：工具跑五分钟那种静默期，也得让这条通道知道连接还活着 */
    public void keepaliveRemote(String sessionId) {
        List<RemoteSub> subs = remoteSubs.get(sessionId);
        if (subs == null || subs.isEmpty()) return;
        for (RemoteSub sub : List.copyOf(subs)) {
            try {
                sub.emitter().send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                removeRemoteSub(sessionId, sub.emitter());
            }
        }
    }

    /** 上层登记/摘除本地通道后同步一次本副本的观众数（本地 + 远端一起报） */
    public void reportViewerCount(String sessionId, int totalOnThisReplica) {
        writeViewersHere(sessionId, totalOnThisReplica);
    }

    public int remoteSubCountHere(String sessionId) {
        List<RemoteSub> subs = remoteSubs.get(sessionId);
        return subs == null ? 0 : subs.size();
    }

    /** 本副本正在替哪些会话服务远端观众（巡检要按这个做心跳与存在性续期） */
    public List<String> sessionsWithRemoteSubs() {
        return List.copyOf(remoteSubs.keySet());
    }

    /** 某会话的远端观众全部结束（宿主判死或本副本收尾） */
    public void completeRemote(String sessionId) {
        List<RemoteSub> subs = remoteSubs.remove(sessionId);
        if (subs == null) return;
        for (RemoteSub sub : subs) {
            try {
                sub.emitter().complete();
            } catch (Exception ignored) {
                // 已经断了
            }
        }
        clearViewersHere(sessionId);
    }

    public void forgetSession(String sessionId) {
        publishing.remove(sessionId);
        completeRemote(sessionId);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        completeAllRemote();
    }

    /** 进程收尾：本副本的远端观众通道都要关掉 */
    public void completeAllRemote() {
        for (String session : List.copyOf(remoteSubs.keySet())) {
            completeRemote(session);
        }
    }
}
