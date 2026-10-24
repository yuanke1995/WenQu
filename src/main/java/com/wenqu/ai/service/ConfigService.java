package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.ConfigMapper;
import com.wenqu.ai.model.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型配置服务：DB（c_ai_config）存储 + 内存缓存
 * <p>
 * - 启动时表空则从 yml/env 默认值灌入
 * - 可编辑白名单：vision.prompt / 检索与解析参数等（保存即生效；
 *   chat.model / embedding.model / vision.model 已退役——业务模型归属到使用者：
 *   知识库绑定向量/解析视觉/检索重排，聊天走会话覆盖>个人默认；问答对生成（parse.qaModel
 *   已退役）与 GraphRAG 抽取（graphrag.modelRef 已退役）均回落库主个人默认聊天模型；
 *   仅 eval.judgeModel 等
 *   管理员自用工具保留全局）
 * - chat.baseUrl / chat.apiKey / chat.completionsPath 支持跨厂商热切换（DynamicOpenAiChatModel
 *   每次请求校验配置指纹、变化即重建，配合 Redis 广播多实例同步生效）；embedding / vision / rerank
 *   各组的网关三要素保留为「遗留纯模型名」的回落网关，不再作为运行时默认
 * - 敏感项（*.apiKey / *.clientSecret）RSA 加密入库（ConfigCryptoService）：启动自动迁移存量明文，读取透明解密
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigService {

    // 可编辑白名单与参数分层（原 EDITABLE / TIER 两张 Map）已收敛到 classpath:config-schema.json，
    // 由 ConfigSchemaService 提供 isEditable() / tier() / help() / validate()（字段定义单一来源）。
    // MCP Server 已下沉为个人资产（c_ai_user_mcp），平台层面只剩 tool.enabled 总开关。

    private final ConfigMapper configMapper;
    private final AppProperties properties;
    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;
    /** @Lazy 打破循环依赖：KeywordIndexService 构造依赖本类，仅引擎切换校验/重建时使用 */
    private final KeywordIndexService keywordIndexService;
    /** 敏感项（见 {@link #isSensitiveKey}）RSA 加解密 */
    private final ConfigCryptoService crypto;
    /** 配置字段定义（可编辑白名单 / 分层 / 说明 / 校验规则的唯一来源） */
    private final com.wenqu.ai.config.ConfigSchemaService schema;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    /**
     * 配置变更监听：只在**值真的变了**时通知（reload / update / reset / putInternal 四条写入路径统一发）。
     * <p>用途是「只能在 build 期定、但又必须保存即生效」的配置——当前是 {@code memory.*}：
     * flush 节流、consolidation 上限、硬上限都烤在常驻 HarnessAgent 实例里，不重建就会
     * 「管理员关了总闸，旧会话还在写」。监听器只标记实例失效，不阻塞保存。
     */
    private final List<java.util.function.Consumer<Set<String>>> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public ConfigService(ConfigMapper configMapper, AppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties,
                         @org.springframework.context.annotation.Lazy KeywordIndexService keywordIndexService,
                         ConfigCryptoService crypto,
                         com.wenqu.ai.config.ConfigSchemaService schema) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
        this.keywordIndexService = keywordIndexService;
        this.crypto = crypto;
        this.schema = schema;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // 缺失的默认项自动补入（存量升级场景：新增 key 自动注入，不覆盖已有配置）
        ensureDefaults();
        reload();
        // 存量明文密钥（历史版本明文入库的 *.apiKey）自动迁移为 RSA 密文
        migratePlainSecrets();
        startRedisConfigSync();
        auditDefaultOnSwitches();
        log.info("模型配置加载完成，共 {} 项", cache.size());
    }

    /**
     * 启动自检：schema 里声明「默认开」的开关（type=switch + def=true）有没有落点。
     * <p>为什么查这个：这类键若既无配置行、也未在 {@link #defaults()} 登记，读到的就是关——
     * 设置页写着开、行为是关，而且不报错（{@code chat.resumeEnabled} 上线时就是这么整条静默不生效，
     * 排查时最先怀疑的是代码而不是配置读取）。ensureDefaults 会把 defaults() 的键全部灌成配置行，
     * 所以「无配置行」等价于「两处都没登记」。
     * <p>只告警、不自动补一行：补行会把「忘了登记」变成「这是管理员设的值」，掩盖问题而不是暴露它。
     */
    private void auditDefaultOnSwitches() {
        try {
            List<String> missing = new ArrayList<>();
            Map<String, String> seeds = defaults();
            for (String key : schema.defaultOnSwitchKeys()) {
                if (cache.get(key) == null && !seeds.containsKey(key)) {
                    missing.add(key);
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            log.warn("[Config][自检] {} 个「默认开」的开关既无配置行也未在 defaults() 登记，读到按关闭处理"
                    + "（确认调用方用的是 getBoolean(key, true)，否则整条特性静默不生效）: {}",
                    missing.size(), missing);
        } catch (Exception e) {
            log.warn("[Config][自检] 默认开开关自检失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 存量明文密钥迁移：敏感项（*.apiKey / *.clientSecret）非 RSA: 前缀的值加密回写 DB 与缓存。
     * 读取兼容明文（get 透明解密对无前缀值原样返回），迁移只为尽快消除库中明文；
     * 多实例部署由 Redis 广播 reload 触发各自迁移，幂等。
     */
    private void migratePlainSecrets() {
        try {
            Map<String, String> encrypted = new HashMap<>();
            for (Map.Entry<String, String> e : cache.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (isSensitiveKey(k) && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
                    encrypted.put(k, crypto.encrypt(v));
                }
            }
            if (encrypted.isEmpty()) {
                return;
            }
            for (Map.Entry<String, String> e : encrypted.entrySet()) {
                Config c = configMapper.selectById(e.getKey());
                if (c != null) {
                    c.setConfigValue(e.getValue());
                    configMapper.updateById(c);
                }
            }
            Map<String, String> newCache = new HashMap<>(cache);
            newCache.putAll(encrypted);
            cache = newCache;
            syncProperties();
            log.info("[Config] 存量明文密钥已迁移为 RSA 加密存储: {}", encrypted.keySet());
        } catch (Exception e) {
            log.warn("[Config] 明文密钥加密迁移失败（不影响启动，读取兼容明文）: {}", e.getMessage());
        }
    }

    /** 全量重读 c_ai_config 进缓存（本地更新 / Redis 订阅通知 / schedule 包周期兜底均调用） */
    public void reload() {
        try {
            Map<String, String> before = cache;
            List<Config> all = configMapper.selectList(new LambdaQueryWrapper<Config>());
            Map<String, String> map = new HashMap<>();
            for (Config c : all) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
            cache = map;
            syncProperties();
            notifyConfigChanged(diff(before, map));
        } catch (Exception e) {
            log.warn("[Config] 配置重载失败: {}", e.getMessage());
        }
    }

    /** 注册配置变更监听（只在值真的变化时回调；回调异常只告警，不影响保存）。 */
    public void addChangeListener(java.util.function.Consumer<Set<String>> listener) {
        if (listener != null) changeListeners.add(listener);
    }

    private void notifyConfigChanged(Set<String> changed) {
        if (changed == null || changed.isEmpty()) return;
        for (java.util.function.Consumer<Set<String>> listener : changeListeners) {
            try {
                listener.accept(changed);
            } catch (Exception e) {
                log.warn("[Config] 配置变更监听失败 {}: {}", changed, e.getMessage());
            }
        }
    }

    private static Set<String> diff(Map<String, String> before, Map<String, String> after) {
        Map<String, String> b = before == null ? Map.of() : before;
        Map<String, String> a = after == null ? Map.of() : after;
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : b.entrySet()) {
            if (!java.util.Objects.equals(e.getValue(), a.get(e.getKey()))) changed.add(e.getKey());
        }
        for (String k : a.keySet()) {
            if (!b.containsKey(k)) changed.add(k);
        }
        return changed;
    }

    /**
     * 多实例配置同步：daemon 线程订阅 Redis channel，任意实例保存配置后广播，
     * 本实例收到即全量重载缓存（保存即生效跨实例成立）。Redis 不可用时仅告警不影响启动。
     * 订阅线程是永久阻塞的事件监听（不适合进线程池）；周期兜底 reload
     * （订阅断线期间错过的变更由轮询补齐，每 5 分钟）已移至 schedule 包 ScheduleCenter。
     */
    private void startRedisConfigSync() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = new Jedis(redisProperties.getHost(), redisProperties.getPort(), 5000)) {
                    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
                        jedis.auth(redisProperties.getPassword());
                    }
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            reload();
                            log.info("[Config] 收到配置变更广播，已刷新缓存");
                        }
                    }, CONFIG_CHANNEL);
                } catch (Exception e) {
                    log.warn("[Config] Redis 配置同步订阅中断，5s 后重连: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "config-redis-sync");
        t.setDaemon(true);
        t.start();
    }

    /** 本地保存后广播（其他实例订阅刷新；Redis 异常不影响保存结果） */
    private void publishConfigChanged() {
        try {
            redisTemplate.convertAndSend(CONFIG_CHANNEL, "changed");
        } catch (Exception e) {
            log.debug("[Config] 配置变更广播失败: {}", e.getMessage());
        }
    }

    /** 遍历 defaults()，DB 中缺失的 key 自动灌入默认值（单条失败不影响其余） */
    private void ensureDefaults() {
        for (Map.Entry<String, String> e : defaults().entrySet()) {
            // 个人专属键（schema personalOnly）没有全局槽位：种一行全局值也读不到（get 会直接返回空），
            // 只会在库里留下一条永远不生效的“死配置”。rerank.model 就是这种键，故不灌库。
            if (schema.isPersonalOnly(e.getKey())) continue;
            try {
                Long cnt = configMapper.selectCount(new LambdaQueryWrapper<Config>()
                        .eq(Config::getConfigKey, e.getKey()));
                if (cnt == null || cnt == 0) {
                    Config c = new Config();
                    c.setConfigKey(e.getKey());
                    // 敏感项默认值灌入即加密（RSA: 前缀密文）
                    c.setConfigValue(isSensitiveKey(e.getKey()) ? crypto.encrypt(e.getValue()) : e.getValue());
                    c.setRemark(schema.helpOrDefault(e.getKey(), "只读配置"));
                    configMapper.insert(c);
                }
            } catch (Exception ex) {
                log.warn("配置默认值灌入失败: {} error={}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** 从 yml/env 读取默认值 */
    private Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        // 目标模式（2026-10-22）：与 GoalSettings 的默认值/钳制范围同源；schema 已挂「目标模式」面板。
        // 为什么必须在这里登记：snapshot() 只遍历 defaults()，漏登记的表单能保存但重开读不回，
        // 表现就是「改了没生效」。20 个键一一对应 GoalSettings 的 getter。
        d.put("chat.goal.maxRounds", "40");
        d.put("chat.goal.nextRoundDelayMs", "2000");
        d.put("chat.goal.maxCompletionChecks", "2");
        d.put("chat.goal.maxVerifyFailures", "5");
        d.put("chat.goal.maxTokens", "2000000");
        d.put("chat.goal.maxMs", "7200000");
        d.put("chat.goal.maxToolCalls", "400");
        d.put("chat.goal.maxRetries", "2");
        d.put("chat.goal.maxRecoveries", "3");
        d.put("chat.goal.retryBaseSeconds", "15");
        d.put("chat.goal.retryMaxSeconds", "120");
        d.put("chat.goal.softBudgetRatio", "0.8");
        d.put("chat.goal.claimBatch", "5");
        d.put("chat.goal.workers", "4");
        d.put("chat.goal.initialTickDelayMs", "1000");
        d.put("chat.goal.tickMs", "2000");
        d.put("chat.goal.leaseSeconds", "300");
        d.put("chat.goal.heartbeatStaleSeconds", "90");
        d.put("chat.goal.staleGraceSeconds", "90");
        d.put("chat.goal.verifyLocalFiles", "false");
        // chat.model 已退役（全局兜底移除）：不再注入默认值，存量库中的旧行成为孤儿数据（无读取方）。
        // chat.baseUrl / chat.apiKey / chat.completionsPath 同步退役：聊天网关统一来自「模型供应商」表
        // （模型引用 → 供应商网关），DynamicOpenAiChatModel 对解析不出的引用 fail-loud，无全局兜底
        // 回答温度已下线设置页/个人偏好（知识库问答固定低温度更稳，暴露给最终用户徒增困惑）：
        // 降级为隐藏参数——仅 DB 可调（与 tool.mcpCiteMaxRefs 同一口径），无设置页字段、无个人层
        d.put("chat.temperature", env("spring.ai.openai.chat.options.temperature", "0.3"));
        // chat.systemPrompt / chat.userSystemPrompt 已退役（2026-10）：角色提示词归智能体管
        // （c_ai_agent.system_prompt，问渠内置智能体已自带），未绑定智能体的会话回落内置默认
        // （AppProperties.systemPrompt，yml/env 可覆盖）；附加指令不再提供平台/个人层，存量库中的
        // 旧行成为孤儿数据（无读取方）
        d.put("chat.citationCheckEnabled", "true");         // 引用语义一致性自检（生成后校验：编造/张冠李戴的引用是 RAG 信任根基，默认开；每轮多一次模型调用，超时/失败自动跳过不阻塞）
        // chat.planAutoIntent 已退役（2026-10）：计划模式只由用户显式开关或模型自主调用 plan_enter 决定，
        // 问渠的预判器（PlanModeService）已删除；存量行由 schema.sql 清理。
        // 断线重连接流（P0）：默认开，且**不再露在设置页**——关掉它没有任何收益（刷新/换设备就丢一轮），
        // 它只是这次行为改动的回滚开关，不该出现在管理员的日常旋钮里（schema 已移除该字段）。
        // 这里继续登记=保留配置行与默认值，排障时可直接改库/env 回滚；
        // 与调用方 getBoolean("chat.resumeEnabled", true) 的形参默认值互为双保险——
        // 历史上形参默认值不可靠（Boolean.parseBoolean("") 返回 false 且不抛异常，兜底链走不到第二级，
        // 「默认开的开关」在键未入库时静默变关），该坑已在 getOrDefaultParsed 修掉并加启动自检点名。
        d.put("chat.resumeEnabled", "true");                // 刷新/换设备后接回正在跑的那一轮
        d.put("chat.detachGraceMs", "180000");              // 没人观看多久后按中断收束本轮；-1=一律跑完，0=关掉就停
        // ---------- 问答引擎：单引擎（只保留 AgentScope 框架运行时）----------
        // 手写运行时与 ab 灰度分流已下线；框架会话状态目录：AgentScope 默认写 ~/.agentscope/state/**，
        // 在问渠受管数据目录之外（容器里等于丢）
        d.put("chat.agentscopeStateDir", env("AI_AGENTSCOPE_STATE_DIR",
                System.getProperty("user.dir") + "/user-data/agentscope"));
        d.put("chat.agentscopeMaxIters", "12");            // 框架引擎单轮推理步数上限（ReAct 循环；框架默认 10，问渠显式 12）
        // chat.agentscopeMaxContextTokens 已取消（2026-10-18）：该键原以为能控制框架的压缩阈值，
        // 实测框架里 .maxContextTokens(...) 是 WorkspaceContextMiddleware 的「工作区材料注入预算」
        // （AGENTS.md/MEMORY.md/knowledge 注入上限，框架默认 8000），不是压缩阈值；压缩阈值由
        // model.getContextWindowSize() 推导（CompactionMiddleware: window-reserved 20k）。
        // 用户的窗口档位现落 OpenAIChatModel.contextWindowSize（AgentscopeChatEngine.modelContextWindow）。
        // 数据库遗留该键不影响行为（无读取点），设置页 debug 面板若无对应 schema 行也不会显示。
        // 框架引擎状态恒存 Redis（RedisAgentStateStore），本地文件方案已退役：
        // 容器一重建本地文件就没了，会话上下文随之丢失，多轮对话退化成每轮独立问答
        // （2026-10-21 线上事故：模型上一轮刚给过数据，下一轮却说没有相关数据）。
        // 因此 Redis 是硬依赖——连不上时应用启动失败，不静默退回本地实现。
        // 连接 host/port/password 复用 spring.data.redis（REDIS_HOST/REDIS_PORT/REDIS_PASSWORD），
        // 不再作为 c_ai_config 字段；这里只保留一个 key 前缀，避免与缓存/配置广播混用命名空间。
        d.put("chat.agentscopeRedisPrefix", "agentscope:session:");
        // 会话事件日志归档：归档目录跟着 chat.agentscopeStateDir 走（留空=落在它旁边的 session-archive/）。
        // 漏登记不只是「库里少一行」：snapshot() 只遍历 defaults()，这些键的值永远不回显，
        // 页面按 schema 的 def 显示初值，而脏检测只比页面上出现过的值——于是归档开关打开后
        // 在界面上既看不到、也关不掉（库里那行 true 一直留着）。四个键一并登记，读取点
        // SessionLogArchive/ScheduleCenter 的形参默认值与之同口径。
        d.put("chat.sessionLogArchive.enabled", "true");    // 归档默认开：保留策略（keepDays=90 + 每日清理）随默认值出厂，盘占用有界；
                                                            // 且正文之外的过程明细（工具入参/结果、思考段、压缩留痕）只存在于事件日志，
                                                            // 不归档则容器重建即丢（2026-10-21 Redis 会话事故若有归档会好查得多）
        d.put("chat.sessionLogArchive.dir", "");             // 留空=落在 {chat.agentscopeStateDir}/session-archive；多副本必须指共享卷
        d.put("chat.sessionLogArchive.keepDays", "90");      // 按归档文件最后写入时间判定；0=不清理
        d.put("chat.sessionLogArchive.cleanupIntervalMs", "86400000"); // 清理任务间隔，默认 1 天；≤0 暂停该任务
        // 会话事件日志换代：框架每轮开始要把整条事件链从头读几遍，代价按事件数的平方算
        // （本机实测 3.5k→1.1s、14k→4.7s、57k→49.3s，全花在模型出第一个字之前）。
        // 阈值按「起轮税不超过约 1.5 秒」定，不是按轮数定；≤0 = 不换代（长会话必然越聊越慢）。
        d.put("chat.agentscopeJournalRotateEvents", "12000");
        d.put("chat.agentscopeJournalRotateKeepDays", "30");  // 搬走的旧代次留 30 天（归档 JSONL 才是长期件）；≤0 = 不清理
        // chat.agentscopeTurnTimeoutSeconds 已取消（2026-10）：框架对单把工具有自带 5 分钟硬超时，
        // 此前用该键覆盖成 SSE 超时，默认 0=沿用 SSE 超时——框架已有限制、参数只留个默认就用不上，
        // 现由 AgentscopeChatEngine.turnSeconds() 恒定取 chat.sseTimeoutMs，不再留配置口
        // chat.agentscopeCompactTriggerMessages 已取消（2026-10）：框架默认（动态 token 阈值或
        // 50 条消息触发、逐字留最近 20 条）本身就是会跑的滚动压缩，默认态与框架默认逐字相同，
        // 现 compactionConfig() 只保留手动 /compact 的强制压缩路径
        // 工具大输出留存固定开启（不再留开关）；下面两条只治理磁盘占用。
        d.put("tool.spillMaxMbPerUser", "200");             // 每人留存原文的磁盘配额(MB)，0=不限量；超出按落盘时间淘汰最旧几份
        // 留存的两条治理参数都要进 defaults()：snapshot() 只回显这里登记过的键，
        // 漏登记的表现是设置页改完重开仍显示表单默认值（看着像「改了没生效」，实际库里已生效）
        d.put("tool.spillCleanupIntervalMs", "3600000");     // 外溢原文超期清理扫描间隔(ms)
        d.put("cleanup.toolSpillRetentionDays", "7");        // 外溢原文保留天数（≤0=不清理）
        d.put("vision.prompt", properties.getVision().getPrompt());
        // vision.baseUrl / vision.apiKey 不注默认值：视觉网关统一来自「模型供应商」表（知识库
        // parse_params.visionRef 引用 → 供应商网关）。这两键既不在可编辑白名单、也没有运行时读取点，
        // 灌进库里只会让"设置值看起来生效"（VisionService.routeFor 对无引用直接跳过，不回落这两键）
        d.put("vision.enabled", String.valueOf(properties.getVision().isEnabled())); // L12：总开关（设置页可改）
        d.put("vision.concurrency", String.valueOf(properties.getVision().getConcurrency()));
        d.put("vision.descCacheVersion", "1");                 // 图片描述缓存版本（bump 后全量重新描述）
        d.put("vision.descCacheTtlDays", "180");               // 图片描述缓存有效期(天，0=不过期)
        // embedding.baseUrl/apiKey/embeddingsPath 退役：向量网关统一来自「模型供应商」表（kb.embedding_ref
        // 引用 → 供应商网关），遗留网关信息已无任何读取方
        // embedding.dimensions 已退役（2026-10）：维度按知识库登记在 c_ai_knowledge_base.embedding_dimensions，
        // 全局记录既无写入方也无读取方，不再保留配置键
        // 平台内置向量化模型（全局、管理员可编辑、非 personalOnly）：运营方登记一个 embedding 模型引用，
        // 作为全体用户记忆语义能力的统一来源——用户零配置即可获得语义记忆。留空=不提供向量化
        // （记忆去重降级为精确匹配、语义注入关闭）。记忆向量化已不再暴露为个人设置项
        // 分块粒度与标题层级：解析器统一经 configService 读取（d 里必须给出种子，否则丢失 yml 默认），
        // 知识库 parse_params 的库级覆盖才可能生效（覆盖走线程局部，读 AppProperties 的旁路读不到）
        d.put("chunk.maxSize", String.valueOf(properties.getChunk().getMaxSize()));
        d.put("chunk.headingDepth", String.valueOf(properties.getChunk().getHeadingDepth()));
        d.put("chunk.maxChunks", String.valueOf(properties.getChunk().getMaxChunks()));
        d.put("chunk.maxImages", String.valueOf(properties.getChunk().getMaxImages()));
        d.put("chunk.overlap", String.valueOf(properties.getChunk().getOverlap()));
        d.put("chunk.structural", String.valueOf(properties.getChunk().isStructural()));
        d.put("chunk.structuralRatio", String.valueOf(properties.getChunk().getStructuralRatio()));
        d.put("parse.embedRetryCount", String.valueOf(ConfigDefaults.PARSE_EMBED_RETRY_COUNT));                 // M10：向量化批次失败自动重试次数
        d.put("upload.maxFileSize", String.valueOf(200L * 1024 * 1024));  // 业务上传上限（字节），默认 200MB
        d.put("retrieval.vectorWeight", String.valueOf(properties.getRetrieval().getVectorWeight()));
        d.put("retrieval.keywordWeight", String.valueOf(properties.getRetrieval().getKeywordWeight()));
        d.put("rerank.enabled", String.valueOf(properties.getRetrieval().getRerank().isEnabled()));
        // personalOnly：归知识库检索设置绑定，没有全局槽位。保留在 defaults() 只为
        // snapshot/isLiveKey 能识别这个键；ensureDefaults 会跳过 personalOnly，不再往库里种全局行。
        d.put("rerank.model", "");
        // 平台默认重排模型（管理员登记，全平台兜底）：库/智能体覆盖与个人设置都为空时生效，
        // 仍为空才回落 rerank.baseUrl。与 rerank.model 分开成键，是为了不动 personalOnly 的归属语义
        // （模型引用只认归属人），又让"一个库都没绑 = 全平台没重排"这个默认态有解。
        d.put("rerank.platformRef", "");
        d.put("rerank.baseUrl", properties.getRetrieval().getRerank().getBaseUrl());
        // 重排窗口两键：此前只有代码默认值（ConfigDefaults），设置页看不见、PUT 改不动，
        // 只能直接改库——调参时等于"没有入口"。2026-10-20 补齐 schema 字段（检索设置·重排服务一节），
        // defaults() 与 schema 的 def 同为 ConfigDefaults.RERANK_MIN_HITS/MAX_HITS。
        d.put("rerank.minHits", String.valueOf(ConfigDefaults.RERANK_MIN_HITS));
        d.put("rerank.maxHits", String.valueOf(ConfigDefaults.RERANK_MAX_HITS));
        // context.safetyFactor / context.costCapTokens / context.maxOutputTokens 已退役：
        // 窗口与最大输出按模型在「模型管理」声明（对话类窗口必填），安全系数固定为
        // ModelRegistryService.CONTEXT_SAFETY_FACTOR，成本软上限移除。存量库中的旧行成为孤儿数据（无读取方）。
        // context.historyCompress / context.compressRatio 已退役（2026-10）：自动历史压缩由 AgentScope
        // CompactionMiddleware 接管，问渠侧那套 assemble/compact 代码已删除；存量行由 schema.sql 清理。
        // context.snippetWindowChars / dedup* / adjacentMerge* 已退役（2026-10）：唯一读点是已删除的
        // RetrievalPipeline，问答实际走 search_knowledge 工具；设置页不再展示这些点了不生效的旋钮。
        d.put("context.maxContextHits", String.valueOf(properties.getContext().getMaxContextHits()));
        d.put("context.maxBlocksPerDoc", "3");             // 单文档块数配额（0=不限制；@ 引用块豁免）
        // P5 解绑后主检索覆盖使用者全部可见库，没有每库配额就会被"块多且词面泛"的那个库占满名额
        d.put("context.maxBlocksPerKb", "4");              // 单知识库块数配额（0=不限制；@ 引用块与库取不到的块豁免）
        // deepReasoning.* 已退役（2026-10）：思考强度由对话页按模型选择，框架引擎只透传模型方言，
        // 旧「思考流 → <search> 检索计划 → 多路检索」整组配置无任何读取点，连同 AppProperties 类一并删除。
        // 读点共用默认值的 key：defaults() 与读点 fallback 共用 ConfigDefaults 常量（改默认值只改常量一处）

        // 检索行为参数（原硬编码收口，设置页可调、保存即生效）
        d.put("retrieval.vecThreshold", "0.3");            // 向量相似度归一化基准/下限
        d.put("retrieval.minContextScore", String.valueOf(ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE));  // 最低相关分门槛（仅对重排分 0~1 分域生效，对齐 Dify/Coze Score 阈值；0=关）
        d.put("retrieval.minFusionScore", String.valueOf(ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE));    // 融合分门槛（未启用重排时生效；2026-10-04 评测基线标定：期望块 95.7%≥0.55，无关块 70%<0.5、通过率 30%，旧 0.25 时通过率 82.7% 拦不住词面重叠噪声；调检索权重后需重标定；0=关）
        // retrieval.queryRewriteEnabled 已退役（2026-10）：多轮查询改写实现已随手写运行时删除，
        // 框架/检索链路没有第二个读取点；存量行由 schema.sql 清理。
        d.put("retrieval.aclTopKSupplant", "true");        // 权限补采是否顶掉原 topK 尾部（2026-10-20 补 schema 字段：此前 defaults 登记了但设置页没有这一行，改了无法回显、PUT 还会被整笔拒）
        d.put("retrieval.vectorTopK", "15");               // 向量召回 topK（调优/评估扫参用，下限 1）
        d.put("retrieval.keywordLimit", "20");             // 关键词召回上限
        d.put("retrieval.searchTimeoutMs", "8000");        // 混合检索总超时
        // retrieval.multiConsensusBonus / retrieval.reflectiveRetrieval 已退役（2026-10）：
        // 二者都只服务已删除的「深度思考多路检索 + 检索-反思循环」；框架路径的工具挂载把 reflective
        // 恒传 false，多路共识仅评测的 searchMulti 近似还在用（改为 ConfigDefaults 常量）。
        // 存量行由 schema.sql 清理。
        // 重排行为参数
        // 关键词召回引擎（mysql=LIKE；meilisearch=外部索引，中文分词+相关度；index 只走 yml 不入库）
        d.put("keyword.engine", properties.getKeyword().getEngine());
        d.put("keyword.baseUrl", properties.getKeyword().getBaseUrl());
        d.put("keyword.apiKey", properties.getKeyword().getApiKey());   // master key RSA 加密入库（设置页可改，改后客户端自动重建）；未配置时回退 env AI_MEILI_KEY
        d.put("keyword.reconcileIntervalMs", "3600000");     // 关键词索引精确对账周期（ms，默认 1 小时；≤0 暂停）。只比 Meilisearch 与 MySQL，不调任何模型
        // 启动首轮对账：2026-10-06 默认关闭。此前库里存的是 true，导致每次重启都跑一遍全量双向比对——
        // 虽然不烧额度，但多副本各跑一遍纯属无谓扫描（对账已由上面的周期任务覆盖，启动首轮没有不可替代的价值）。
        // 注意：此前本键既没登记进 defaults() 也不在 config-schema.json，导致设置页看不到、改不了，
        // 值只能手工改库——「隐藏开关」本身就是隐患，故在此正式登记。
        d.put("keyword.reconcileOnStartup", "false");
        // 解析行为参数
        d.put("parse.concurrency", "3");                   // 文档解析 worker 并发数（队列worker 数；下游 OCR/视觉/embedding 另有闸门限流）
        d.put("parse.queue.capacity", "500");              // 解析队列容量：排队满则上传直接拒（不再是"收下再丢解析任务"）
        d.put("parse.queue.scanIntervalMs", "5000");       // 解析队列扫描间隔（≤0 暂停：解析不再自动执行）
        d.put("parse.taskTimeoutMs", "1200000");           // 单个解析任务总超时（超时中断线程；租约随后回收，崩溃也能自愈）
        d.put("parse.taskLeaseSeconds", "0");              // 任务租约时长(秒)；0=按 taskTimeoutMs+300 自动算
        d.put("parse.retryMaxAttempts", "3");              // 解析失败最大尝试次数（超过转终态 dead，不再退避）
        d.put("parse.retryBackoffSeconds", "5");           // 解析失败退避基数(秒)：第 n 次等 base×2^(n-1)（封顶 300s）
        d.put("parse.embedConcurrency", String.valueOf(ConfigDefaults.PARSE_EMBED_CONCURRENCY));              // 向量化并发闸（多文档并行解析时防打爆 embedding 服务）
        d.put("parse.ocrGateConcurrency", "1");            // 版面引擎并发闸：自托管单实例服务一次只吃一份，默认守住 1
        d.put("parse.ocrMinText", "20");                   // PDF 文本少于该长度判定扫描件触发 OCR
        d.put("parse.recoverStuckOnStartup", "true");      // 启动对账：复位崩溃残留的"解析中"文档（多副本部署应置 false）
        d.put("parse.qaEnabled", "true");                  // QA 增强：解析时按块生成问答对并按问法向量化（消耗对话模型 token；需重解析生效）
        d.put("parse.qaPerChunk", "2");                    // QA 增强：每块生成问法条数（1~5）
        // parse.qaModel 已退役：问答对生成回落库主个人默认聊天模型（User.defaultModel，QaIndexService 显式查询）
        d.put("parse.childEnabled", "true");               // 父子分块：超长块切子块向量化，命中后返回父块正文（确定性切分，无 LLM；需重解析生效）
        d.put("parse.childSize", "400");                   // 父子分块：子块尺寸（字符，超过该长度的块才切子块）
        d.put("vision.userImageConcurrency", "2");         // 用户上传图片识别并发
        // 问答行为参数
        // chat.remainTokenFloor / chat.truncateFallbackChars 已退役（2026-10）：上下文装配的工程细节
        // 不再暴露给设置页（用户容易误解为压缩参数，误调会在预算耗尽后强塞知识块）——
        // 固定为 ChatService 常量 REMAIN_TOKEN_FLOOR=800 / TRUNCATE_FALLBACK_CHARS=200。存量库旧行成孤儿数据
        // chat.historyRounds 已退役：历史不再按轮数截断；框架引擎的滚动压缩由 AgentScope
        // CompactionMiddleware 按模型窗口自动触发（context.historyCompress/compressRatio 已一并退役）。
        // chat.userSystemPrompt（附加指令）已退役：见上方 chat.systemPrompt 注释
        // 深度思考默认偏好（personal；个人设置可改，前端据此决定新模型默认开/关，按模型的手动记忆仍存浏览器）
        d.put("chat.pipelineThreads", "8");                // 问答流水线线程数（重活不占 Tomcat 请求线程）
        d.put("chat.approvalTimeoutMs", "120000");         // 工具执行审批等待上限(ms)：超时按拒绝处理（阻塞工具线程，必须有界）
        d.put("chat.planTimeoutMs", "600000");             // 计划模式：计划批准等待上限(ms)（人工等待不计入整轮预算）；超时按未批准终止本轮
        d.put("chat.sseTimeoutMs", "300000");              // H4：问答 SSE 超时(ms)
        d.put("chat.askTimeoutMs", "600000");              // 人在回路提问卡等待上限(ms)（2026-10-09 补：schema 早已声明，defaults 漏登记 → 设置页改了回显不出）
        // 目标模式（Goal Mode）已无任何平台参数（2026-10-22 对齐 DSH）：
        // 不设预算/轮数旋钮，也没有确认卡等待——目标建好就自己往下跑，助手自报完成即停；
        // 开关只剩智能体级 goal_enabled 那一格，自动续跑上限由 GoalSettings 的 chat.goal.maxRounds 控制（默认 40）。
        // 存量库里的旧行由 schema.sql 的退役键清理幂等删除。
        // 调试显示总开关（归属徽标 / 路由提示 / 降级提示）。默认开（2026-10-18 由 false 改 true）：
        // 它管的已不只是排障项——「无命中 / 改写失败 / 图片剔除」这类降级信息默认不显示，等于系统性地
        // 不告诉用户这一轮检索没按预期发生，与「答得准、也敢被查」的定位直接冲突：产品承诺的是可被
        // 核对，若用户看不到自己踩了哪条路，就没有可核对的入口。真正的排障项（分值、步数、路由理由）
        // 仍只对管理员露出（前端按 isAdminSync 二次收窄），对普通用户开的是降级与归属这类诚实性信息。
        d.put("chat.retrievalDebugEnabled", "true");
        // 接口限流（按用户/IP 固定窗口）
        d.put("ratelimit.enabled", String.valueOf(properties.getRatelimit().isEnabled()));
        d.put("ratelimit.chatPerMinute", String.valueOf(properties.getRatelimit().getChatPerMinute()));
        d.put("ratelimit.uploadPerMinute", String.valueOf(properties.getRatelimit().getUploadPerMinute()));
        d.put("eval.judgeEnabled", "false");   // 自动体检 LLM 评判（默认关，评估集大时耗时/成本明显）
        d.put("eval.judgeModel", "");              // 评判用独立模型（留空=跳过 LLM 自动评判）
        d.put("eval.answerModel", "");             // 答案级评测的生成模型（留空=复用评判模型）
        d.put("eval.autoIntervalMs", "86400000");  // 自动体检周期(ms，≤0=暂停)
        d.put("eval.autoThresholdPct", "10");      // 退化判定：指标相对跌幅百分比阈值
        // 定时任务（schedule 包读取；≤0=暂停对应任务）
        d.put("images.chatCleanupIntervalMs", "86400000");   // 聊天图片清理间隔(ms)
        d.put("images.chatRetentionMillis", "604800000");    // 聊天图片保留时长(ms，7天)
        d.put("cleanup.sessionCleanupIntervalMs", "86400000"); // 会话清理间隔(ms)
        d.put("cleanup.sessionRetentionDays", "30");           // 会话保留天数
        d.put("cleanup.sessionEventRetentionDays", "30");      // 会话事件账本保留天数（≤0=不清理；与上一条同一清理周期）
        d.put("cleanup.visitorIdleDays", String.valueOf(ConfigDefaults.VISITOR_SESSION_IDLE_DAYS)); // 访客会话闲置回收天数
        d.put("artifact.retentionDays", String.valueOf(ConfigDefaults.ARTIFACT_RETENTION_DAYS));                 // 产物保留天数（0=不清理）
        d.put("artifact.cleanupIntervalMs", "86400000");       // 产物超期清理间隔(ms，≤0=暂停)
        d.put("schedule.runLogRetentionDays", "7");            // 定时任务执行日志保留天数（超期物理删除）
        d.put("schedule.runLogCleanupIntervalMs", "86400000"); // 任务执行日志清理间隔(ms，≤0=暂停)
        d.put("scheduled.enabled", "true");                    // 定时执行智能体总开关
        d.put("scheduled.maxPerUser", "20");                   // 每人定时任务数上限（0=不限）
        d.put("scheduled.timeoutMs", "300000");                // 单次执行超时(ms)
        d.put("scheduled.scanIntervalMs", "30000");            // 到期扫描间隔(ms，≤0=暂停扫描)
        d.put("web.refreshEnabled", "true");                   // 网页源定时刷新总开关（仅 file_type=url 且文档开启 autoRefresh 才刷新）
        d.put("web.refreshScanIntervalMs", "60000");           // 网页源到期扫描间隔(ms，≤0=暂停扫描)
        // 原 yml 参数开放为可配置（默认值取 bean，yml/env 可覆盖；再由 syncProperties 回写 AppProperties，
        // 读取点无需改动）。与 chunk / retrieval / ratelimit 同口径：这里写死字面量会让 yml 配置无声失效。
        d.put("images.maxWidth", String.valueOf(properties.getImages().getMaxWidth()));          // 图片压缩最长边(px,0=不压缩)
        d.put("images.quality", String.valueOf(properties.getImages().getQuality()));            // JPEG 压缩质量
        d.put("images.authEnabled", String.valueOf(properties.getImages().isAuthEnabled()));     // 图片签名鉴权开关（默认开：签名 URL 防盗链）
        d.put("images.authExpireSeconds", String.valueOf(properties.getImages().getAuthExpireSeconds())); // 签名 URL 有效期(秒)
        d.put("vision.timeoutMillis", String.valueOf(properties.getVision().getTimeoutMillis())); // 视觉模型读取超时(ms，RestClient 构建期读取，需重启生效)
        d.put("vision.retryCount", String.valueOf(properties.getVision().getRetryCount()));       // 单图失败重试次数
        d.put("session.maxHistory", String.valueOf(properties.getSession().getMaxHistory()));     // 会话保留轮数
        d.put("session.expireMinutes", String.valueOf(properties.getSession().getExpireMinutes())); // 会话过期(分钟)
        // 图片相关性校验（读取点 ImageFilterService；defaults 取 bean）
        d.put("imageFilter.enabled", String.valueOf(properties.getImages().getImageFilter().isEnabled()));
        d.put("imageFilter.minHits", String.valueOf(properties.getImages().getImageFilter().getMinHits()));
        d.put("imageFilter.preContextChars", String.valueOf(properties.getImages().getImageFilter().getPreContextChars()));
        // 原先写死在消费方代码里的行为参数（直读 configService，保存即生效）
        d.put("chat.maxImagesPerMessage", "9");
        d.put("chat.maxImageMb", "10");
        // 聊天附件：上传换 fileId 落盘，超保留期由 ScheduleCenter 清理（0=不清理）
        d.put("chat.uploadRetentionHours", "24");
        d.put("chat.uploadCleanupIntervalMs", "3600000");
        d.put("parse.embedBatchSize", String.valueOf(ConfigDefaults.PARSE_EMBED_BATCH_SIZE));
        d.put("parse.ocrDpi", "200");
        d.put("parse.ocrEngine", "none");      // PDF 深度解析引擎：none=文本抽取+扫描件视觉兜底（默认）/ vision=整份逐页视觉OCR / pp_structure_v3 / mineru=外部版面解析服务（失败 fail-loud 不回落）
        d.put("parse.ocrPpUri", env("OCR_PP_STRUCTURE_URI", ""));       // PP-StructureV3 服务地址（环境变量；未配置则引擎 fail-loud）
        d.put("parse.ocrMineruUri", env("OCR_MINERU_URI", ""));         // MinerU 服务地址（环境变量；未配置则引擎 fail-loud）
        d.put("parse.ocrMineruBackend", env("OCR_MINERU_BACKEND", "pipeline")); // pipeline=CPU 稳 / hybrid-auto-engine=本地 VLM 高精度
        d.put("parse.ocrTimeoutMs", env("OCR_TIMEOUT_MS", String.valueOf(ConfigDefaults.PARSE_OCR_TIMEOUT_MS))); // 版面引擎单次调用超时(ms)
        d.put("ratelimit.windowSeconds", "60");
        d.put("cache.docMetaTtlSeconds", "600");
        // 问渠工具总开关（只管理问渠注册的工具；AgentScope 原生文件/命令/记忆/技能由权限档位与各自配置控制）
        d.put("tool.enabled", "true");
        // tool.knowledgeRetrieval.enabled / tool.artifact.enabled / tool.builtin.enabled /
        // tool.subagentConsult.enabled / tool.todo.enabled / tool.mcpCiteEnabled / tool.spillEnabled
        // 已退役（2026-10）：低风险工具默认随总闸启用，按智能体三态或平台停用名单 tool.deny 收口；
        // 工具大输出留存与 MCP 引用注册改为固定行为，不再各留一个平台开关。
        d.put("tool.knowledgeRetrieval.maxHits", "5");     // 精确检索工具单次返回命中块上限(1~5)
        d.put("tool.mcpCiteMaxRefs", "10");                // MCP 引用注册单轮上限（隐藏参数，DB 可调；无设置页字段）
        // ---------- 联网搜索（工具 webSearch；结果注册进引用体系，与知识库来源同 [N] 编号）----------
        // 注意：webSearch.apiKey 以 .apiKey 结尾 → 走敏感项 RSA 密文入库（isSensitiveKey），改不得命名
        // webSearch.enabled 保持默认关：联网搜索的 provider/baseUrl/apiKey 三个键默认值全为空，
        // 默认开等于让新装用户的模型去调一个必然失败的接口——每轮白烧 token 并把外部错误透给用户。
        // 但「纳入执行审批」默认开（2026-10-18 由 false 改 true）：一旦管理员开了联网，它就是
        // 唯一一类「向外部第三方发请求、可能计费」的工具，没审批就等于让模型自主决定花谁的钱。
        // 审批关不掉的只有逐项确认（智能体选自动执行时照常自动跑），不会把联网整个关掉。
        d.put("webSearch.enabled", "false");               // 联网搜索总开关（需 tool.enabled 总闸开启）
        d.put("webSearch.provider", "tavily");            // 服务商：tavily / bocha / generic（自建 SearXNG）
        d.put("webSearch.baseUrl", "");                   // 服务地址（留空用服务商默认；generic 必填，如 http://127.0.0.1:8888）
        d.put("webSearch.apiKey", "");                    // 服务 Key（RSA 密文入库；generic 自建可留空）
        d.put("webSearch.maxResults", "5");               // 单次搜索返回条数上限(1~10)
        d.put("webSearch.maxCallsPerTurn", "2");          // 单轮搜索次数上限（防模型反复搜烧配额；0=不限制）
        d.put("webSearch.timeoutMs", "8000");             // 单次请求超时(ms)
        d.put("webSearch.snippetChars", "600");           // 单条摘要截断字符数（控制工具结果 token 量）
        d.put("webSearch.requireApproval", "true");       // 是否纳入"有副作用工具"：true=受智能体 toolApprovalMode=ask 管辖。默认开：联网是唯一会向外部第三方发请求、可能对用户计费的能力，让模型自主决定花谁的钱不该是默认态
        // 技能：内置随版本分发 + 用户自装（user-data/skills-users/{uid}）+ 助手在对话里沉淀到沙盒工作区。
        // 平台侧只剩两件事：哪些技能全平台不再用（skill.disabled），以及 URL 安装允许哪些来源域名。
        // 技能正文读取上限改为平台常量（SKILL_MAX_FILE_CHARS），不再作为设置项。
        d.put("skill.disabled", "");                                                                   // 停用清单（平台层）
        // 用户自助安装（粘贴/zip/URL）开启后，URL 安装只允许这些域名（精确匹配；空=关闭远程安装）。
        // 服务端仍会逐跳做公网校验（SsrfGuard），这里只回答「允许从哪儿拉」。
        d.put("skill.remoteAllowedHosts", "");

        // ---------- 沙盒（隔离执行环境）----------
        // 语义与问渠新栈的环境变量一一对应（SANDBOX_PROVISIONER_URL / TOKEN / DELETE_TIMEOUT_SECONDS /
        // KEEPALIVE_INTERVAL_SECONDS / VIRTUAL_PATH_PREFIX / EXEC_TIMEOUT_SECONDS / MAX_OUTPUT_BYTES），
        // 沙盒连接参数属于部署配置，走环境变量而不是设置页：
        //   SANDBOX_PROVISIONER_URL / SANDBOX_PROVISIONER_TOKEN / SANDBOX_VIRTUAL_PATH_PREFIX /
        //   SANDBOX_CALLBACK_BASE_URL
        // token 仍走敏感项（.token 后缀 ⇒ RSA 密文入库 + 快照只回显后 4 位）。
        d.put("sandbox.provisionerUrl", env("SANDBOX_PROVISIONER_URL", "http://127.0.0.1:8002"));
        d.put("sandbox.token", env("SANDBOX_PROVISIONER_TOKEN", ""));
        d.put("sandbox.virtualPathPrefix", env("SANDBOX_VIRTUAL_PATH_PREFIX", "/home/gem/user-data"));
        d.put("sandbox.commandTimeoutSeconds", "180");            // 单条命令超时（秒）
        d.put("sandbox.maxOutputBytes", "262144");                // 单条命令输出上限（字节）
        d.put("sandbox.keepaliveIntervalSeconds", "30");          // keepalive 间隔（秒，≤0 关闭）
        d.put("sandbox.deleteTimeoutSeconds", "120");             // 删除沙盒的超时（秒）
        d.put("sandbox.idleReleaseMinutes", "60");                // 空闲多久回收沙盒（分钟，0=不回收）
        d.put("sandbox.cleanupIntervalMs", String.valueOf(ConfigDefaults.SANDBOX_CLEANUP_INTERVAL_MS));             // 空闲回收扫描间隔（ms，≤0=暂停）
        // 沙盒里的脚本要回调平台工具（P3b run_code）时的回程地址。容器各自挂在自己的 bridge 网络上，
        // 本机实测 host.docker.internal 可解析且能打到应用端口；生产的拓扑不同，所以这项必须显式配置。
        // 留空 = 不提供子调用（run_code 会明确说"回调地址没配"，而不是让脚本每次都超时）。
        d.put("sandbox.callbackBaseUrl", env("SANDBOX_CALLBACK_BASE_URL", "http://host.docker.internal:8090/ai"));
        // ---------- 沙盒脚本（P3b：一次做完一批同类活）----------
        d.put("tool.runcode.enabled", "false");             // 默认关：能力形态变化，由管理员决定谁先开
        d.put("tool.runcode.maxSubCalls", "50");            // 单次运行的子调用总上限（脚本扇出去的工具调用次数）
        d.put("tool.runcode.maxParallel", "8");             // 脚本内并发上限（生成模块里的信号量，也防打爆平台线程池）
        d.put("tool.runcode.timeoutSeconds", "120");        // 单次脚本运行上限（沙盒命令超时）
        d.put("tool.runcode.subCallTimeoutMs", "60000");    // 单个子调用的等待上限（脚本侧 HTTP 超时）
        // 工作区目录（沙盒挂 shared/{uid}/workspace 的 projects/{id} 而非会话目录）
        // 默认关：想让新装的用户「什么都不配」也能用，回会话级目录的行为与引入工作区之前逐字一致。
        // 端到端已真机验证通过（2026-10-18）：带 workdir 建沙盒正常、文件落宿主机工作区目录、
        // 删容器后重建文件仍在、会话 A 写入会话 B 可读——历史记录的 404 未复现，当前环境可放心开启。
        // 工作区目录在**宿主**上的根：工作区页的文件浏览直接读它（沙盒与后端同机部署时成立；
        // 远端 provisioner 部署下这里读不到，接口会如实返回「不可用」而不是假装空列表）
        d.put("sandbox.userDataHostDir", System.getProperty("user.dir") + "/user-data");
        d.put("sandbox.projectWorkdirEnabled", "false");
        // 工作区页往目录里上传文件的单文件上限（MB）。服务端代码再钳到 256：
        // spring 的 multipart 上限是 256MB，写更大只会让请求在容器那一层变成一句没有原因的失败。
        d.put("sandbox.projectUploadMaxMb", "100");
        d.put("tool.sandbox.enabled", "false");                   // 沙盒 API / 工作流节点开关（不是聊天里的文件/命令工具）
        // ---------- 权限档位（P2：沙盒执行档 × 工具执行确认，捆成具名预设）----------
        // 档位取值见 PermissionPreset：research=只读调研 standard=常规 autonomous=全自主。
        // 上限默认放到最高档=不钳制（存量智能体行为逐字不变），管理员收紧后选得更高的档位按上限执行。
        d.put("agent.permissionPreset.default", "standard");      // 智能体未选档时的平台默认档
        d.put("agent.permissionPreset.max", "autonomous");        // 平台允许的最高档（管理员收紧处）
        // 平台级工具黑名单（逗号分隔工具名）：管理员一次性停掉某一个工具，不用逐个智能体改。
        // 族级开关（tool.websearch.enabled 等）粒度太粗——关一族会连带关掉同族其它能力；
        // 智能体级黑名单又要求每个智能体都改一遍。这一项补的是中间那一档。
        d.put("tool.deny", "");                                 // 平台禁用工具名单（空=不禁）
        // 已退役：agent.enabled / agent.subAgents / agent.topKPerAgent / agent.digestEnabled /
        // agent.aggregateMode / agent.digestMaxChars / agent.aggregateMarkFailed / agent.autoRoute /
        // agent.routeTimeoutMs / agent.maxParallelSubAgents / agent.subagentMaxDepth /
        // agent.subagentTimeoutMs / agent.subagentBriefChars / agent.subagentBriefMode /
        // agent.subagentBriefTurns。
        // 这一组属于 P5 之前那套「SubAgentOrchestrator 并行编排」（自建 StateGraph 扇出 + 要点提炼
        // + digest/full 双形态 + autoRoute 模型路由）。P5 把派遣换成 consultAgent（agent-as-tool、模型
        // 自主发起）后，该编排器连同这组键的读取点一起归零，编排器本体已删除，键不再登记——
        // 留着只会让设置页显示一套点了不生效的开关，而「配了没生效」正是用户最难自行归因的故障。
        d.put("agent.maxToolSteps", "15");                 // 单轮工具调用步数上限（智能体可覆盖；0=不限制）
        // ---------- 子智能体分支的执行形态（P3a）----------
        // 默认 digest = 引入本档之前的行为逐字相同（只并行检索 + 一次短调用提炼要点，不跑工具循环）。
        // full 那一档才是"分支里真有一个完整智能体在跑"，按子智能体逐个开，代价是每支都等于一轮问答。
        d.put("agent.subagentMode", "digest");             // 平台默认档（子智能体没自己选时用它）
        d.put("workflow.maxSteps", String.valueOf(ConfigDefaults.WORKFLOW_MAX_STEPS));                  // 工作流单次运行的最大图步数（StateGraph recursionLimit；M3 loop 回跳也受此闸）
        d.put("workflow.subagentTimeoutMs", "180000");     // 工作流子智能体节点等待回答的超时（ms，下限 30s）
        d.put("workflow.runTimeoutSeconds", "600");        // M5：工作流单次运行硬超时（秒，0=不限制；超时按 timeout 终态收口，防长管线占死执行线程）
        d.put("workflow.runLogRetentionDays", "30");       // 工作流运行记录保留天数（含 DSL 快照与节点 trace 大字段；超期由「工作流运行记录清理」任务物理删除）
        d.put("workflow.runCleanupIntervalMs", "86400000"); // 工作流运行记录清理间隔（ms，默认每日；≤0 暂停）
        d.put("workflow.scheduleScanIntervalMs", "30000");  // 工作流定时触发扫描间隔（ms，默认 30s；≤0 暂停「工作流定时触发」任务）
        d.put("workflow.approvalReapIntervalMs", "60000"); // 审批超时回收间隔（ms，默认 60s；≤0 暂停，暂停后挂起 run 不落终态）
        d.put("config.reloadIntervalMs", "300000");        // 多副本配置同步的周期兜底刷新间隔（ms，默认 5min；≤0 暂停）
        d.put("notification.retentionDays", "30");          // 站内通知保留天数（超期由「站内通知清理」任务物理删除，不区分已读未读）
        d.put("notification.cleanupIntervalMs", "86400000"); // 站内通知清理间隔（ms，默认每日；≤0 暂停）
        d.put("notification.dedupWindow", "1440");          // 同类通知合并窗口(分钟)：同目标重复发生的同类通知合并为一条，0=关闭合并
        d.put("workflow.maxConcurrentRuns", "4");          // 第 2 期：异步运行并发上限（派发池 core=max；改后需重启生效）
        d.put("workflow.runQueueCapacity", String.valueOf(ConfigDefaults.WORKFLOW_RUN_QUEUE_CAPACITY));          // 第 2 期：异步运行排队容量（队列满即拒绝，fail-loud 不无限堆积；改后需重启生效）
        d.put("trace.samplingIntervalMs", "86400000");     // P1：Trace 线上采样间隔（ms，默认每日；≤0 暂停）
        d.put("trace.sessionEventEnabled", "true");         // 会话事件账本：每轮结构过程按写入顺序留痕（默认开；关掉后一行不写，问答行为不变）
        d.put("trace.turnIdBackfill", "false");             // 一次性补账开关：默认关（开着等于每次重启都扫一遍历史消息）
        d.put("trace.sampleRandomDaily", "20");            // P1：每日随机采样条数（温和策略；0=不采）
        d.put("trace.sampleNoHitDaily", "10");             // P1：每日无引用采样条数（0=不采）；差评恒为必采
        // graphrag.modelRef 已退役（2026-10）：GraphRAG 抽取回落库主个人默认聊天模型（User.defaultModel），
        // 不再单独配置——存量个人键行成为孤儿数据（无读取方）。
        d.put("graphrag.maxTriplesPerChunk", "10");        // P1：单个知识块抽取三元组上限（成本闸）
        d.put("graphrag.batchChunks", "3");                // P1：合并批抽取的块数（3~5 平衡 token 与归属粒度）
        d.put("graphrag.expandTopK", "5");                 // P1：检索时图扩展并入的块数上限（0=不扩展）
        // 用户长期记忆（跨会话个性化）
        d.put("memory.enabled", "true");                   // 总开关：关掉后全平台问答不再自动提炼记忆（已沉淀的仍会注入）
        d.put("memory.flushThrottleMinutes", String.valueOf(ConfigDefaults.MEMORY_FLUSH_THROTTLE_MINUTES));
        d.put("memory.maxMdTokens", String.valueOf(ConfigDefaults.MEMORY_MAX_MD_TOKENS));
        // memory.consolidationMaxTokens / memory.dailyRetentionDays 已退役：容量只留硬上限一个旋钮，
        // consolidation 的提示词目标由它按 2/3 推导；日账保留期沿用框架默认 90 天。
        // 存量行由 schema.sql 的退役键清理幂等删除。
        // 已退役：agent.autoRoute / agent.routeTimeoutMs（派遣路由的两项）。P5 换成 agent-as-tool 后这两项
        // 的读取点随 SubAgentOrchestrator 一起归零——见本方法上方那段「已退役」清单的统一说明。
        d.put("agent.consultMaxPerTurn", String.valueOf(ConfigDefaults.AGENT_CONSULT_MAX_PER_TURN));   // 一轮最多咨询几位助手
        d.put("agent.consultTimeoutMs", String.valueOf(ConfigDefaults.AGENT_CONSULT_TIMEOUT_MS));      // 单次咨询等那条子轮的上限
        // tool.subagentConsult.enabled 已退役：派遣随工具总闸启用，平台级收口走 tool.deny（按工具名 agent_spawn）。
        // 智能体配置版本快照保留份数（超出按版本号最小先删）；设置页挂在「定时任务 → 智能体配置版本」
        d.put("agent.versionKeep", "20");
        // mcp.enabled / mcp.servers 已移除：MCP **客户端**改为每人自己的 c_ai_user_mcp（见 McpClientService）
        // MCP **服务端**（对外提供端点）：把已发布的智能体暴露给 Claude/Cursor 等外部客户端
        d.put("mcp.server.enabled", "false");      // MCP 端点总开关（默认关：不主动对外暴露能力）
        d.put("mcp.server.timeoutMs", String.valueOf(ConfigDefaults.MCP_SERVER_TIMEOUT_MS));   // 单次工具调用等待问答完成的超时（ms）
        d.put("mcp.server.allowedOrigins", "");    // 允许的 Origin 列表（逗号分隔；空=仅允许回环地址）
        return d;
    }

    /**
     * 把「原由 ai-app.*（yml/env）在启动时绑定」的配置项，从缓存回写到 AppProperties。
     * <p>目的：这些项（chunk.maxSize、images.*、vision.*、session.*）的消费方直接调用
     * properties.getXxx()，若只加白名单不回写，设置页保存了也不会生效。
     * 在每次缓存（重）载入后调用一次，即可让消费方无需改动而支持 DB/设置页热生效。
     * <p>bean 是缓存派生视图（非第二份真源）：值非法/缺失时保留 bean 当前值（安全回退）。
     * 注：个别在构造期一次性读取的值（如 VisionService 的 RestClient 读超时）需重启才生效。
     */
    private void syncProperties() {
        try {
            // chunk.maxSize / chunk.headingDepth 不再回写 AppProperties：解析器已统一从 configService 读取，
            // 再回写一份进 bean 就形成双读取源——库级覆盖只写线程局部，读 bean 的旁路读不到（正是断链根因）
            AppProperties.Images images = properties.getImages();
            images.setMaxWidth(pInt("images.maxWidth", images.getMaxWidth()));
            images.setQuality((float) pDouble("images.quality", images.getQuality()));
            images.setAuthEnabled(pBool("images.authEnabled", images.isAuthEnabled()));
            images.setAuthExpireSeconds(pLong("images.authExpireSeconds", images.getAuthExpireSeconds()));
            AppProperties.Vision vision = properties.getVision();
            vision.setTimeoutMillis(pInt("vision.timeoutMillis", vision.getTimeoutMillis()));
            vision.setRetryCount(pInt("vision.retryCount", vision.getRetryCount()));
            AppProperties.Session session = properties.getSession();
            session.setMaxHistory(pInt("session.maxHistory", session.getMaxHistory()));
            session.setExpireMinutes(pInt("session.expireMinutes", session.getExpireMinutes()));
            AppProperties.ImageFilter imgFilter = properties.getImages().getImageFilter();
            imgFilter.setEnabled(pBool("imageFilter.enabled", imgFilter.isEnabled()));
            imgFilter.setMinHits(pInt("imageFilter.minHits", imgFilter.getMinHits()));
            imgFilter.setPreContextChars(pInt("imageFilter.preContextChars", imgFilter.getPreContextChars()));
        } catch (Exception e) {
            log.warn("[Config] 回写 AppProperties 失败（沿用当前值）: {}", e.getMessage());
        }
    }

    private int pInt(String k, int def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    private long pLong(String k, long def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Long.parseLong(v.trim()); }
        catch (Exception e) { return def; }
    }

    private double pDouble(String k, double def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Double.parseDouble(v.trim()); }
        catch (Exception e) { return def; }
    }

    private boolean pBool(String k, boolean def) {
        try { String v = get(k); return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim()); }
        catch (Exception e) { return def; }
    }

    private String env(String key, String def) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 评估批量对比用的线程局部参数覆盖（仅当前线程生效，finally 必须 clear；不写 DB 不污染配置） */
    private static final ThreadLocal<Map<String, String>> OVERRIDE = new ThreadLocal<>();

    /**
     * 个人配置覆盖（schema personal 字段）的线程局部快照：由问答流水线在装载身份后一次性载入
     * （键仅限 schema 标记 personal 的字段），只作用于本线程——管理端读取（Tomcat 线程）与
     * 定时任务/评估等无用户上下文的线程读到的仍是全局值。
     * <p>
     * 优先级：显式覆盖（{@link #putOverrides}，评估用）&gt; 个人覆盖 &gt; 全局缓存/默认值。
     * 池化线程复用，finally 必须 clear（见 {@link #clearUserOverrides()}）。
     */
    private static final ThreadLocal<Map<String, String>> USER_OVERRIDE = new ThreadLocal<>();

    /** 设置线程局部参数覆盖（评估用），返回 this 便于 finally 中 clearOverride */
    public void putOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        Map<String, String> cur = OVERRIDE.get();
        if (cur == null) {
            OVERRIDE.set(new HashMap<>(overrides));
        } else {
            cur.putAll(overrides);
        }
    }

    /**
     * 敏感配置项：值以 RSA 密文入库、快照脱敏为 {@code ****后4位}、恢复默认时跳过。
     * <p>按后缀判定而不是枚举键名——否则每加一个含密钥的配置项（如 OIDC 的 clientSecret）
     * 都要回来补一处判断，漏掉就会明文落库且界面回显完整密钥。</p>
     */
    private static boolean isSensitiveKey(String key) {
        return key != null && (key.endsWith(".apiKey") || key.endsWith(".clientSecret")
                || key.endsWith(".token") || key.endsWith("Password"));
    }

    /**
     * 键是否为「活跃槽位」：只有 {@link #defaults()} 定义的键才会被设置页渲染、保存校验、默认值灌入。
     * <p>退役键（chat.model / vision.model / embedding.model）不在其中——存量行对用户不可见也不可改，
     * 引用校验若仍认它们，会形成界面无处解除的死锁（删供应商被看不见的槽位挡住）。</p>
     */
    public boolean isLiveKey(String key) {
        return key != null && defaults().containsKey(key);
    }

    /**
     * 个人专属键（schema personalOnly：模型引用类，无全局槽位语义）。
     * 供删除供应商守门等场景区分「可见可改的活跃槽位」与「个人层键」——后者的引用去留
     * 由各归属用户自己管理，不该以"系统配置槽位"的名义挡住供应商删除（也挡不明白）。
     */
    public boolean isPersonalOnly(String key) {
        return schema.isPersonalOnly(key);
    }

    /** 清除线程局部参数覆盖（评估结束后必须调用） */
    public void clearOverride() {
        OVERRIDE.remove();
    }

    /**
     * 装载本轮用户的个人配置覆盖（键仅限 schema 标记 personal 的字段）。
     * 调用方（问答流水线线程）负责 finally 里 {@link #clearUserOverrides()}；空/null 视为清除。
     */
    public void putUserOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            USER_OVERRIDE.remove();
        } else {
            USER_OVERRIDE.set(new HashMap<>(overrides));
        }
    }

    /** 清除个人配置覆盖（池化线程复用，本轮问答结束必须调用） */
    public void clearUserOverrides() {
        USER_OVERRIDE.remove();
    }

    /** 取当前线程个人配置覆盖的快照（副本，可能为空；供并行子线程重放，见 SubAgentOrchestrator） */
    public Map<String, String> currentUserOverrides() {
        Map<String, String> cur = USER_OVERRIDE.get();
        return cur == null || cur.isEmpty() ? Map.of() : new HashMap<>(cur);
    }

    /**
     * 取当前线程参数覆盖的快照（副本，可能为空）。
     * <p>用途：把本轮覆盖**显式**交给并行子线程——ThreadLocal 不随任务提交跨线程继承，
     * 池化线程里读到的永远是空覆盖（静默退化为全局配置）。调用方在子线程内 putOverrides(snapshot)
     * 并在 finally 里 clearOverride()（线程复用，必须清）。
     */
    public Map<String, String> currentOverrides() {
        Map<String, String> cur = OVERRIDE.get();
        return cur == null || cur.isEmpty() ? Map.of() : new HashMap<>(cur);
    }

    /**
     * 读取配置（显式覆盖 → 个人覆盖 → 缓存 → 默认值）。
     * 敏感项 RSA 密文在此透明解密（见 {@link #isSensitiveKey}）：缓存/DB 存密文，消费方拿明文（无前缀的历史明文原样返回，兼容存量）。
     * <p>
     * <b>个人专属键（schema personalOnly）没有全局层</b>：值引用的是某个用户登记的私有资产
     * （模型），残留的全局行一旦被无个人覆盖的线程读到，就是"管理员的模型被全平台使用"——
     * 这里是隔离的最后一道闸：未装载个人覆盖（或未设置）时一律返回空，各消费方按自身口径降级/回落。
     */
    public String get(String key) {
        Map<String, String> ov = OVERRIDE.get();
        if (ov != null && ov.containsKey(key)) return ov.get(key);
        Map<String, String> pv = USER_OVERRIDE.get();
        if (pv != null) {
            String u = pv.get(key);
            if (u != null && !u.isBlank()) return u;
        }
        if (schema.isPersonalOnly(key)) return "";
        String v = cache.get(key);
        if (v == null) v = defaults().getOrDefault(key, "");
        return isSensitiveKey(key) ? crypto.decrypt(v) : v;
    }

    /**
     * 无参 typed getter 的兜底链：缓存/DB 值缺失或解析失败 → 回退 {@code defaults()} 的权威默认值
     * → 仍失败返回中性值。此前各方法散落着魔法兜底（getDouble 兜 0.3 恰是 temperature 的默认、
     * getInt 兜 0 会把 chunk.maxImages 的解析失败变成"不限制"），统一改为跟随声明默认，
     * 仅 defaults() 也未注册该 key 时落到中性值。
     */
    private <T> T getOrDefaultParsed(String key, java.util.function.Function<String, T> parser, T neutral) {
        String raw = null;
        try {
            raw = get(key);
        } catch (Exception ignored) {
            // 读取本身异常（解密/覆盖层出错）按缺失处理，照样走兜底链
        }
        // 空值必须在解析**之前**短路：Boolean.parseBoolean("") 返回 false 且不抛异常，兜底链永远走不到
        // 第二级——「默认开的开关」在键未入库时会静默变成关（chat.resumeEnabled 就是这么整条特性没生效，
        // 当时只能靠把它登记进 defaults() 绕开）。数值解析抛异常才落到兜底，效果相同，故统一在此处理。
        if (raw != null && !raw.isBlank()) {
            try {
                return parser.apply(raw.trim());
            } catch (Exception ignored) {
                // 值非法 → 走权威默认
            }
        }
        // 个人专属键没有全局层（见 get() 的隔离闸）：空值即终态，不得再拿 defaults() 的种子顶上，
        // 否则"管理员的模型被全平台使用"会从这条兜底漏回来
        if (schema.isPersonalOnly(key)) {
            return neutral;
        }
        String seed = defaults().get(key);
        try {
            return seed == null || seed.isBlank() ? neutral : parser.apply(seed.trim());
        } catch (Exception e) {
            return neutral;
        }
    }

    public double getDouble(String key) {
        return getOrDefaultParsed(key, Double::parseDouble, 0.0);
    }

    public double getDouble(String key, double def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public int getInt(String key) {
        return getOrDefaultParsed(key, Integer::parseInt, 0);
    }

    public int getInt(String key, int def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public long getLong(String key) {
        return getOrDefaultParsed(key, Long::parseLong, 0L);
    }

    public long getLong(String key, long def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 读取布尔开关（无显式默认值）。
     * <p>注意：本方法对「既无配置行、也未在 {@link #defaults()} 登记」的键返回 <b>false</b>。
     * 默认应开启的开关请用 {@link #getBoolean(String, boolean)} 显式给出默认值，或在 defaults() 登记；
     * 启动时 {@link #auditDefaultOnSwitches()} 会把漏登记的「默认开」键点名告警。
     */
    public boolean getBoolean(String key) {
        return getOrDefaultParsed(key, Boolean::parseBoolean, false);
    }

    /**
     * 读取布尔开关，键缺失/空值/非法时用调用方给的默认值。
     * <p>新增「默认开」的开关一律用本方法（与 {@link #getInt(String, int)} 同模式）：
     * 只靠 {@link #defaults()} 登记也能生效，但登记点离使用点越远越容易漏，形参默认值是就地声明。
     */
    public boolean getBoolean(String key, boolean def) {
        return getOrDefaultParsed(key, Boolean::parseBoolean, def);
    }

    /** 保存可编辑项（白名单校验）→ 写 DB + 刷新缓存 */
    public Map<String, String> update(Map<String, Map<String, String>> groups) {
        Map<String, String> updates = new HashMap<>();
        // 不认识项的清单：必须在任何写入之前整笔拒绝，不能丢弃后继续——
        // 原先只把不认识的键静默扔掉，接口照样返回"配置已保存并生效"，库里一个字没改
        // （实测 rerank.maxHits 改了没反应，据此做的 A/B 结论全是错的）。
        // 个人配置侧（UserConfigService.save）已是拒绝口径，管理端对齐同一行为。
        Set<String> unknownKeys = new LinkedHashSet<>();
        if (groups != null) {
            for (Map.Entry<String, Map<String, String>> g : groups.entrySet()) {
                String prefix = g.getKey() + ".";
                for (Map.Entry<String, String> kv : g.getValue().entrySet()) {
                    String fullKey = prefix + kv.getKey();
                    if (schema.isEditable(fullKey)) {
                        updates.put(fullKey, kv.getValue() == null ? "" : kv.getValue().trim());
                    } else {
                        unknownKeys.add(fullKey);
                    }
                }
            }
        }
        if (!unknownKeys.isEmpty()) {
            throw new IllegalArgumentException("这些项系统不认，本次未保存任何改动："
                    + String.join("、", unknownKeys)
                    + "（可能是页面版本过旧，或该项已改名/取消）。请刷新设置页后重试");
        }
        // 掩码回写保护：snapshot 对敏感项脱敏为 "****后4位"，前端未修改时会把掩码原样提交；
        // 掩码值（**** 开头）一律跳过更新，避免覆盖库中真实 key（真实 master key 不可能以 **** 开头）
        updates.entrySet().removeIf(kv ->
                isSensitiveKey(kv.getKey()) && kv.getValue() != null && kv.getValue().startsWith("****"));

        // 个人专属键（模型引用，schema personalOnly）拒绝管理端写入：这类值的归属人是单个用户，
        // 放进全局槽位就是把"某人的模型"用成全平台默认（隔离原则）。设置页已不下发这些字段，
        // 这里拦住构造请求；请到「个人设置」配置（模型引用只允许归属于操作者本人）
        for (String k : updates.keySet()) {
            if (schema.isPersonalOnly(k)) {
                throw new IllegalArgumentException("「" + schema.helpOrDefault(k, k)
                        + "」已调整为个人设置项（模型归属登记人本人），请在「个人设置」中配置");
            }
        }

        // ---------- schema 驱动校验（类型 / 范围 / 枚举 / 布尔）----------
        // 规则全部来自 classpath:config-schema.json（与下发前端渲染的是同一份定义）。此前按前缀分组
        // 手写的校验清单已删除：其中 20 条对应的键早已退出可编辑白名单（死校验），其余 43 条的约束
        // 均已被 schema 覆盖且不更松。窗口与输出的跨字段一致性归模型管理登记时校验（窗口已无全局兜底）。
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String err = schema.validate(kv.getKey(), kv.getValue());
            if (err != null) throw new IllegalArgumentException(err);
        }
        // 切换到 meilisearch：保存前强制探测服务可用性，不可用则阻止保存（避免切到不可用的空索引）。
        // 这是"服务可达性"而不是"取值合法性"，故留在代码里而不进 schema。
        // 探测必须用**本次提交的值**（updates 里的新地址/新 Key），不能用 checkAvailable() 读已保存的旧值：
        // 「改地址 + 切引擎」常在同一次保存里完成（地址与 Key 只有选中 meilisearch 后才可见，用户是
        // 填好地址后一起保存的），拿旧值探测等于用上一次的服务地址去验这一次的新地址——首次配置
        // 必然被误判为不可用而拒绝保存，用户陷入死锁。updates 未带的键才回落已保存配置。
        String engine = updates.get("keyword.engine");
        if (engine != null && "meilisearch".equalsIgnoreCase(engine.trim())) {
            boolean ok = keywordIndexService.checkAvailable(updates.get("keyword.baseUrl"), updates.get("keyword.apiKey"));
            if (!ok) {
                String url = updates.getOrDefault("keyword.baseUrl", keywordIndexService.debugUnavailableReason());
                throw new IllegalArgumentException("Meilisearch 服务不可用（"
                        + (url == null || url.isBlank() ? "探测失败" : "地址 " + url + " 探测失败")
                        + "），请先启动 Meilisearch（docker compose 或本地）再切换");
            }
        }

        // 视觉网关地址校验已移除：vision.baseUrl 不在可编辑白名单、也无运行时读取点
        // （视觉网关来自供应商表），保留这段只会校验一个永远不会被消费的键

        // 值真的变了才通知（前端保存总是提交当前值；不能把「点了一次保存」当成配置变更）
        Set<String> changedKeys = new LinkedHashSet<>();
        for (String key : updates.keySet()) {
            if (!java.util.Objects.equals(get(key), updates.get(key))) changedKeys.add(key);
        }

        // 敏感 key RSA 加密入库：明文→密文（已加密值原样保留；空值不加密直接存空）
        for (Map.Entry<String, String> kv : updates.entrySet()) {
            String v = kv.getValue();
            if (isSensitiveKey(kv.getKey()) && v != null && !v.isBlank() && !crypto.isEncrypted(v)) {
                kv.setValue(crypto.encrypt(v));
            }
        }

        for (Map.Entry<String, String> kv : updates.entrySet()) {
            Config c = configMapper.selectById(kv.getKey());
            if (c == null) {
                c = new Config();
                c.setConfigKey(kv.getKey());
                c.setConfigValue(kv.getValue());
                c.setRemark(schema.help(kv.getKey()));
                configMapper.insert(c);
            } else {
                c.setConfigValue(kv.getValue());
                configMapper.updateById(c);
            }
        }
        // 引擎切换检测：仅当 keyword.engine 值真正变化（如 mysql→meilisearch）才全量重建。
        // 必须在刷新缓存前取旧值——前端保存总是提交当前 engine 值，若无条件重建，
        // 每次"改任意配置保存"都会误触发全量灌库（资源浪费 + 日志误导）
        boolean engineSwitchedToMeili = false;
        if (updates.containsKey("keyword.engine") && "meilisearch".equalsIgnoreCase(updates.get("keyword.engine"))) {
            String oldEngine = get("keyword.engine"); // 刷新前 cache 仍是旧值
            engineSwitchedToMeili = oldEngine == null || !"meilisearch".equalsIgnoreCase(oldEngine);
        }
        // 刷新缓存
        Map<String, String> newCache = new HashMap<>(cache);
        newCache.putAll(updates);
        cache = newCache;
        syncProperties();
        log.info("模型配置已更新: {}", updates.keySet());
        notifyConfigChanged(changedKeys);
        // 广播其他实例刷新（多副本部署配置同步）
        publishConfigChanged();
        // 自动全量重建（仅真实切换 mysql→meilisearch 时；reindexAll 内部有防重入与可用性检查）
        if (engineSwitchedToMeili) {
            try {
                keywordIndexService.reindexAll();
                log.info("[Config] 关键词引擎已切换至 Meilisearch，自动触发索引全量重建");
            } catch (Exception e) {
                log.warn("[Config] 自动触发索引重建失败（可稍后手动调 /api/ai/search-index/reindex）: {}", e.getMessage());
            }
        }
        return updates;
    }

    /**
     * 将指定分组恢复为出厂默认值（defaults() 值写库 + 刷新缓存 + Redis 广播）。
     * <ul>
     *   <li>白名单分组与 snapshot 对齐，但<b>排除 embedding</b>：向量模型恢复会触发全量重嵌入，
     *       必须走设置页正常流程（探测→确认）；</li>
     *   <li><b>跳过敏感项</b>（*.apiKey / *.clientSecret）：密钥以 RSA 加密存于 DB，恢复默认不得清空用户已配置的密钥
     *       （env 回退值可能为空导致模型不可用）；</li>
     *   <li>keyword.engine 不触发索引联动（恢复 mysql 后关键词走 MySQL LIKE，Meili 索引可留待后续重建）。</li>
     * </ul>
     *
     * @param groups 待恢复分组（chat/vision/chunk/parse/upload/retrieval/rerank/keyword/context/ratelimit）
     * @return 实际恢复的键值
     */
    public Map<String, String> resetDefaults(Collection<String> groups) {
        Set<String> allowed = new HashSet<>(List.of(
                "chat", "vision", "chunk", "parse", "upload", "retrieval", "rerank",
                "keyword", "context", "ratelimit"));
        Set<String> targets = new HashSet<>();
        for (String g : groups) {
            if (g == null || g.isBlank() || !allowed.contains(g)) {
                throw new IllegalArgumentException("不支持恢复默认的分组: " + g);
            }
            targets.add(g);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("请至少指定一个分组");
        }
        Map<String, String> defs = defaults();
        Map<String, String> next = new HashMap<>(cache);
        Map<String, String> reset = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : defs.entrySet()) {
            String k = e.getKey();
            int dot = k.indexOf('.');
            if (dot <= 0 || !targets.contains(k.substring(0, dot))) continue;
            if (isSensitiveKey(k) || schema.isPersonalOnly(k)) continue; // 密钥不随"恢复默认"清空；个人专属键无全局槽位
            String v = e.getValue();
            try {
                Config c = configMapper.selectById(k);
                if (c == null) {
                    c = new Config();
                    c.setConfigKey(k);
                    c.setConfigValue(v);
                    c.setRemark(schema.helpOrDefault(k, "只读配置"));
                    configMapper.insert(c);
                } else {
                    c.setConfigValue(v);
                    configMapper.updateById(c);
                }
            } catch (Exception ex) {
                log.warn("[Config] 恢复默认写库失败 {}: {}", k, ex.getMessage());
                continue;
            }
            next.put(k, v);
            reset.put(k, v);
        }
        Set<String> changedKeys = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : reset.entrySet()) {
            if (!java.util.Objects.equals(get(e.getKey()), e.getValue())) changedKeys.add(e.getKey());
        }
        cache = next;
        syncProperties();
        notifyConfigChanged(changedKeys);
        publishConfigChanged();
        log.info("[Config] 分组恢复默认完成: {}（{} 项）", targets, reset.size());
        return reset;
    }

    /**
     * 系统内部回写（不经字段定义白名单）：供运行流程记录"既成事实"型配置，
     * 当前唯一用途是评估体检报告写 eval.lastReport（看板红绿灯读取）。
     * 与 update() 的区别：不做业务校验、不加密、不触发重嵌入/重建索引等联动，
     * 只落库 + 刷新本地缓存 + 广播其他副本。失败仅告警（记录性数据，不阻断主流程）。
     */
    public void putInternal(String key, String value) {
        try {
            String old = get(key);
            Config c = configMapper.selectById(key);
            if (c == null) {
                c = new Config();
                c.setConfigKey(key);
                c.setConfigValue(value);
                c.setRemark("只读配置");
                configMapper.insert(c);
            } else {
                c.setConfigValue(value);
                configMapper.updateById(c);
            }
            Map<String, String> newCache = new HashMap<>(cache);
            newCache.put(key, value);
            cache = newCache;
            syncProperties();
            if (!java.util.Objects.equals(old, value)) {
                notifyConfigChanged(Set.of(key));
            }
            publishConfigChanged();
            log.info("[Config] 系统内部记录已更新: {}={}", key, value);
        } catch (Exception e) {
            log.warn("[Config] 系统内部记录写入失败: {}={} error={}", key, value, e.getMessage());
        }
    }

    /** 全量配置（供配置界面展示；敏感项脱敏） */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        // 分组由 defaults() 的键前缀自动派生：不再手写组名数组——漏一个前缀该组就永远回显不出来
        // （历史上 images / session / cleanup / eval / cache 就是这么漏掉的）。defaults() 只求值一次。
        Map<String, String> defs = defaults();
        Set<String> groups = new LinkedHashSet<>();
        for (String k : defs.keySet()) {
            int dot = k.indexOf('.');
            if (dot > 0) groups.add(k.substring(0, dot));
        }
        for (String g : groups) {
            Map<String, Object> items = new LinkedHashMap<>();
            for (Map.Entry<String, String> d : defs.entrySet()) {
                if (!d.getKey().startsWith(g + ".")) continue;
                String shortKey = d.getKey().substring(g.length() + 1);
                String value = get(d.getKey());
                if (isSensitiveKey(d.getKey()) && value.length() > 4) {
                    value = "****" + value.substring(value.length() - 4);
                }
                items.put(shortKey, Map.of(
                        "value", value,
                        "editable", schema.isEditable(d.getKey()),
                        "tier", schema.tier(d.getKey())));
            }
            result.put(g, items);
        }
        return result;
    }
}
