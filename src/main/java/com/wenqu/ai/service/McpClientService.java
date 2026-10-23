package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.McpCallLogMapper;
import com.wenqu.ai.mapper.UserMcpMapper;
import com.wenqu.ai.model.UserMcp;
import com.wenqu.ai.util.McpHeaders;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * - 配置变化（增删改/启停）按<b>单服务增量</b>对齐：只断开/重连受影响的那个服务，其余健康连接、
 *   在途建连与失败退避都不受牵连——任一开关只动它自己；「全部重连」才整池重建；
 * - 某个用户长时间不用（{@link #IDLE_EVICT_MILLIS}）其连接池会被关闭回收，避免长连接无限堆积。
 * <p>
 * 工具暴露：每个成功连接的 server 的工具列表转 {@link SyncMcpToolCallback}（Spring AI ToolCallback），
 * 由 {@code ChatService.enabledToolCallbacks()} 合并进请求；工具名自动带 server 前缀防冲突（Spring AI 默认行为）。
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
     * handshake）类瞬时故障多数在一两轮内恢复；全部用尽后停在 failed，等用户点「刷新」或
     * 重新启用/改配置（该服务重新对齐）。只在后台跑，绝不占 HTTP 请求线程。
     */
    private static final long[] RETRY_DELAYS_SECONDS = {30, 60, 120, 300, 600};
    /**
     * 工具清单缓存 TTL：问答/工作流每轮都要工具表，而每次 {@code listTools()} 都是远程往返
     * （实测 1~3.5s+，还会分页）。连接成功时已经拉过一次，先把那次结果预热进缓存；
     * 过期的旧清单先照常用、后台刷新，避免每 5 分钟固定卡一轮。
     */
    private static final long TOOLS_CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final UserMcpMapper userMcpMapper;
    /** 工具中文名（用户在 MCP 页手工起名）的解析：状态清单随工具条目带中文名，没起名带原名 */
    private final McpToolLabelService labelService;
    /** 自定义请求头的加解密：实体层只存密文，进出边界在 connect/probe/status 三处 */
    private final ConfigCryptoService cryptoService;
    /** 出站调用审计（channel=outbound）：外部工具每次执行落一行，写失败不影响调用 */
    private final McpCallLogMapper mcpCallLogMapper;

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

    public McpClientService(UserMcpMapper userMcpMapper, McpToolLabelService labelService,
                            ConfigCryptoService cryptoService, McpCallLogMapper mcpCallLogMapper) {
        this.userMcpMapper = userMcpMapper;
        this.labelService = labelService;
        this.cryptoService = cryptoService;
        this.mcpCallLogMapper = mcpCallLogMapper;
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
     * 返回某用户当前所有已启用 MCP 服务的工具（供 ChatService.enabledToolCallbacks() 合并）。
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
        return toolCallbacks(uid, onlyServers, null);
    }

    /**
     * 同上，附带调用方智能体（出站审计用；工作流节点没有智能体维度，传 null）。
     * <p>审计包装必须放在这一层而不是 {@code McpToolProvider}：工作流节点直接调本方法取回调，
     * 走 provider 那层会漏掉工作流；而普通 delegate 又会破坏工作流按裸名找工具的判据，
     * 所以用 {@link SyncMcpToolCallback} 子类包装。
     */
    public List<ToolCallback> toolCallbacks(String uid, java.util.Set<String> onlyServers, String agentId) {
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
        Map<String, CachedTools> cacheSnap;
        synchronized (pool) {
            snap = new LinkedHashMap<>(pool.clients);
            cacheSnap = new LinkedHashMap<>(pool.toolsCache);
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, McpSyncClient> entry : snap.entrySet()) {
            if (onlyServers != null && !onlyServers.contains(entry.getKey())) continue;
            McpSyncClient client = entry.getValue();
            CachedTools cached = cacheSnap.get(entry.getKey());
            if (cached != null && cached.fresh(now)) {
                for (McpSchema.Tool tool : cached.tools) callbacks.add(auditCallback(client, tool, uid, entry.getKey(), agentId));
                continue;
            }
            if (cached != null) {
                // 过期：旧清单先照常返回（问答不被远程列表拖住），后台单飞刷新，下一轮拿新的
                for (McpSchema.Tool tool : cached.tools) callbacks.add(auditCallback(client, tool, uid, entry.getKey(), agentId));
                refreshToolsAsync(pool, uid, entry.getKey(), client);
                continue;
            }
            try {
                List<McpSchema.Tool> tools = client.listTools().tools();
                for (McpSchema.Tool tool : tools) callbacks.add(auditCallback(client, tool, uid, entry.getKey(), agentId));
                putCacheIfCurrent(pool, entry.getKey(), client, tools);
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

    /** 回调统一走审计包装（SyncMcpToolCallback 子类：保留裸名，工作流按裸名找工具不受影响） */
    private ToolCallback auditCallback(McpSyncClient client, McpSchema.Tool tool, String uid, String server,
                                       String agentId) {
        return new McpOutboundAuditCallback(client, tool, mcpCallLogMapper, uid, server, agentId);
    }

    /** 写入工具缓存：连接已换代（快照时的 client 不在池里了）就丢弃，避免旧连接的清单盖到新连接上 */
    private static void putCacheIfCurrent(UserPool pool, String name, McpSyncClient client,
                                          List<McpSchema.Tool> tools) {
        synchronized (pool) {
            if (pool.clients.get(name) != client) return;
            pool.toolsCache.put(name, new CachedTools(List.copyOf(tools), System.currentTimeMillis()));
        }
    }

    /** 过期清单的后台刷新（每服务单飞）：失败继续用旧清单，下一轮再试 */
    private void refreshToolsAsync(UserPool pool, String uid, String name, McpSyncClient client) {
        synchronized (pool) {
            if (!pool.toolsRefreshing.add(name)) return; // 已有刷新在跑，别堆任务
        }
        try {
            connectExecutor.submit(() -> {
                try {
                    List<McpSchema.Tool> tools = client.listTools().tools();
                    putCacheIfCurrent(pool, name, client, tools);
                    log.debug("[MCP] uid={} server {} 工具清单后台刷新完成（{} 个）", uid, name, tools.size());
                } catch (Exception e) {
                    log.debug("[MCP] uid={} server {} 工具清单后台刷新失败（继续用旧清单）: {}",
                            uid, name, e.getMessage());
                } finally {
                    synchronized (pool) { pool.toolsRefreshing.remove(name); }
                }
            });
        } catch (Exception e) {
            // 调度器已 shutdown（应用关闭中）：清掉标记，不影响本轮
            synchronized (pool) { pool.toolsRefreshing.remove(name); }
        }
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
                List<McpSchema.Tool> fresh = c.listTools().tools();
                // 管理页/画布这一档本来就是实时远程，顺手回填缓存（下一次问答不用再等）
                putCacheIfCurrent(pool, row.getName(), c, fresh);
                List<Map<String, String>> tools = new ArrayList<>();
                for (McpSchema.Tool t : fresh) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    // 手工中文名（管理页工具清单里改的）；没起名就写裸名，前端按裸名展示
                    m.put("label", java.util.Objects.requireNonNullElse(labelService.label(uid, t.name()), t.name()));
                    m.put("description", brief(t.description()));
                    tools.add(m);
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
            // 脱敏回显：只给「有没有凭据」与键名，值永不出服务端
            m.put("hasHeaders", row.getHeaders() != null && !row.getHeaders().isBlank());
            m.put("headerKeys", McpHeaders.keys(cryptoService.decrypt(row.getHeaders())));
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
     * 一直停在 failed 上，等不到自动恢复。而「刷新」的语义是把列表刷成当前真实状态，所以显式
     * 刷新时先补一次重连（只碰坏连接，代价与失败服务数成正比），再由 serverStatuses 正常出列表。
     * 只在用户点「刷新」时调用——进页面拉状态不重试，避免挂着几个死服务把首屏状态请求拖到超时。
     */
    public void retryBroken(String uid) {
        if (uid == null || uid.isBlank()) return;
        UserPool pool = ensureConnections(uid);

        // 锁内只做三件事：挑出要重连的目标、摘掉旧连接、置 connecting。connect+initialize 是
        // 秒级远程 IO（单服务最长 30s），持锁做会把 /status 等只读消费方堵在池锁上——此前
        // "刷新"期间切到智能体页，列表就被这把锁拖住转圈。临界区纪律见 {@link UserPool}。
        List<ConnectTarget> targets = new ArrayList<>();
        List<McpSyncClient> stale = new ArrayList<>();
        synchronized (pool) {
            for (UserMcp row : pool.rows) {
                if (!Integer.valueOf(1).equals(row.getEnabled())) continue;
                String name = row.getName();
                if (name == null || name.isBlank() || row.getUrl() == null || row.getUrl().isBlank()) continue;
                if ("connected".equals(pool.states.get(name)) && pool.clients.get(name) != null) continue;
                if ("connecting".equals(pool.states.get(name))) continue; // 后台任务正在连，别重复建连（会互踩+泄漏连接）
                stale.addAll(detachServer(pool, name));
                pool.states.put(name, "connecting");
                pool.specs.put(name, specOf(row));
                targets.add(new ConnectTarget(row, bumpToken(pool, name)));
            }
        }
        closeQuietly(stale); // 旧连接的 closeGracefully 是远程 IO，锁外关闭

        // 锁外逐个重连（与后台 connectAsync 同为慢 IO；只碰坏连接，代价与失败服务数成正比）
        Map<String, McpSyncClient> ok = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (ConnectTarget target : targets) {
            UserMcp row = target.row();
            String name = row.getName();
            String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType();
            McpSyncClient client = null;
            try {
                client = connect(name, row.getUrl(), type, headersOf(row));
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

        // 锁内回写：期间配置又变过（该服务的建连代次前进）就丢弃，交给新的对齐流程接管
        List<McpSyncClient> discard = new ArrayList<>();
        synchronized (pool) {
            for (ConnectTarget target : targets) {
                String name = target.row().getName();
                if (!tokenCurrent(pool, name, target.token())) {
                    McpSyncClient c = ok.remove(name);
                    if (c != null) discard.add(c);
                    bad.remove(name);
                    continue;
                }
                McpSyncClient c = ok.get(name);
                if (c != null) {
                    pool.clients.put(name, c);
                    pool.states.put(name, "connected");
                }
            }
            bad.forEach(pool.states::put);
        }
        closeQuietly(discard);
    }

    /**
     * 配置变更（增删改/启停）后立刻对齐连接。按单服务增量：只连新增/被改动的服务、只断已删/
     * 已停用/地址变了的那一个，其余健康连接与在途建连原样保留——任一开关只影响它自己。
     * 立即返回，新建连在后台进行，最终状态由 /status 轮询看到。
     */
    public void refresh(String uid) {
        if (uid == null || uid.isBlank()) return;
        ensureConnections(uid);
    }

    /**
     * 重连某个用户的<b>全部</b>服务（「全部重连」按钮）：整池拆掉按当前配置重建。
     * 立即返回——期间各服务状态为 connecting，由轮询看到最终结果。
     */
    public void reload(String uid) {
        if (uid == null || uid.isBlank()) return;
        UserPool pool = pools.computeIfAbsent(uid, k -> new UserPool());
        List<McpSyncClient> stale;
        synchronized (pool) {
            stale = detachPool(pool); // 摘掉全部连接并作废在途任务
            pool.fingerprint = "";    // 清指纹 → ensureConnections 按当前配置整池重连
        }
        closeQuietly(stale);
        ensureConnections(uid);
    }

    /**
     * 临时连接测试（"添加服务"弹窗里用）：**不落配置、不进该用户的连接池**，连上取工具清单即关闭。
     * 用于"先测再存"，避免填错地址还得先保存再回来删。
     *
     * @param headersJson 本次显式填写的请求头（明文 JSON，已由 Controller 校验）；为空时若给了
     *                    {@code serverId} 则取该服务已保存的凭据——编辑态回显只有键名、拿不到明文，
     *                    不这样「测试连接」会对已存凭据的服务必然失败
     */
    public Map<String, Object> probe(String url, String type, String headersJson, boolean headersExplicit,
                                     String uid, String serverId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            out.put("available", false);
            out.put("error", "地址为空");
            return out;
        }
        String t = (type == null || type.isBlank()) ? "streamable" : type;
        McpSyncClient client = null;
        try {
            client = connect("probe", url.trim(), t,
                    resolveProbeHeaders(headersJson, headersExplicit, uid, serverId));
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

    /**
     * 探测用的请求头：显式传了 headers 就以它为准（空对象=明确不带凭据），
     * 没传才回落到本人已保存的服务凭据。
     */
    private Map<String, String> resolveProbeHeaders(String headersJson, boolean headersExplicit,
                                                    String uid, String serverId) {
        if (headersExplicit) return headersJson == null ? Map.of() : McpHeaders.parse(headersJson);
        if (serverId == null || serverId.isBlank() || uid == null || uid.isBlank()) return Map.of();
        try {
            UserMcp row = userMcpMapper.selectById(serverId);
            return row == null || !uid.equals(row.getUid()) ? Map.of() : headersOf(row);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 服务已保存的请求头（解密 → 宽松解析）。解密/解析失败按「无凭据」处理并告警：
     * 连接会因 401 变红，这比抛异常打断建连更符合「MCP 故障不影响主链路」。
     */
    private Map<String, String> headersOf(UserMcp row) {
        String stored = row.getHeaders();
        if (stored == null || stored.isBlank()) return Map.of();
        Map<String, String> headers = McpHeaders.parse(cryptoService.decrypt(stored));
        if (headers.isEmpty()) {
            log.warn("[MCP] uid={} server {} 的请求头无法解密或格式非法，本次按无凭据连接",
                    row.getUid(), row.getName());
        }
        return headers;
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
     * 按该用户当前配置对齐连接（懒加载；配置指纹变化才做增量对齐）。<b>只对齐清单、立即返回</b>：
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
        List<McpSyncClient> stale = List.of();
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
            // 配置变了：按单服务比对，只动受影响的那个（健康连接、在途建连、失败退避都不牵连）
            List<ConnectTarget> toConnect = new ArrayList<>();
            stale = reconcile(pool, rows, toConnect);
            pool.fingerprint = fingerprint;
            pool.rows = new ArrayList<>(rows);
            if (!toConnect.isEmpty()) {
                final String fuid = uid;
                final UserPool p = pool;
                final List<ConnectTarget> targets = List.copyOf(toConnect);
                pool.tasks.removeIf(Future::isDone);
                pool.tasks.add(connectExecutor.submit(() -> connectAsync(fuid, p, targets)));
            }
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
     * 把池内连接对齐到最新配置，返回需要锁外关闭的旧连接（调用方持池锁）。
     * <p>
     * 逐服务判定：已删除/已停用/地址或类型变了 → 摘连接；新增/重新启用/配置变了 → 排进待连清单；
     * 配置没变的健康连接与在途任务原样保留。这就是「任一开关只动它自己」的落点——
     * 每次变化都整池重建时，任何一个服务的启停都会把所有服务断开重握手（坏服务还要再失败一轮）。
     */
    private List<McpSyncClient> reconcile(UserPool pool, List<UserMcp> rows, List<ConnectTarget> toConnect) {
        List<McpSyncClient> stale = new ArrayList<>();
        Set<String> alive = new LinkedHashSet<>();
        for (UserMcp row : rows) {
            String name = row.getName();
            if (name == null || name.isBlank() || row.getUrl() == null || row.getUrl().isBlank()) continue;
            alive.add(name);
            String spec = specOf(row);
            if (!Integer.valueOf(1).equals(row.getEnabled())) {
                stale.addAll(detachServer(pool, name));
                pool.states.put(name, "disabled"); // 停用的服务直接跳过连接
                continue;
            }
            if (pool.clients.containsKey(name) && spec.equals(pool.specs.get(name))) {
                // 失败态不在这一步「复活」：在线校验判死的连接还挂在池里，改别的服务不能把它刷回绿点
                String st = pool.states.get(name);
                if (st == null || !st.startsWith("failed")) pool.states.put(name, "connected");
                continue;
            }
            if ("connecting".equals(pool.states.get(name)) && spec.equals(pool.specs.get(name))) {
                continue; // 同一配置已有在途任务，别重复建连（会互踩+泄漏连接）
            }
            stale.addAll(detachServer(pool, name)); // 旧配置的连接与在途任务一并作废
            pool.states.put(name, "connecting");
            pool.specs.put(name, spec);
            toConnect.add(new ConnectTarget(row, bumpToken(pool, name)));
        }
        // 清单里已不存在的服务：摘连接、作废在途任务、清状态
        Set<String> known = new LinkedHashSet<>(pool.states.keySet());
        known.addAll(pool.clients.keySet());
        known.addAll(pool.specs.keySet());
        for (String name : known) {
            if (alive.contains(name)) continue;
            stale.addAll(detachServer(pool, name));
            pool.states.remove(name);
        }
        pool.states.keySet().retainAll(alive);
        pool.specs.keySet().retainAll(alive);
        pool.tokens.keySet().retainAll(alive);
        return stale;
    }

    /** 服务连接配置（地址+类型+请求头摘要）：变了就必须重连，没变就沿用现有连接 */
    private static String specOf(UserMcp row) {
        String url = row.getUrl() == null ? "" : row.getUrl().trim();
        String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType().trim();
        return url + "|" + type.toLowerCase() + "|h:" + McpHeaders.digest(row.getHeaders());
    }

    /** 摘掉某服务的连接并清其目标配置（调用方持池锁）；返回的 client 由调用方锁外关闭 */
    private static List<McpSyncClient> detachServer(UserPool pool, String name) {
        McpSyncClient c = pool.clients.remove(name);
        pool.specs.remove(name);
        pool.toolsCache.remove(name);
        pool.toolsRefreshing.remove(name);
        bumpToken(pool, name); // 作废该服务的在途建连任务
        return c == null ? List.of() : List.of(c);
    }

    /** 该服务的建连代次 +1（持有池锁时调用） */
    private static int bumpToken(UserPool pool, String name) {
        return pool.tokens.merge(name, 1, Integer::sum);
    }

    /** 在途任务是否仍代表该服务的当前意图（持有池锁时调用） */
    private static boolean tokenCurrent(UserPool pool, String name, int token) {
        return Integer.valueOf(token).equals(pool.tokens.get(name));
    }

    /**
     * 后台逐个建连（单用户内串行，与旧的同步行为一致）。每个 server 连完立刻在锁内落状态，
     * 让轮询中的 /status 尽快看到。回写前校验该服务的建连代次：期间它被停用/删除/改配置
     * （代次已前进）就丢弃结果并关掉刚建好的连接——新的对齐流程会按新配置重连。
     */
    private void connectAsync(String uid, UserPool pool, List<ConnectTarget> targets) {
        for (ConnectTarget target : targets) {
            if (Thread.currentThread().isInterrupted()) return;
            connectOne(uid, pool, target, 0);
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
     *
     * @param attempt 已用掉的重试次数（0=首次连接）；失败后按它推进退避序列
     */
    private void connectOne(String uid, UserPool pool, ConnectTarget target, int attempt) {
        UserMcp row = target.row();
        String name = row.getName();
        String type = row.getType() == null || row.getType().isBlank() ? "streamable" : row.getType();
        McpSyncClient client = null;
        try {
            client = connect(name, row.getUrl(), type, headersOf(row));
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
                if (!tokenCurrent(pool, name, target.token())) return; // 该服务已停用/删除/改配置，本轮作废
                pool.states.put(name, "failed:" + rootMessage(e));
            }
            log.warn("[MCP] uid={} server {} ({}) 连接失败（跳过，不影响问答）: {}",
                    uid, name, type, rootMessage(e));
            scheduleRetry(uid, pool, target, attempt + 1);
            return;
        }
        boolean keep;
        synchronized (pool) {
            keep = tokenCurrent(pool, name, target.token());
            if (keep) {
                pool.clients.put(name, client);
                pool.specs.put(name, specOf(row));
                pool.states.put(name, "connected");
            }
        }
        if (!keep) {
            closeQuietly(List.of(client)); // 过期任务：关掉刚建的连接直接收工（锁外关，远程 IO）
            return;
        }
        int toolCount = -1;
        try {
            List<McpSchema.Tool> tools = client.listTools().tools();
            toolCount = tools.size();
            // 同一次结果直接预热工具缓存：连接后的第一轮问答不必再付一次远程 listTools
            putCacheIfCurrent(pool, name, client, tools);
        } catch (Exception e) {
            // 仅影响日志的工具数展示；连接已建立、状态已落。远端若真死了，/status 的
            // 在线校验或下一次取工具会把状态翻成 failed
        }
        log.info("[MCP] uid={} server {} ({}) 连接成功，工具 {}",
                uid, name, type, toolCount < 0 ? "数未知（列表拉取失败）" : toolCount + " 个");
    }

    /**
     * 连接失败后的后台退避重试：按 {@link #RETRY_DELAYS_SECONDS} 逐轮加间隔，全部用尽停在
     * failed。执行前校验该服务的建连代次与服务状态——只重试仍停在 failed 的服务：被停用/删除/
     * 改配置（代次前进）或手动「刷新」（置 connecting）已接管的不碰，避免互踩建连。
     */
    private void scheduleRetry(String uid, UserPool pool, ConnectTarget target, int attempt) {
        if (attempt > RETRY_DELAYS_SECONDS.length) return;
        long delay = RETRY_DELAYS_SECONDS[attempt - 1];
        String name = target.row().getName();
        try {
            retryScheduler.schedule(() -> {
                synchronized (pool) {
                    if (!tokenCurrent(pool, name, target.token())) return;
                    String st = pool.states.get(name);
                    if (st == null || !st.startsWith("failed")) return;
                    pool.states.put(name, "connecting");
                }
                log.info("[MCP] uid={} server {} 第 {} 次后台重试", uid, name, attempt);
                connectOne(uid, pool, target, attempt);
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
        List<Future<?>> pending;
        synchronized (pool) {
            pool.tasks.removeIf(Future::isDone); // 已完成的任务不必再等，也顺带清掉引用
            pending = new ArrayList<>(pool.tasks);
        }
        if (pending.isEmpty()) return;
        long deadline = System.currentTimeMillis() + 120_000;
        for (Future<?> t : pending) {
            try {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return;
                t.get(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[MCP] 等待连接任务收敛异常（按当前已连状态继续）: {}", e.getMessage());
            }
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
        pool.specs.clear();
        pool.toolsCache.clear();
        pool.toolsRefreshing.clear();
        pool.rows = List.of();
        // 作废全部在途任务：整池重建后旧任务的连接不能再回写
        pool.tokens.replaceAll((name, token) -> token + 1);
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

    /** 配置指纹：服务名/地址/类型/启停/请求头任一变化都要重建连接（请求头只比摘要，不把凭据塞进指纹串） */
    private String fingerprintOf(List<UserMcp> rows) {
        StringBuilder sb = new StringBuilder();
        for (UserMcp r : rows) {
            sb.append(r.getName()).append('|').append(r.getUrl()).append('|')
                    .append(r.getType()).append('|').append(r.getEnabled())
                    .append("|h:").append(McpHeaders.digest(r.getHeaders())).append(';');
        }
        return sb.toString();
    }

    /** 按类型构建传输层并创建同步客户端（未 initialize） */
    private McpSyncClient connect(String name, String url, String type, Map<String, String> headers) {
        McpSchema.Implementation clientInfo = new McpSchema.Implementation("wen-qu", "1.0.0");
        McpClient.SyncSpec spec = McpClient.sync(buildTransport(url, type, headers))
                .clientInfo(clientInfo)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(CONNECT_TIMEOUT);
        return spec.build();
    }

    /** streamable（默认）与 sse 两种传输；其余值按 streamable 处理。请求头由 customizer 逐请求注入 */
    private io.modelcontextprotocol.spec.McpClientTransport buildTransport(String url, String type,
                                                                           Map<String, String> headers) {
        // 0.18.3 的签名是 (builder, method, uri, body, ctx)——第 4 参是请求体，不是 userAgent
        McpSyncHttpClientRequestCustomizer customizer = headers == null || headers.isEmpty() ? null
                : (reqBuilder, method, uri, body, ctx) -> headers.forEach(reqBuilder::header);
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
            HttpClientSseClientTransport.Builder builder =
                    HttpClientSseClientTransport.builder(finalBase).sseEndpoint(ssePath);
            if (customizer != null) builder.httpRequestCustomizer(customizer);
            return builder.build();
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
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(endpoint)
                .resumableStreams(false)
                .openConnectionOnStartup(false);
        if (customizer != null) builder.httpRequestCustomizer(customizer);
        return builder.build();
    }

    /**
     * 单个用户的连接池：连接、状态与本次对齐的配置快照。
     * <p>
     * 临界区纪律：{@code synchronized (pool)} 只保护内存结构（clients/states/rows）的读写；
     * 一切远程 IO（initialize / listTools / closeGracefully）都在锁外做——否则持锁等网络
     * （远端挂起时 listTools 60s、重连握手 30s/服务）会把 /status、问答取工具这些只读消费方
     * 一起堵在锁上（智能体列表被 MCP 页在线校验/刷新拖住转圈的根因）。锁外操作的回写必须
     * 比对该服务的建连代次（{@code tokens}）或 client 引用，防止过期结果覆盖新一轮的状态。
     */
    private static final class UserPool {
        /** 本次对齐的配置指纹（变化才做增量对齐） */
        String fingerprint = "";
        /** name → 建连代次：该服务每次被重新调度（启停/改配置/删除/整池重建）时 +1，旧任务回写前比对 */
        final Map<String, Integer> tokens = new LinkedHashMap<>();
        /** name → 当前连接/在途任务对应的配置（地址+类型+请求头摘要）；与实际不符即需断开重连 */
        final Map<String, String> specs = new LinkedHashMap<>();
        /** 在途的后台连接任务（取工具前逐个 join；完成即被清理） */
        final List<Future<?>> tasks = new ArrayList<>();
        /** 最近一次被使用的时间（闲置回收用） */
        long lastAccess = System.currentTimeMillis();
        /** name → 已连接客户端 */
        final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
        /** name → 连接状态（connected / failed:原因 / disabled） */
        final Map<String, String> states = new LinkedHashMap<>();
        /** name → 工具清单缓存（连接成功时预热；TTL 内问答/工作流不再远程 listTools） */
        final Map<String, CachedTools> toolsCache = new LinkedHashMap<>();
        /** name → 是否有过期清单的后台刷新在跑（单飞：TTL 到期只发一次远程） */
        final java.util.Set<String> toolsRefreshing = new LinkedHashSet<>();
        /** 本次对齐的服务清单（状态展示按此顺序） */
        List<UserMcp> rows = new ArrayList<>();
    }

    /** 一份工具清单缓存：工具对象不可变，缓存本身只记拉取时刻 */
    private record CachedTools(List<McpSchema.Tool> tools, long fetchedAt) {
        boolean fresh(long now) {
            return now - fetchedAt < TOOLS_CACHE_TTL_MILLIS;
        }
    }

    /** 一次建连任务：目标服务 + 调度时的建连代次（回写前校验，过期即丢弃） */
    private record ConnectTarget(UserMcp row, int token) {
    }
}
