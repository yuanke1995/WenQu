package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.McpCallLogMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.model.ApiKey;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.McpCallLog;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.RequestUser;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP（Model Context Protocol）服务端：把「已发布的智能体」对外暴露成 MCP Server，
 * 供 Claude Desktop / Cursor / 其他 Agent 以标准协议调用（双向 MCP 的 Server 一半）。
 * <p>
 * <b>形态（对标 Dify「把应用发布为 MCP Server」）</b>：一个智能体一个端点 {@code /ai/mcp/{token}}，
 * token 即凭据（与网页分享 {@code /s/{token}} 同源、同一条 {@code c_ai_agent_share} 记录），
 * 开启/停用/撤销与分享共用一套操作；端点暴露 <b>一个</b> 以该智能体命名的提问工具。
 * <p>
 * <b>安全模型（复用游客分享的既有结论，不另起一套）</b>：
 * <ul>
 *   <li>身份：检索可见性与默认模型按<b>发布者</b>执行（{@code created_by}）——发布即授权；</li>
 *   <li>能力：走 {@code RagService.chat} 的 guestMode，工具白名单收窄到知识检索与内置项，
 *       沙盒/产物/个人技能/个人 MCP 等身份敏感能力一律不暴露；</li>
 *   <li>凭据：URL 路径里的 token，停用（enabled=0）或未开启 MCP（mcp_enabled=0）或撤销（删行）立即失效；</li>
 *   <li>限频：优先按客户端 IP（取不到时按 token）走 chat 桶，防止单个端点被打爆；</li>
 *   <li>DNS rebinding：按 MCP 规范校验 Origin（浏览器型客户端），非浏览器客户端无 Origin 不受影响。</li>
 * </ul>
 * <p>
 * <b>实现要点</b>：传输用 {@link WebMvcStatelessServerTransport}（无状态 Streamable HTTP）——
 * 每次请求都是完整的一次性对话，无需在服务端保存 MCP 会话；也正因为无状态，
 * 这里可以<b>按请求</b>构建 transport 与 server，工具名能跟随智能体当前的名称，
 * 且不需要维护"配置变更后重建实例"的缓存一致性（改了配置下一次请求就生效）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class McpServerService {

    /** 端点路径前缀（context-path /ai 之外）：完整对外地址为 {origin}/ai/mcp/{token} */
    public static final String ENDPOINT_PREFIX = "/mcp/";
    /** 路由 pattern（转发层用它接住所有 token 的请求） */
    public static final String ENDPOINT_PATTERN = "/mcp/{token}";
    /** 平台级入口路径（不带 token）：完整对外地址为 {origin}/ai/mcp */
    public static final String PLATFORM_ENDPOINT = "/mcp";

    /** MCP 访客会话 uid 前缀（非真实用户；SessionService 按 owner 精确匹配天然隔离） */
    private static final String MCP_VISITOR_UID_PREFIX = "mcp-visitor:";
    /** 传输上下文 key：分享 token */
    private static final String CTX_TOKEN = "wenqu.mcp.token";
    /** 传输上下文 key：客户端 IP（限频用） */
    private static final String CTX_IP = "wenqu.mcp.ip";
    /** 单条提问长度上限（与游客对话同口径） */
    private static final int MAX_QUESTION_CHARS = 8000;
    /** Origin 未配置允许列表时放行的回环主机（本地自部署/本机客户端） */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** 平台级入口的访客会话 uid 前缀（会话不进任何人的会话列表，但归属可追溯） */
    private static final String MCP_CLIENT_UID_PREFIX = "mcp-client:";
    /** 传输上下文 key：平台级入口解析出的 API Key 记录 */
    private static final String CTX_API_KEY = "wenqu.mcp.apiKey";

    private final AgentShareService agentShareService;
    private final SessionService sessionService;
    private final RagService ragService;
    private final RateLimitService rateLimitService;
    private final ModelRegistryService modelRegistryService;
    private final ConfigService configService;
    private final ApiKeyService apiKeyService;
    private final UserMapper userMapper;
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentService agentService;
    private final ResourceVisibilityService visibility;
    private final McpCallLogMapper mcpCallLogMapper;

    public McpServerService(AgentShareService agentShareService, SessionService sessionService,
                            RagService ragService, RateLimitService rateLimitService,
                            ModelRegistryService modelRegistryService, ConfigService configService,
                            ApiKeyService apiKeyService, UserMapper userMapper,
                            KnowledgeRetrievalTool knowledgeRetrievalTool,
                            KnowledgeBaseService knowledgeBaseService, AgentService agentService,
                            ResourceVisibilityService visibility, McpCallLogMapper mcpCallLogMapper) {
        this.agentShareService = agentShareService;
        this.sessionService = sessionService;
        this.ragService = ragService;
        this.rateLimitService = rateLimitService;
        this.modelRegistryService = modelRegistryService;
        this.configService = configService;
        this.apiKeyService = apiKeyService;
        this.userMapper = userMapper;
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
        this.knowledgeBaseService = knowledgeBaseService;
        this.agentService = agentService;
        this.visibility = visibility;
        this.mcpCallLogMapper = mcpCallLogMapper;
    }

    /** MCP 端点总开关（mcp.server.enabled，默认关：不主动对外暴露能力） */
    public boolean enabled() {
        return configService.getBoolean("mcp.server.enabled");
    }

    /**
     * 处理一次 Streamable HTTP 请求：解析 token → 构建该智能体的无状态 MCP Server → 交给传输层处理。
     * <p>
     * token 无效/未开启/已停用一律 404（不泄露存在性，与游客分享同口径）。
     */
    public ServerResponse handle(ServerRequest request) throws Exception {
        String token = request.pathVariable("token");
        if (!enabled()) {
            return jsonError(HttpStatus.NOT_FOUND, "MCP 端点未启用（可在系统设置开启）");
        }
        Resolved resolved;
        try {
            resolved = resolve(token);
        } catch (BizException e) {
            log.warn("[MCP-SERVER] 端点解析失败 token={}***: {}", tokenPrefix(token), e.getMessage());
            return jsonError(HttpStatus.NOT_FOUND, e.getMessage());
        }
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .messageEndpoint(ENDPOINT_PREFIX + token)
                .contextExtractor(req -> McpTransportContext.create(Map.of(
                        CTX_TOKEN, token, CTX_IP, clientIp(req))))
                // MCP 规范要求 HTTP 传输校验 Origin（防 DNS rebinding 攻击）
                .securityValidator(this::validateHeaders)
                .build();
        McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("wenqu", "1.0.0"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions("问渠智能体「" + agentName(resolved.agent()) + "」的 MCP 端点。"
                        + "调用唯一工具即可向该智能体提问，它会检索自己的知识库并给出完整回答。")
                .tools(new McpStatelessServerFeatures.SyncToolSpecification(
                        buildTool(resolved.agent()), (ctx, call) -> ask(resolved, ctx, call)))
                .build();
        // 传输层自己的 RouterFunction 绑定的是本 token 的具体路径，这里把请求交给它处理
        return transport.getRouterFunction().route(request)
                .orElseThrow(() -> new IllegalStateException("MCP 传输层路由未命中: " + tokenPrefix(token)))
                .handle(request);
    }

    // ==================== 平台级入口 /ai/mcp（API Key 凭据 + 固定元工具集） ====================

    /**
     * 平台级入口：一个地址暴露整套能力，凭据是请求头里的 API Key，身份 = Key 的创建者。
     * <p>
     * 与 per-agent 端点的分工：端点不属于某个智能体，工具集固定（无状态传输下 tools 在注册时就固定，
     * 不能按凭据动态变化），因此<b>权限在每个 handler 内按身份裁决</b>——可见的知识库、可用的智能体
     * 与这个人在网页上看到的完全一致（复用 ResourceVisibilityService 与 AgentService 的既有口径）。
     */
    public ServerResponse handlePlatform(ServerRequest request) throws Exception {
        if (!enabled()) {
            return jsonError(HttpStatus.NOT_FOUND, "MCP 端点未启用（可在系统设置开启）");
        }
        String plainKey = credential(request);
        ApiKey key = plainKey.isEmpty() ? null : apiKeyService.verify(plainKey);
        if (key == null || key.getMcpEnabled() == null || key.getMcpEnabled() != 1) {
            log.warn("[MCP-SERVER] 平台级入口凭据无效或未授权 ip={}", clientIp(request));
            return jsonError(HttpStatus.UNAUTHORIZED, "API Key 无效，或未授权访问 MCP 入口（需在 API Key 管理中开启）");
        }
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .messageEndpoint(PLATFORM_ENDPOINT)
                .contextExtractor(req -> McpTransportContext.create(Map.of(
                        CTX_API_KEY, key, CTX_IP, clientIp(req))))
                .securityValidator(this::validateHeaders)
                .build();
        McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("wenqu", "1.0.0"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions("问渠（WenQu）知识中枢：可检索调用者可见的知识库、向智能体提问、"
                        + "列出可见的知识库与可用智能体。所有能力都按调用者自身的可见范围执行。")
                .tools(platformTools())
                .build();
        apiKeyService.touchLastUsed(key.getId());
        return transport.getRouterFunction().route(request)
                .orElseThrow(() -> new IllegalStateException("MCP 传输层路由未命中（平台级入口）"))
                .handle(request);
    }

    /** 平台级固定工具集（名字固定 ⇒ 权限在 handler 内按身份裁决，不靠工具名区分人） */
    private McpStatelessServerFeatures.SyncToolSpecification[] platformTools() {
        return new McpStatelessServerFeatures.SyncToolSpecification[]{
                new McpStatelessServerFeatures.SyncToolSpecification(
                        tool("wenqu_search_knowledge", "检索知识库",
                                "在调用者可见的知识库中检索与关键词相关的片段（含章节路径、标题、正文）。"
                                        + "kbId 留空 = 检索全部可见库；结果已按相关度门槛过滤，未命中会明确说明。",
                                props("query", "string", "检索关键词或短语，越精准越好（可含功能名/字段名/操作对象）",
                                        "topK", "integer", "返回条数 1~5，默认 5",
                                        "kbId", "string", "可选：限定在某个知识库内检索（用 wenqu_list_knowledge_bases 取 id）"),
                                List.of("query")),
                        this::searchKnowledge),
                new McpStatelessServerFeatures.SyncToolSpecification(
                        tool("wenqu_ask", "向智能体提问",
                                "跑一次完整问答（检索 + 模型作答），拿到完整回答文本。"
                                        + "agentId 留空 = 按调用者的默认配置问答；传入则按该智能体的提示词、模型与知识库范围作答。"
                                        + "需要多轮上下文时把上一次返回的 sessionId 传回来。",
                                props("question", "string", "提问内容",
                                        "agentId", "string", "可选：指定智能体（用 wenqu_list_agents 取 id）",
                                        "sessionId", "string", "可选：上一次调用返回的会话 ID，传入可续接上下文"),
                                List.of("question")),
                        this::askPlatform),
                new McpStatelessServerFeatures.SyncToolSpecification(
                        tool("wenqu_list_knowledge_bases", "列出可见知识库",
                                "返回调用者可见的知识库 id 与名称（检索时可指定 kbId 限定范围）。",
                                Map.of(), List.of()),
                        (ctx, call) -> listKnowledgeBases(ctx)),
                new McpStatelessServerFeatures.SyncToolSpecification(
                        tool("wenqu_list_agents", "列出可用智能体",
                                "返回调用者可使用的智能体 id、名称与描述（wenqu_ask 的 agentId 从这里取）。",
                                Map.of(), List.of()),
                        (ctx, call) -> listAgents(ctx))
        };
    }

    private McpSchema.CallToolResult searchKnowledge(McpTransportContext ctx, McpSchema.CallToolRequest call) {
        ApiKey key = apiKeyOf(ctx);
        return audit("platform", "wenqu_search_knowledge", keyOwner(key), keyRef(key),
                key == null ? null : key.getId(), null, ipOf(ctx), () -> searchKnowledgeBody(key, call));
    }

    private McpSchema.CallToolResult searchKnowledgeBody(ApiKey key, McpSchema.CallToolRequest call) {
        Map<String, Object> args = argsOf(call);
        String query = text(args.get("query"));
        if (query.isEmpty()) return toolError("参数 query 不能为空");
        Integer topK = args.get("topK") == null ? null : toInt(args.get("topK"));
        String kbId = text(args.get("kbId"));
        return withIdentity(key, () -> {
            if (!kbId.isEmpty() && !visibleKbIds().contains(kbId)) {
                return toolError("kbId 不存在或不在可见范围内（用 wenqu_list_knowledge_bases 查看）");
            }
            try {
                // 工具范围与主链路同语义：ThreadLocal 限定本线程的检索库界（finally 必清，池化线程会复用）
                KnowledgeRetrievalTool.setKbScope(kbId.isEmpty() ? null : List.of(kbId), null);
                return ok(knowledgeRetrievalTool.searchKnowledge(query, topK));
            } finally {
                KnowledgeRetrievalTool.clearKbScope();
            }
        });
    }

    private McpSchema.CallToolResult askPlatform(McpTransportContext ctx, McpSchema.CallToolRequest call) {
        ApiKey key = apiKeyOf(ctx);
        String agentId = text(argsOf(call).get("agentId"));
        return audit("platform", "wenqu_ask", keyOwner(key), keyRef(key),
                key == null ? null : key.getId(), agentId.isEmpty() ? null : agentId, ipOf(ctx),
                () -> askPlatformBody(key, ctx, call));
    }

    private McpSchema.CallToolResult askPlatformBody(ApiKey key, McpTransportContext ctx, McpSchema.CallToolRequest call) {
        Map<String, Object> args = argsOf(call);
        String question = text(args.get("question"));
        if (question.isEmpty()) return toolError("参数 question 不能为空");
        if (question.length() > MAX_QUESTION_CHARS) {
            return toolError("单条内容过长（最多 " + MAX_QUESTION_CHARS + " 字）");
        }
        String agentId = text(args.get("agentId"));
        String sessionId = text(args.get("sessionId"));
        String ip = text(ctx.get(CTX_IP));
        try {
            rateLimitService.checkRateLimit("chat", ip.isEmpty() ? "mcp-key:" + key.getId() : "ip:" + ip);
        } catch (BizException e) {
            return toolError(e.getMessage());
        }
        return withIdentity(key, () -> {
            if (!agentId.isEmpty() && !visibleAgentIds().contains(agentId)) {
                return toolError("agentId 不存在或不可用（用 wenqu_list_agents 查看）");
            }
            // 会话挂在独立 uid 下：不污染调用者自己的会话列表，归属仍可追溯
            String owner = MCP_CLIENT_UID_PREFIX + key.getCreatedBy();
            String sid = sessionId;
            if (sid.isEmpty()) {
                sid = sessionService.createSession(owner);
            } else {
                try {
                    sessionService.assertOwned(sid, owner);
                } catch (BizException e) {
                    return toolError("sessionId 无效或不属于该 Key");
                }
            }
            long timeoutMs = Math.max(30_000, configService.getInt("mcp.server.timeoutMs", 180_000));
            CollectingSseEmitter sink = new CollectingSseEmitter();
            try {
                // 非游客模式：身份与网页问答完全一致（智能体、个人技能、个人 MCP、工具开关等按本人配置）
                ragService.chat(sid, question, List.of(), List.of(), List.of(),
                        false, agentId.isEmpty() ? null : agentId, "", key.getCreatedBy(), sink, false);
                if (!sink.awaitDone(timeoutMs)) {
                    return toolError("执行超时（" + timeoutMs + " ms 内未收到完成事件）");
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    return toolError(sink.lastError());
                }
                String answer = sink.answer();
                if (answer.isBlank()) return toolError("本轮未产生回答内容");
                return McpSchema.CallToolResult.builder()
                        .addTextContent(answer)
                        .structuredContent(Map.of("sessionId", sid))
                        .build();
            } finally {
                RagService.forgetSseChannel(sink);
            }
        });
    }

    private McpSchema.CallToolResult listKnowledgeBases(McpTransportContext ctx) {
        ApiKey key = apiKeyOf(ctx);
        return audit("platform", "wenqu_list_knowledge_bases", keyOwner(key), keyRef(key),
                key == null ? null : key.getId(), null, ipOf(ctx),
                () -> withIdentity(key, () -> {
                    ResourceVisibilityService.Principal p = new ResourceVisibilityService.Principal(
                            RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (KnowledgeBase kb : knowledgeBaseService.list()) {
                        if (!visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(),
                                ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", kb.getId());
                        m.put("name", kb.getName() == null ? "" : kb.getName());
                        m.put("isDefault", kb.getIsDefault() != null && kb.getIsDefault() == 1);
                        out.add(m);
                    }
                    return ok(JSON.toJSONString(out));
                }));
    }

    private McpSchema.CallToolResult listAgents(McpTransportContext ctx) {
        ApiKey key = apiKeyOf(ctx);
        return audit("platform", "wenqu_list_agents", keyOwner(key), keyRef(key),
                key == null ? null : key.getId(), null, ipOf(ctx),
                () -> withIdentity(key, () -> ok(JSON.toJSONString(agentService.available()))));
    }

    // ==================== 端点解析 ====================

    /** 解析结果：分享配置 + 智能体 + 发布者（身份来源）+ 游客模型引用 */
    private record Resolved(AgentShare share, Agent agent, User owner, String modelRef) {
    }

    /**
     * token → 可用的 MCP 端点上下文。逐项 fail-loud（与游客分享同口径）：
     * 分享不存在/已停用 → 404；未开启 MCP → 404；智能体或发布者缺失 → 404；模型不可用 → 404。
     */
    private Resolved resolve(String token) {
        var guest = agentShareService.resolveGuest(token);
        if (guest.share().getMcpEnabled() == null || guest.share().getMcpEnabled() != 1) {
            throw new BizException("该智能体未开启 MCP 端点");
        }
        User owner = guest.owner();
        if (owner.getStatus() != null && owner.getStatus() == 0) {
            throw new BizException("分享不存在或已停止访问");
        }
        String modelRef = guest.share().getModelRef() == null ? "" : guest.share().getModelRef();
        // 发布后模型可能被停用/删除：每次调用都复核（fail-loud，而不是拿到空模型静默报错）
        if (!modelRef.isBlank()) {
            modelRegistryService.assertUsable(modelRef, owner.getUid(), owner.getRole());
        }
        return new Resolved(guest.share(), guest.agent(), owner, modelRef);
    }

    // ==================== 工具 ====================

    /**
     * 该智能体对应的工具：名字取自智能体名（ASCII 化），中文名退化成 agentId 前 8 位——
     * MCP 工具名要求可预测的 ASCII 标识，中文名直接用作 name 会被严格校验拒绝。
     */
    private McpSchema.Tool buildTool(Agent agent) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", Map.of("type", "string",
                "description", "提问内容（会检索该智能体的知识库并按其提示词作答）"));
        props.put("sessionId", Map.of("type", "string",
                "description", "可选：上一次调用返回的会话 ID，传入可续接上下文；不传则开启新会话"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", props, List.of("question"), false, null, null);
        return McpSchema.Tool.builder()
                .name(toolName(agent))
                .title(agentName(agent))
                .description("向问渠智能体「" + agentName(agent) + "」提问并获取完整回答"
                        + "（自动检索其知识库、按该智能体的提示词与工具配置作答）。"
                        + "适合需要该智能体专业知识的问答；不需要上下文时每次调用都是独立的一轮。")
                .inputSchema(schema)
                .build();
    }

    /** 工具名：ask-{智能体名 ASCII 化}，中文名退化成 ask-agent-{id 前 8 位} */
    private String toolName(Agent agent) {
        String slug = agent.getName() == null ? "" : agent.getName()
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            String id = agent.getId() == null ? "" : agent.getId();
            slug = "agent-" + id.substring(0, Math.min(8, id.length()));
        }
        return ("ask-" + slug).substring(0, Math.min(64, ("ask-" + slug).length()));
    }

    /**
     * 工具执行：跑一次完整的问答流水线（复用 {@link RagService#chat} 的游客模式），
     * 用 {@link CollectingSseEmitter} 收集完整回答后一次性返回（MCP 的 tools/call 是请求-响应语义）。
     * 外层 {@link #audit} 统一落调用审计（每次外部调用一行，可回溯）。
     */
    private McpSchema.CallToolResult ask(Resolved r, McpTransportContext ctx, McpSchema.CallToolRequest call) {
        return audit("agent", toolName(r.agent()), r.owner().getUid(),
                "token:" + tokenPrefix(r.share().getToken()), null, r.agent().getId(), ipOf(ctx),
                () -> askBody(r, ctx, call));
    }

    private McpSchema.CallToolResult askBody(Resolved r, McpTransportContext ctx, McpSchema.CallToolRequest call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        String question = text(args.get("question"));
        if (question.isEmpty()) {
            return toolError("参数 question 不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            return toolError("单条内容过长（最多 " + MAX_QUESTION_CHARS + " 字）");
        }
        // 限频：IP 优先（同一端点被多客户端共享时互不影响），取不到 IP 时退回按 token 计
        String ip = text(ctx.get(CTX_IP));
        String limitKey = ip.isEmpty() ? "mcp:" + r.share().getToken() : "ip:" + ip;
        try {
            rateLimitService.checkRateLimit("chat", limitKey);
        } catch (BizException e) {
            return toolError(e.getMessage());
        }
        // 会话归属按端点派生：持有 token 的人可以续接本端点产生的会话（与游客分享同语义）
        String visitorUid = MCP_VISITOR_UID_PREFIX + digest(r.share().getToken());
        String sessionId = text(args.get("sessionId"));
        if (sessionId.isEmpty()) {
            sessionId = sessionService.createSession(visitorUid);
        } else {
            try {
                sessionService.assertOwned(sessionId, visitorUid);
            } catch (BizException e) {
                return toolError("sessionId 无效或不属于该端点");
            }
        }
        long timeoutMs = Math.max(30_000, configService.getInt("mcp.server.timeoutMs", 180_000));
        CollectingSseEmitter sink = new CollectingSseEmitter();
        try {
            // guestMode=true：工具白名单收窄（知识检索+内置），身份按发布者装载
            ragService.chat(sessionId, question, List.of(), List.of(), List.of(),
                    false, r.agent().getId(), r.modelRef(), r.owner().getUid(), sink, true);
            if (!sink.awaitDone(timeoutMs)) {
                return toolError("执行超时（" + timeoutMs + " ms 内未收到完成事件）");
            }
            if (sink.lastError() != null && !sink.lastError().isBlank()) {
                return toolError(sink.lastError());
            }
            String answer = sink.answer();
            if (answer.isBlank()) {
                return toolError("本轮未产生回答内容");
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(answer)
                    // 结构化输出给支持它的客户端（续接上下文用）；文本输出对老客户端始终可读
                    .structuredContent(Map.of("sessionId", sessionId))
                    .build();
        } finally {
            // 收集型通道不触发 onCompletion，必须主动清登记，否则 RagService 的 ACTIVE_SSE 残留
            RagService.forgetSseChannel(sink);
        }
    }

    // ==================== 内部 ====================

    // ==================== 调用审计（c_ai_mcp_call_log） ====================

    /**
     * 统一调用埋点：计时 → 执行 → 按 {@code isError}/异常判定结果 → 落一行调用日志。
     * 覆盖全部五个工具 handler（两类入口），是"谁在什么时候用哪个凭据调了哪个工具、
     * 成没成功、花了多久"的唯一记录点。
     * <p>
     * 日志写失败只告警不外抛：审计是旁路观察面，不能反过来打断外部客户端的正常调用；
     * 但告警必须显式出现在日志里，不允许静默吞掉。
     */
    private McpSchema.CallToolResult audit(String channel, String tool, String ownerUid, String credentialRef,
                                           String apiKeyId, String agentId, String ip,
                                           java.util.function.Supplier<McpSchema.CallToolResult> body) {
        long start = System.currentTimeMillis();
        McpSchema.CallToolResult res;
        boolean success = false;   // 兜底初始化：Error 型异常直接穿 finally 时按失败记录
        String error = null;
        try {
            res = body.get();
            success = !Boolean.TRUE.equals(res.isError());
            if (!success) error = firstText(res);
        } catch (RuntimeException e) {
            success = false;
            error = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            res = toolError("调用处理异常: " + error);
        } finally {
            long cost = System.currentTimeMillis() - start;
            try {
                McpCallLog row = new McpCallLog();
                row.setId(UUID.randomUUID().toString());
                row.setChannel(channel);
                row.setToolName(tool);
                row.setOwnerUid(ownerUid);
                row.setCredentialRef(credentialRef);
                row.setApiKeyId(apiKeyId);
                row.setAgentId(agentId);
                row.setCallerIp(ip);
                row.setDurationMs(cost);
                row.setSuccess(success ? 1 : 0);
                row.setErrorMsg(error == null ? null : error.substring(0, Math.min(error.length(), 500)));
                row.setCreatedAt(LocalDateTime.now());
                mcpCallLogMapper.insert(row);
            } catch (Exception logEx) {
                log.warn("[MCP-AUDIT] 调用日志落库失败（不影响本次调用）: {}", logEx.getMessage());
            }
        }
        return res;
    }

    /** isError 结果里的首段文本（审计记录失败原因用） */
    private static String firstText(McpSchema.CallToolResult res) {
        try {
            if (res.content() == null || res.content().isEmpty()) return "工具返回错误（无文本详情）";
            Object c = res.content().get(0);
            if (c instanceof McpSchema.TextContent tc) return tc.text();
            return String.valueOf(c);
        } catch (Exception e) {
            return "工具返回错误（详情解析失败）";
        }
    }

    /** 传输上下文里的调用者 IP */
    private static String ipOf(McpTransportContext ctx) {
        return text(ctx.get(CTX_IP));
    }

    /** 平台级 Key 的归属 uid（Key 在 handlePlatform 已验真，这里防御性取值） */
    private static String keyOwner(ApiKey key) {
        return key == null || key.getCreatedBy() == null ? "" : key.getCreatedBy();
    }

    /** 平台级 Key 的审计指代：key:{id}（不落明文 Key，ID 足以与管理页对上号） */
    private static String keyRef(ApiKey key) {
        return key == null ? "" : "key:" + key.getId();
    }

    /**
     * Origin 校验（MCP 规范要求 HTTP 传输防 DNS rebinding）：
     * 无 Origin（Claude/Cursor 等本地进程客户端）直接放行；配置了允许列表则逐个比对，
     * 未配置时只允许回环地址（浏览器脚本跨域打到本机的场景仍可用）。
     */
    private void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String origin = null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("origin") && !e.getValue().isEmpty()) {
                origin = e.getValue().get(0);
                break;
            }
        }
        if (origin == null || origin.isBlank()) return; // 非浏览器客户端
        String raw = configService.get("mcp.server.allowedOrigins");
        if (raw != null && !raw.isBlank()) {
            for (String allowed : raw.split(",")) {
                String a = allowed.trim();
                if (!a.isEmpty() && a.equals(origin)) return;
            }
            throw new ServerTransportSecurityException(403, "Origin 不在允许列表内: " + origin);
        }
        String host = originHost(origin);
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new ServerTransportSecurityException(403,
                    "Origin " + origin + " 不在允许列表内（可配置 mcp.server.allowedOrigins）");
        }
    }

    /** origin（http://host:port）→ host 小写 */
    private static String originHost(String origin) {
        String s = origin.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.lastIndexOf(':');
        if (colon > 0 && !s.startsWith("[")) s = s.substring(0, colon);
        return s.toLowerCase(Locale.ROOT);
    }

    // ==================== 平台级内部 ====================

    /** 请求头里的凭据：Authorization: Bearer &lt;key&gt; 优先，其次 X-Api-Key（与内部 API Key 口径一致） */
    private static String credential(ServerRequest req) {
        String auth = req.headers().firstHeader("Authorization");
        if (auth != null && auth.length() > 7 && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String x = req.headers().firstHeader("X-Api-Key");
        return x == null ? "" : x.trim();
    }

    private static ApiKey apiKeyOf(McpTransportContext ctx) {
        Object v = ctx.get(CTX_API_KEY);
        return v instanceof ApiKey k ? k : null;
    }

    private static Map<String, Object> argsOf(McpSchema.CallToolRequest call) {
        return call.arguments() == null ? Map.of() : call.arguments();
    }

    private static Integer toInt(Object v) {
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 以 Key 所属用户的身份执行：装载 RequestUser（检索可见性、智能体可读性都读它）
     * 并在结束时清理——MCP 请求跑在池化线程上，不清会污染下一次请求。
     */
    private McpSchema.CallToolResult withIdentity(ApiKey key, java.util.function.Supplier<McpSchema.CallToolResult> body) {
        if (key == null || key.getCreatedBy() == null || key.getCreatedBy().isBlank()) {
            return toolError("凭据缺失或 Key 无归属用户");
        }
        User u = userMapper.selectById(key.getCreatedBy());
        if (u == null) return toolError("Key 所属用户不存在");
        RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
        try {
            return body.get();
        } finally {
            RequestUser.clear();
        }
    }

    /** 当前身份可见的知识库 id（与知识库页列表同口径） */
    private java.util.Set<String> visibleKbIds() {
        ResourceVisibilityService.Principal p = new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (KnowledgeBase kb : knowledgeBaseService.list()) {
            if (visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(),
                                ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                ids.add(kb.getId());
            }
        }
        return ids;
    }

    /** 当前身份可用的智能体 id（与对话页下拉同口径，子智能体不在其中） */
    private java.util.Set<String> visibleAgentIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Map<String, Object> m : agentService.available()) {
            Object id = m.get("id");
            if (id != null) ids.add(String.valueOf(id));
        }
        return ids;
    }

    private static McpSchema.Tool tool(String name, String title, String description,
                                       Map<String, Object> props, List<String> required) {
        return McpSchema.Tool.builder()
                .name(name).title(title).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", props, required, false, null, null))
                .build();
    }

    /** 按三元组（name, type, description）拼 JsonSchema 的 properties */
    private static Map<String, Object> props(String... triples) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            m.put(triples[i], Map.of("type", triples[i + 1], "description", triples[i + 2]));
        }
        return m;
    }

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    /** 客户端 IP：X-Forwarded-For 首段优先（与 ChatController 同口径） */
    private static String clientIp(ServerRequest req) {
        String xff = req.headers().firstHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return "";
        return xff.split(",")[0].trim();
    }

    private static String agentName(Agent agent) {
        return agent.getName() == null || agent.getName().isBlank() ? "未命名智能体" : agent.getName();
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String tokenPrefix(String token) {
        return token == null || token.length() < 6 ? "?" : token.substring(0, 6);
    }

    /** token → 稳定短摘要（派生访客 uid，不泄露 token 本身） */
    private static String digest(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static McpSchema.CallToolResult toolError(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(Boolean.TRUE).build();
    }

    /** 纯 JSON 错误响应（未建立 MCP 会话时的失败，如 token 无效） */
    private static ServerResponse jsonError(HttpStatus status, String message) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(Map.of("error", message)));
    }
}
