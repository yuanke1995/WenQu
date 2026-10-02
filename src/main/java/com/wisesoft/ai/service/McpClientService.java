package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.UserMcpMapper;
import com.wisesoft.ai.model.UserMcp;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP（Model Context Protocol）客户端管理：把用户登记的 MCP Server 的工具动态接入 Function Calling
 * 链路（"工具生态层"——用户可自行添加外部工具，无需改代码）。
 * <p>
 * <b>归属：每人连自己的服务</b>。原形态是「管理员在系统设置里配一个全局 JSON 数组 + 全局总开关」，
 * 现改为 {@code c_ai_user_mcp} 按 uid 登记，连接池也按 uid 分池——取工具时只 disasters 自己那份，
 * 不会因为别人连了什么服务而影响自己。
 * <p>
 * 连接策略（容错优先，MCP 故障绝不影响问答主链路）：
 * - 连接在<b>后台线程</b>建立：状态接口/首次访问只负责对齐配置清单并立即返回，未连上的服务
 *   状态为 {@code connecting}，连完一个落一个状态（connected / failed:原因）；
 * - 单个 server 连接/初始化失败仅告警并跳过，不影响其他 server；失败后按退避序列在后台
 *   自动重试（{@link #RETRY_DELAYS_SECONDS}），远端抖动类瞬时故障无需用户手动刷新；
 * - 问答链路取工具（{@link #toolCallbacks}）前会等待在途连接收敛——聊天拿到的工具集合
 *   与旧的同步行为一致，等待开销也收敛（initialize 最长 30s 必然返回）；
 * - 该用户的配置发生变化（增删改/启停）由指纹比对自动重建连接（旧连接 closeGracefully）；
 * - 某个用户长时间不用（{@link #IDLE_EVICT_MILLIS}）其连接池会被关闭回收，避免长连接无限堆积。
 * <p>
 * 工具暴露：每个成功连接的 server 的工具列表转 {@link SyncMcpToolCallback}（Spring AI ToolCallback），
 * 由 {@code RagService.enabledToolCallbacks()} 合并进请求；工具名自动带 server 前缀防冲突（Spring AI 默认行为）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class McpClientService {

    /**
     * 初始化超时（秒）。SDK（0.18.3）的 initialize 是一个事务：initialize POST +
     * notifications/initialized + postInit 钩子里再串行发一次 tools/list（工具 schema 缓存），
     * 至少两个业务请求串行。外部 server 的响应延迟波动可以很大（实测 context7 免密钥端点
     * 单请求 1s~3.5s+），5s 预算会被两个慢请求叠加击穿 → initialize 整体 TimeoutException
     * （日志表现为 "Client failed to initialize by explicit API call"，cause 是
     * TimeoutException）。30s 与请求超时同量级，慢服务也能完成握手。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** 工具调用请求超时（秒） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 每个用户最多接入的 server 数（防误配超长列表拖垮问答） */
    private static final int MAX_SERVERS = 10;
    /**
     * 空闲连接池回收阈值：该用户的连接超过这个时长没被用过就关掉（SSE/streamable 是长连接，
     * 不能无限挂着）。30min 偏激进——隔半小时回来看一眼 MCP 页就触发整池重建，每个服务重新
     * 握手（远端抖动时还连不上），体感就是"每次点开都要去连接"。出站长连接量小
     * （≤{@link #MAX_SERVERS} 条/用户），放宽到 6h：工作日内基本不重建，过夜后首次访问重建一次。
     */
    private static final long IDLE_EVICT_MILLIS = Duration.ofHours(6).toMillis();
    /**
     * 连接失败的后台重试退避序列（秒）。远端"间歇性掐握手"（Remote host terminated the
     * handshake）类瞬时故障多数在一两轮内恢复；全部用尽后停在 failed，等用户点「刷新」
     * 或改配置触发整池重建。只在后台跑，绝不占 HTTP 请求线程。
     */
    private static final long[] RETRY_DELAYS_SECONDS = {30, 60, 120, 300, 600};

    private final UserMcpMapper userMcpMapper;

    /** 连接池按用户隔离：uid → 该用户的连接与状态 */
    private final Map<String, UserPool> pools = new ConcurrentHashMap<>();

    /**
     * 后台连接线程池：连接/握手是慢 IO（单 server initialize 最长 30s），绝不能占着 HTTP
     * 请求线程等（曾致 MCP 页面首屏要等所有服务连完才出列表）。cached 池按需起线程，
     * 任务数以「正在重建连接的用户数」为上界，不会失控。
     */
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-connect");
        t.setDaemon(true);
        return t;
    });

    /** 失败重试调度：单线程串行足够——重试是低频后台行为，串行天然温和（不 burst 远端） */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-retry");
        t.setDaemon(true);
        return t;
    });

    public McpClientService(UserMcpMapper userMcpMapper) {
        this.userMcpMapper = userMcpMapper;
    }

    @PreDestroy
    public void shutdown() {
        connectExecutor.shutdownNow();
        retryScheduler.shutdownNow();
        List<McpSyncClient> stale = new ArrayList<>();
        pools.values().forEach(p -> {
            synchronized (p) {
                stale.addAll(detachPool(p));
            }
        });
        closeQuietly(stale);
    }

    /**
     * 返回某用户当前所有已启用 MCP 服务的工具（供 RagService.enabledToolCallbacks() 合并）。
     * 内部先按该用户的配置对齐连接（懒加载 + 配置变更重连），连接失败的 server 工具自动跳过。
     *
     * @param uid 归属用户（只取这个人的服务）
     */
    public List<ToolCallback> toolCallbacks(String uid) {
        return toolCallbacks(uid, null);
    }

    /**
     * 同上，但只取指定 server 的工具（智能体级「具体项筛选」）。
     *
     * @param uid         归属用户
     * @param onlyServers null=不筛选（该用户全部已启用服务）；空集合=一个都不取；非空=只取这些（按服务名）
     */
    public List<ToolCallback> toolCallbacks(String uid, java.util.Set<String> onlyServers) {
        if (onlyServers != null && onlyServers.isEmpty()) {
            return List.of(); // 智能体显式"不使用任何 MCP"：连接都不必建立
        }
        if (uid == null || uid.isBlank()) return List.of();
        UserPool pool = ensureConnections(uid);
        awaitConnect(pool); // 连接是后台任务：聊天取工具前等它收敛，工具集合与旧的同步行为一致

        // 锁内只做快照，listTools 在锁外发：问答链路对每个 server 的 listTools（远端挂起可吃满
        // 60s 请求超时）若持着池锁，会把 /status 这类只读本地快照的消费方一起堵在锁上
        // （智能体列表转圈的同一根因）。临界区纪律见 {@link UserPool}。
        Map<String, McpSyncClient> snap;
        synchronized (pool) {
            snap = new LinkedHashMap<>(pool.clients);
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (Map.Entry<String, McpSyncClient> entry : snap.entrySet()) {
            if (onlyServers != null && !onlyServers.contains(entry.getKey())) continue;
            McpSyncClient client = entry.getValue();
            try {
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    callbacks.add(new SyncMcpToolCallback(client, tool));
                }
            } catch (Exception e) {
                log.warn("[MCP] uid={} 拉取 server {} 工具列表失败（跳过该 server）: {}",
                        uid, entry.getKey(), e.getMessage());
                failed.put(entry.getKey(), "failed:" + e.getMessage());
            }
        }
        // 失败状态回写锁内做，且仅当池里仍是快照到的那个连接——期间配置重建换了新 client 就不覆盖
        if (!failed.isEmpty()) {
            synchronized (pool) {
                failed.forEach((name, st) -> {
                    if (pool.clients.get(name) == snap.get(name)) pool.states.put(name, st);
                });
            }
        }
        return callbacks;
    }

    /**
     * 某用户的服务一览：每条服务的名称/地址/类型/启停/连接状态/可用工具。
     * 没登记服务时返回空列表（前端据此显示"还没有 MCP 服务"）。
     *
     * <p>两个读取档位：{@code verifyOnline=true} 对每个已连接服务同步发一次 listTools 在线校验
     * （远程 IO，单请求 1s~3.5s+、远端挂起要吃满 60s 请求超时）——MCP 管理页要"此刻真实状态"，
     * 值得付这个代价；{@code verifyOnline=false} 只读连接池里最近一次已知状态与配置清单
     * （纯本地内存）。只想拿服务清单的消费方（如智能体页填充「MCP 外部工具」下拉）一律走这档：
     * 曾把在线校验无条件织进本接口，智能体页每次进入都被远程 MCP 的延迟劫持（整页
     * Promise.all 等它，转圈数秒起步）。
     *
     * <p><b>第三档 {@code verifyOnline=false, withTools=true}</b>（2026-10-02 补）：只补工具清单、
     * <b>不翻转状态</b>。工作流画布的 mcp 节点要列该服务下的工具供用户选，纯本地档下 {@code tools}
     * 恒为空 ⇒ 工具下拉永远是空的（画布侧表现为「服务能选、工具选不出来」，节点必然停在必填校验上）；
     * 而让它走 {@code verifyOnline=true} 又会把打开节点抽屉的代价变成同步远程 IO（正是本方法注释里
     * 记的那次回归的根因）。这一档取两者中间：对已连接服务发 listTools 拿工具名，但拿到失败也只当
     * 「本轮没取到工具」——状态显示维持粘性 connected，语义仍由 MCP 管理页的在线校验负责。
     *
     * @param uid          归属用户
     * @param verifyOnline true=对已连接服务做在线校验（可把"假绿"翻成失败）；false=只报最近已知状态
     * @param withTools    true=额外对已连接服务取一次工具清单（只补 tools，不改状态）
     */
    public List<Map<String, Object>> serverStatuses(String uid, boolean verifyOnline) {
        return serverStatuses(uid, verifyOnline, false);
    }

    public List<Map<String, Object>> serverStatuses(String uid, boolean verifyOnline, boolean withTools) {
        if (uid == null || uid.isBlank()) return List.of();
        UserPool pool = ensureConnections(uid);

        // 锁内只做本地快照（清单/状态/客户端引用），立即放锁。在线校验的 listTools 是秒级远程
        // IO（远端挂起要吃满 60s 请求超时），持锁会把智能体页等只读消费方堵在同一把池锁上
        // ——智能体列表被 MCP 拖住转圈的根因。临界区纪律见 {@link UserPool}。
        List<UserMcp> rowsSnap;
        Map<String, String> statesSnap;
        Map<String, McpSyncClient> clientsSnap;
        synchronized (pool) {
            rowsSnap = new ArrayList<>(pool.rows);
            statesSnap = new LinkedHashMap<>(pool.states);
            clientsSnap = new LinkedHashMap<>(pool.clients);
        }

        // "connected" 是粘性状态：连接成功那一刻写入后再也不会自动失效，远端进程被关停、
        // 网络中断都发现不了，状态会一直显示"已连接"（绿点）。verifyOnline 档对缓存客户端发
        // 一次 listTools 当作在线校验：失败就把状态翻成 failed（而不是被 catch 吞掉继续显示
        // 绿色），成功则保持 connected（若上轮因瞬时错误被误标 failed，这一刻也会自动恢复）。
        // 改配置/空闲回收重建连接时另走 ensureConnections 重连。
        Map<String, List<Map<String, String>>> toolsOk = new LinkedHashMap<>();
        Map<String, String> flip = new LinkedHashMap<>();
        for (UserMcp row : rowsSnap) {
            if (!Integer.valueOf(1).equals(row.getEnabled())) continue;
            if (!"connected".equals(statesSnap.get(row.getName()))) continue;
            boolean online = verifyOnline;
            // withTools 档：与在线校验同一段远程 IO，但不把失败翻成 failed（见方法注释第三档）
            if (!online && !withTools) continue;
            McpSyncClient c = clientsSnap.get(row.getName());
            if (c == null) {
                if (online) flip.put(row.getName(), "failed:连接已失效");
                continue;
            }
            try {
                List<Map<String, String>> tools = new ArrayList<>();
                for (McpSchema.Tool t : c.listTools().tools()) {
                    tools.add(Map.of("name", t.name(), "description", brief(t.description())));
                }
                toolsOk.put(row.getName(), tools);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "连接已断开" : e.getMessage();
                if (online) {
                    flip.put(row.getName(), "failed:" + msg);
                    log.info("[MCP] uid={} server {} 在线校验未通过，状态翻为断开: {}", uid, row.getName(), msg);
                } else {
                    // 只补工具这一档：取不到就当「本轮没取到」，状态维持粘性 connected 不翻转
                    log.info("[MCP] uid={} server {} 工具清单获取失败（本轮不取，状态维持原样）: {}",
                            uid, row.getName(), msg);
                }
            }
        }
        if (verifyOnline) {
            // 翻转回写锁内做，且仅当池里仍是刚才校验的那个连接（期间配置重建/重连换了新 client
            // 就不覆盖——新连接的状态归新一轮流程管）
            if (!flip.isEmpty()) {
                synchronized (pool) {
                    flip.forEach((name, st) -> {
                        if (pool.clients.get(name) == clientsSnap.get(name)) pool.states.put(name, st);
                    });
                }
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (UserMcp row : rowsSnap) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("name", row.getName());
            // 智能体引用串：MCP Server 是个人资产，一律带归属 {uid}/{name}
            m.put("ref", row.getUid() + "/" + row.getName());
            m.put("url", row.getUrl());
            m.put("type", row.getType());
            m.put("enabled", Integer.valueOf(1).equals(row.getEnabled()));
            String st = statesSnap.get(row.getName());
            // 停用不建立连接，也不该报"失败"——给出明确语义
            String disp = Integer.valueOf(1).equals(row.getEnabled()) ? (st == null ? "unknown" : st) : "disabled";
            String flipped = flip.get(row.getName());
            if (flipped != null) disp = flipped;
            List<Map<String, String>> tools = toolsOk.getOrDefault(row.getName(), List.of());
            m.put("state", disp);
            m.put("connected", "connected".equals(disp));
            m.put("tools", tools);
            m.put("toolCount", tools.size());
            out.add(m);
        }
        return out;
    }

    /**
     * 当前用户已登记并启用的服务名。
     * <p>用途：判定「智能体按名字指定的 MCP 服务」在该用户名下是否真的存在（名字是弱匹配，
     * 同名不同服务、或根本没登记都会让智能体的意图落空，需要显式告知而不是静默跳过）。
     * 复用已建立的连接（不额外建连）。
     */
    public java.util.Set<String> serverNames(String uid) {
        if (uid == null || uid.isBlank()) return java.util.Set.of();
        UserPool pool = ensureConnections(uid);
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        synchronized (pool) {
            for (UserMcp row : pool.rows) {
                if (Integer.valueOf(1).equals(row.getEnabled()) && row.getName() != null) {
                    out.add(row.getName());
                }
            }
        }
        return out;
    }

    /**
     * 对未连上的服务补一次重连（仅 enabled 且状态非 connected 的，健康连接一律不动）。
     * <p>{@link #serverStatuses} 只做「已连接 → 在线校验」的单向翻转：远端恢复后，失败状态会
     * 一直停在 failed 上，必须整池重建（reload）才能恢复。而「刷新」的语义是把列表刷成当前
     * 真实状态，所以显式刷新时先补一次重连（只碰坏连接，代价与失败服务数成正比），
     * 再由 serverStatuses 正常出列表。只在用户点「刷新」时调用——进页面拉状态不重试，
     * 避免挂着几个死服务把首屏状态请求拖到超时。
     */
    public void retryBroken(String uid) {
        if (uid == null || uid.isBlank()) return;
        UserPool pool = ensureConnections(uid);

        // 锁内只做三件事：挑出要重连的目标、摘掉旧连接、置 connecting。connect+initialize 是
        // 秒级远程 IO（单服务最长 30s），持锁做会把 /status 等只读消费方堵在池锁上——此前
        // "刷新"期间切到智能体页，列表就被这把锁拖住转圈。临界区纪律见 {@link UserPool}。
        List<UserMcp> targets = new ArrayList<>();
        List<McpSyncClient> stale = new ArrayList<>();
        final int gen;
        synchronized (pool) {
            gen = pool.gen;
            for (UserMcp row : pool.rows) {
                if (!Integer.valueOf(1).equals(row.getEnabled())) continue;
                String name = row.getName();
                if (name == null || name.isBlank() || row.getUrl() == null || row.getUrl().isBlank()) continue;
                if ("connected".equals(pool.states.get(name)) && pool.clients.get(name) != null) continue;
                if ("connecting".equals(pool.states.get(name))) continue; // 后台任务正在连，别重复建连（会互踩+泄漏连接）
                McpSyncClient old = pool.clients.remove(name);
                if (old != null) stale.add(old);
                pool.states.put(name, "connecting");
                targets.add(row);
            }
        }
        closeQuietly(stale); // 旧连接的 closeGracefully 是远程 IO，锁外关闭

        // 锁外逐个重连（与后台 connectAsync 同为慢 IO；只碰坏连接，代价与失败服务数成正比）
        Map<String, McpSyncClient> ok = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (UserMcp row : targets) {
            String name = row.getName();
            String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType();
            McpSyncClient client = null;
            try {
                client = connect(name, row.getUrl(), type);
                client.initialize();
                ok.put(name, client);
                log.info("[MCP] uid={} server {} 刷新时重连成功", uid, name);
            } catch (Exception e) {
                if (client != null) {
                    try {
                        client.closeGracefully();
                    } catch (Exception ignore) {
                        // 半初始化的连接，关闭失败无需处理
                    }
                }
                bad.put(name, "failed:" + rootMessage(e));
                log.info("[MCP] uid={} server {} 刷新时重连仍失败: {}", uid, name, rootMessage(e));
            }
        }

        // 锁内回写：期间配置又变过（gen 前进）就整批丢弃，交给新一代任务接管
        synchronized (pool) {
            if (pool.gen != gen) {
                closeQuietly(new ArrayList<>(ok.values()));
                return;
            }
            ok.forEach((name, c) -> {
                pool.clients.put(name, c);
                pool.states.put(name, "connected");
            });
            bad.forEach(pool.states::put);
        }
    }

    /**
     * 重连某个用户的全部服务（增删/改地址后想立刻生效时用；不重建也会在下一次取工具时按指纹自动生效）。
     * 只清指纹触发后台重建，立即返回——期间各服务状态为 connecting，由轮询看到最终结果。
     */
    public void reload(String uid) {
        if (uid == null || uid.isBlank()) return;
        UserPool pool = pools.computeIfAbsent(uid, k -> new UserPool());
        synchronized (pool) {
            pool.fingerprint = ""; // 清指纹 → ensureConnections 重建
        }
        ensureConnections(uid);
    }

    /**
     * 临时连接测试（"添加服务"弹窗里用）：**不落配置、不进该用户的连接池**，连上取工具清单即关闭。
     * 用于"先测再存"，避免填错地址还得先保存再回来删。
     */
    public Map<String, Object> probe(String url, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            out.put("available", false);
            out.put("error", "地址为空");
            return out;
        }
        String t = (type == null || type.isBlank()) ? "streamable" : type;
        McpSyncClient client = null;
        try {
            client = connect("probe", url.trim(), t);
            client.initialize();
            List<Map<String, String>> tools = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                tools.add(Map.of("name", tool.name(), "description", brief(tool.description())));
            }
            out.put("available", true);
            out.put("tools", tools);
            out.put("toolCount", tools.size());
            return out;
        } catch (Exception e) {
            out.put("available", false);
            out.put("error", e.getMessage() == null ? "连接失败" : e.getMessage());
            return out;
        } finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception ignore) {
                    // 探测用连接，关闭失败无需处理
                }
            }
        }
    }

    /** 工具描述截断（界面展开用，避免超长描述撑坏卡片） */
    private static String brief(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 100 ? one.substring(0, 100) + "…" : one;
    }

    /**
     * 取异常链最深一层的消息。SDK 把初始化/传输失败层层包装（如 "Client failed to initialize
     * by explicit API call" ← TimeoutException / McpTransportException），只打 getMessage()
     * 会丢掉真正的原因（本次排查 context7 超时即栽在这里），状态与日志都展示根因。
     */
    private static String rootMessage(Throwable e) {
        Throwable cur = e, cause = e.getCause();
        while (cause != null && cause != cur) {
            cur = cause;
            cause = cur.getCause();
        }
        String msg = cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    /**
     * 按该用户当前配置对齐连接（懒加载；配置指纹变化才重建）。<b>只对齐清单、立即返回</b>：
     * 具体连接丢给后台线程（{@link #connectAsync}），调用方拿到池时未连上的服务状态是
     * {@code connecting}。这样 /status 之类只需清单的调用不再被 30s 级的握手拖住
     * （MCP 页面首屏曾要等全部服务连完才出列表）；需要工具的调用方（问答链路）再显式
     * {@link #awaitConnect} 等收敛。返回的一定是 {@link #pools} 里的活池：先对齐自己的池
     * （顺带刷新 lastAccess），再做全局空闲回收——顺序反了的话，回收会把自己刚取出的闲置池
     * 关掉并从 map 摘除，调用方随后按 uid 再 get 就拿不到，对着已出 map 的"僵尸池"继续用
     * （曾致 serverStatuses NPE）。
     */
    private UserPool ensureConnections(String uid) {
        UserPool pool = pools.computeIfAbsent(uid, k -> new UserPool());
        List<McpSyncClient> stale;
        synchronized (pool) {
            pool.lastAccess = System.currentTimeMillis();
            List<UserMcp> rows = userMcpMapper.selectList(new LambdaQueryWrapper<UserMcp>()
                    .eq(UserMcp::getUid, uid).orderByAsc(UserMcp::getCreateTime));
            if (rows.size() > MAX_SERVERS) {
                rows = rows.subList(0, MAX_SERVERS);
                log.warn("[MCP] uid={} 登记的 server 超过上限 {}，其余忽略", uid, MAX_SERVERS);
            }
            String fingerprint = fingerprintOf(rows);
            if (fingerprint.equals(pool.fingerprint)) {
                return pool; // 配置未变，沿用现有连接（空闲回收统一在锁外做，见方法尾）
            }
            stale = detachPool(pool);
            pool.gen++; // 新一代连接任务：旧后台任务回写前校验代次，过期结果直接丢弃
            pool.fingerprint = fingerprint;
            pool.rows = new ArrayList<>(rows);
            List<UserMcp> toConnect = new ArrayList<>();
            for (UserMcp row : rows) {
                String name = row.getName();
                if (name == null || name.isBlank() || row.getUrl() == null || row.getUrl().isBlank()) continue;
                if (!Integer.valueOf(1).equals(row.getEnabled())) {
                    pool.states.put(name, "disabled");   // 停用的服务直接跳过连接
                    continue;
                }
                pool.states.put(name, "connecting");
                toConnect.add(row);
            }
            final String fuid = uid;
            final UserPool p = pool;
            final int gen = pool.gen;
            final List<UserMcp> targets = List.copyOf(toConnect);
            pool.task = connectExecutor.submit(() -> connectAsync(fuid, p, gen, targets));
        }
        // 旧连接的 closeGracefully 是远程 IO（streamable 会发 DELETE 关会话），锁外关闭——
        // 放在临界区里曾让重建路径把池锁握在手里等网络
        closeQuietly(stale);
        // 空闲回收也不再持本池锁调用（它要拿别的池的锁）：锁内嵌套他池锁既拖长临界区又有
        // 理论上的锁序死锁面；两个分支现在统一在锁外做这一步
        evictIdlePools();
        return pool;
    }

    /**
     * 后台逐个建连（单用户内串行，与旧的同步行为一致）。每个 server 连完立刻在锁内落状态，
     * 让轮询中的 /status 尽快看到。回写前校验代次：期间配置又变了（gen 已前进）就丢弃结果
     * 并关掉刚建好的连接——新任务会按新配置重建。
     */
    private void connectAsync(String uid, UserPool pool, int gen, List<UserMcp> targets) {
        for (UserMcp row : targets) {
            if (Thread.currentThread().isInterrupted()) return;
            connectOne(uid, pool, gen, row);
        }
    }

    /**
     * 连接单个服务并回写状态（connectAsync 与失败重试共用）。
     * <p>
     * 状态回写紧跟 initialize 成功、在锁内完成；工具数日志的 listTools 单独兜 try 且绝不影响
     * 状态——这行曾裸奔在 try 之外，initialize 成功但 listTools 被远端掐断（deepwiki 实测的
     * "Remote host terminated the handshake"是间歇性的，握手能过、下一个请求被掐）时异常
     * 直接炸掉整个建连任务：该服务永远停在 connecting、排在后面的服务再也不连、也没人重试，
     * 用户看到的就是"每次点开都在连接中"。
     */
    private void connectOne(String uid, UserPool pool, int gen, UserMcp row) {
        String name = row.getName();
        String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType();
        McpSyncClient client = null;
        try {
            client = connect(name, row.getUrl(), type);
            client.initialize();
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception ignore) {
                    // 半初始化的连接，关闭失败无需处理
                }
            }
            synchronized (pool) {
                if (pool.gen != gen) return; // 配置已又变，本轮全部作废
                pool.states.put(name, "failed:" + rootMessage(e));
            }
            log.warn("[MCP] uid={} server {} ({}) 连接失败（跳过，不影响问答）: {}",
                    uid, name, type, rootMessage(e));
            scheduleRetry(uid, pool, gen, row, 1);
            return;
        }
        synchronized (pool) {
            if (pool.gen != gen) { // 同上：过期任务，关掉刚建的连接直接收工
                closeQuietly(List.of(client));
                return;
            }
            pool.clients.put(name, client);
            pool.states.put(name, "connected");
        }
        int toolCount = -1;
        try {
            toolCount = client.listTools().tools().size();
        } catch (Exception e) {
            // 仅影响日志的工具数展示；连接已建立、状态已落。远端若真死了，/status 的
            // 在线校验或下一次取工具会把状态翻成 failed
        }
        log.info("[MCP] uid={} server {} ({}) 连接成功，工具 {}",
                uid, name, type, toolCount < 0 ? "数未知（列表拉取失败）" : toolCount + " 个");
    }

    /**
     * 连接失败后的后台退避重试：按 {@link #RETRY_DELAYS_SECONDS} 逐轮加间隔，全部用尽停在
     * failed。执行前校验池代次与服务状态——只重试仍停在 failed 的服务：整池重建（gen 前进）
     * 或手动「刷新」（置 connecting）已接管的不碰，避免互踩建连。
     */
    private void scheduleRetry(String uid, UserPool pool, int gen, UserMcp row, int attempt) {
        if (attempt > RETRY_DELAYS_SECONDS.length) return;
        long delay = RETRY_DELAYS_SECONDS[attempt - 1];
        String name = row.getName();
        try {
            retryScheduler.schedule(() -> {
                synchronized (pool) {
                    if (pool.gen != gen) return;
                    String st = pool.states.get(name);
                    if (st == null || !st.startsWith("failed")) return;
                    pool.states.put(name, "connecting");
                }
                log.info("[MCP] uid={} server {} 第 {} 次后台重试", uid, name, attempt);
                connectOne(uid, pool, gen, row);
            }, delay, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 调度器已 shutdown（应用关闭中）等情况：保持 failed 即可，不影响主流程
        }
    }

    /**
     * 等待该用户在途的连接任务收敛（问答链路取工具前调用）。
     * initialize 有 30s 硬超时，任务必然结束；上限给 120s 只是防御性封顶（多 server 串行）。
     */
    private void awaitConnect(UserPool pool) {
        Future<?> t = pool.task;
        if (t == null) return;
        try {
            t.get(120, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[MCP] 等待连接任务收敛异常（按当前已连状态继续）: {}", e.getMessage());
        }
    }

    /**
     * 摘除池内全部连接并清空状态/清单，返回摘下的连接。调用方拿去在<b>锁外</b>关
     * （{@link #closeQuietly}）：closeGracefully 对 streamable 会发 DELETE 关会话，是远程 IO，
     * 不进临界区——否则持有池锁等网络，/status 等读者全被堵住。
     */
    private List<McpSyncClient> detachPool(UserPool pool) {
        List<McpSyncClient> stale = new ArrayList<>(pool.clients.values());
        pool.clients.clear();
        pool.states.clear();
        pool.rows = List.of();
        return stale;
    }

    /** 关闭一批已摘出池的连接（必须锁外调用）；单个关闭失败不影响其余 */
    private static void closeQuietly(List<McpSyncClient> clients) {
        for (McpSyncClient c : clients) {
            try {
                c.closeGracefully();
            } catch (Exception ignore) {
                // 关闭失败不影响重建/回收
            }
        }
    }

    /**
     * 回收长时间未使用的用户连接池：SSE/streamable 都是有状态长连接，用户下线不再问答后
     * 不能一直挂着（尤其 SSE 会占用服务端连接槽）。仅回收空闲的，正在用的不受影响。
     * <p>
     * 只关连接、清指纹，池壳留在 {@link #pools} 里（下次访问指纹为空自动重建连接）——
     * 不从 map 摘除：一旦摘除，「别的线程刚 computeIfAbsent/get 到引用」与「这里 remove」
     * 之间存在竞态，调用方会拿着已出 map 的僵尸池或 get 到 null（serverStatuses 曾因此 NPE）。
     * 池壳仅几个空字段，常驻开销可忽略；双重检查保证刚被使用的池不会被误关。
     */
    private void evictIdlePools() {
        long now = System.currentTimeMillis();
        List<McpSyncClient> stale = new ArrayList<>();
        for (Map.Entry<String, UserPool> e : pools.entrySet()) {
            UserPool pool = e.getValue();
            if (now - pool.lastAccess < IDLE_EVICT_MILLIS) continue;
            synchronized (pool) {
                if (System.currentTimeMillis() - pool.lastAccess < IDLE_EVICT_MILLIS) continue;
                stale.addAll(detachPool(pool));
                pool.fingerprint = "";
                log.info("[MCP] 用户 {} 的 MCP 连接池空闲超时已回收", e.getKey());
            }
        }
        closeQuietly(stale); // 锁外关闭：closeGracefully 是远程 IO，不进临界区
    }

    /** 配置指纹：服务名/地址/类型/启停任一变化都要重建连接 */
    private String fingerprintOf(List<UserMcp> rows) {
        StringBuilder sb = new StringBuilder();
        for (UserMcp r : rows) {
            sb.append(r.getName()).append('|').append(r.getUrl()).append('|')
                    .append(r.getType()).append('|').append(r.getEnabled()).append(";");
        }
        return sb.toString();
    }

    /** 按类型构建传输层并创建同步客户端（未 initialize） */
    private McpSyncClient connect(String name, String url, String type) {
        McpSchema.Implementation clientInfo = new McpSchema.Implementation("wen-qu", "1.0.0");
        McpClient.SyncSpec spec = McpClient.sync(buildTransport(url, type))
                .clientInfo(clientInfo)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(CONNECT_TIMEOUT);
        return spec.build();
    }

    /** streamable（默认）与 sse 两种传输；其余值按 streamable 处理 */
    private io.modelcontextprotocol.spec.McpClientTransport buildTransport(String url, String type) {
        if ("sse".equalsIgnoreCase(type)) {
            // SSE：url 拆 base + sse 端点（默认 /sse）
            String base = url, ssePath = "/sse";
            int idx = url.indexOf("://");
            int pathStart = idx > 0 ? url.indexOf('/', idx + 3) : -1;
            if (pathStart > 0) {
                base = url.substring(0, pathStart);
                String path = url.substring(pathStart);
                ssePath = path.endsWith("/sse") ? path : path + "/sse";
                if (path.endsWith("/sse")) {
                    ssePath = path;
                }
            }
            String finalBase = base;
            return HttpClientSseClientTransport.builder(finalBase).sseEndpoint(ssePath).build();
        }
        // streamable（默认）：builder 拆 baseUri + endpoint(path)，二者拼接为完整请求地址；
        // url 可能带路径（http://host:port/mcp）也可能不带（http://host:port，默认端点 /mcp）
        String baseUri = url;
        String endpoint = "/mcp";
        int schemeIdx = url.indexOf("://");
        int pathStart = schemeIdx > 0 ? url.indexOf('/', schemeIdx + 3) : -1;
        if (pathStart > 0) {
            baseUri = url.substring(0, pathStart);
            endpoint = url.substring(pathStart);
        }
        return HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .build();
    }

    /**
     * 单个用户的连接池：连接、状态与本次对齐的配置快照。
     * <p>
     * 临界区纪律：{@code synchronized (pool)} 只保护内存结构（clients/states/rows）的读写；
     * 一切远程 IO（initialize / listTools / closeGracefully）都在锁外做——否则持锁等网络
     * （远端挂起时 listTools 60s、重连握手 30s/服务）会把 /status、问答取工具这些只读消费方
     * 一起堵在锁上（智能体列表被 MCP 页在线校验/刷新拖住转圈的根因）。锁外操作的回写必须
     * 校验代次（gen）或比对 client 引用，防止过期结果覆盖新一轮的状态。
     */
    private static final class UserPool {
        /** 本次对齐的配置指纹（变化即重建） */
        String fingerprint = "";
        /** 连接代次：每次按新配置重建时 +1；后台任务回写前校验，过期结果丢弃 */
        int gen;
        /** 在途的后台连接任务（取工具前 join 它；配置重建后指向新任务，旧任务靠 gen 校验作废） */
        volatile Future<?> task;
        /** 最近一次被使用的时间（闲置回收用） */
        long lastAccess = System.currentTimeMillis();
        /** name → 已连接客户端 */
        final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
        /** name → 连接状态（connected / failed:原因 / disabled） */
        final Map<String, String> states = new LinkedHashMap<>();
        /** 本次对齐的服务清单（状态展示按此顺序） */
        List<UserMcp> rows = new ArrayList<>();
    }
}
