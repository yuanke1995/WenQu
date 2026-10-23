package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.UserMcpMapper;
import com.wenqu.ai.model.UserMcp;
import com.wenqu.ai.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 工具中文名的自动生成与解析（「工具生态层」的展示名来源）。
 *
 * <p><b>为什么要有它</b>：问渠自己的工具中文名登记在 {@code ToolRegistry}，框架原生工具在
 * {@code FrameworkToolLabels}——MCP 动态工具两边都管不着，名字由用户自己登记的 server 决定，
 * 过去只能靠前端那份手抄映射（context7/deepwiki 那十几条就是代价：来一个新 server 就得手补一批）。
 * 本服务把这件事变成自动的：连接成功拿到工具清单后，按「工具集指纹」判断要不要重新翻译，
 * 需要就把该 server 全部工具的 name + description <b>一次请求</b>交给 LLM 译成中文
 * （口径与注册中心一致：书面动宾短语写「它在干什么」），落进 {@code c_ai_user_mcp.tool_labels}。
 *
 * <p><b>翻译用的模型 = 用户自己的默认聊天模型</b>（{@code ChatService#resolveChatModel} 的生效模型）：
 * 不另设配置键——MCP 是个人资产，翻译质量本就该跟着这个人选的模型走，且翻译只在「连接成功且工具集变化」
 * 时一次请求，开销可忽略。用户尚未配置默认模型（ref 为空）时退规则词典，不调 LLM。
 *
 * <p><b>翻译失败不落库</b>：LLM 不可用/超时时只写日志，展示层退到动词词典规则翻译
 * （{@link #ruleLabel}，零成本但质量一般）。不把规则结果写进缓存是有意的——下次连接会再试 LLM，
 * 规则结果一旦落库就永远没有转正的机会了。
 *
 * <p><b>调用纪律</b>：{@link #refreshAsync} 只在后台线程跑（LLM 往返是秒级 IO），
 * 绝不进连接/问答链路；{@link #lookup} 带按 uid 的短 TTL 内存缓存（工具卡每步都查，
 * 不能每步打一次 DB）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class McpToolLabelService {

    /** 词典条目数上限之外不值得一次请求，超出按规则兜底即可（单 server 工具数通常 < 50） */
    private static final int MAX_TRANSLATE_TOOLS = 80;

    private final UserMcpMapper userMcpMapper;
    private final ChatService chatService;
    private final ChatClient chatClient;

    /** 按 uid 聚合的中文名缓存（跨该用户全部 server 合并）：uid → (裸工具名 → 条目) */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = Duration.ofSeconds(15).toMillis();

    /** 在途翻译去重：同一 (uid, server) 连接成功与重试同时触发时只跑一次 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 翻译用的后台线程池：单线程串行足够——翻译是低频后台行为（一个 server 一次连接最多一次），
     * 串行天然温和，不打爆上游模型网关。
     */
    private final ExecutorService translateExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-label-translate");
        t.setDaemon(true);
        return t;
    });

    /** 一条工具的中文名与悬停说明（都可空：空 = 展示层回落下一档） */
    public record LabelHint(String label, String hint) {
    }

    private record Cached(Map<String, LabelHint> labels, long loadedAt) {
    }

    public McpToolLabelService(UserMcpMapper userMcpMapper, ChatService chatService, ChatClient chatClient) {
        this.userMcpMapper = userMcpMapper;
        this.chatService = chatService;
        this.chatClient = chatClient;
    }

    // ==================== 解析（工具卡/审批卡/管理页清单共用） ====================

    /**
     * 查某用户某工具的中文名（label 优先，返回 null = 展示层回落下一档）。
     *
     * @param uid      归属用户（MCP 是个人资产，翻译结果按归属隔离）
     * @param toolName 工具卡上记录的名字——通常是带 clientInfo 前缀的挂载名
     *                 （Spring AI {@code prefixedToolName} 无分隔符直拼：{@code wen_qusql_query}），
     *                 也可以就是裸名；先按原样查、再剥前缀查
     */
    public String label(String uid, String toolName) {
        LabelHint h = lookup(uid, toolName);
        return h == null ? null : h.label();
    }

    /** 悬停说明；查不到返回 null（调用方据此不写 hint 字段） */
    public String hint(String uid, String toolName) {
        LabelHint h = lookup(uid, toolName);
        return h == null ? null : h.hint();
    }

    private LabelHint lookup(String uid, String toolName) {
        if (uid == null || uid.isBlank() || toolName == null || toolName.isBlank()) return null;
        Cached c = cache.get(uid);
        if (c == null || System.currentTimeMillis() - c.loadedAt() > CACHE_TTL_MS) {
            c = new Cached(loadAll(uid), System.currentTimeMillis());
            cache.put(uid, c);
        }
        LabelHint hit = c.labels().get(toolName);
        if (hit != null) return hit;
        String bare = stripPrefix(toolName);
        if (bare != null) hit = c.labels().get(bare);
        if (hit != null) return hit;
        // 库里没有（LLM 未配置/未翻译成功）：规则词典兜底，宁浅勿编
        return ruleLabel(bare != null ? bare : toolName);
    }

    /** 读该用户全部 server 的中文名并合并（缓存过期时才打 DB） */
    private Map<String, LabelHint> loadAll(String uid) {
        Map<String, LabelHint> out = new LinkedHashMap<>();
        try {
            List<UserMcp> rows = userMcpMapper.selectList(new LambdaQueryWrapper<UserMcp>()
                    .eq(UserMcp::getUid, uid));
            for (UserMcp row : rows) {
                Map<String, LabelHint> items = parseItems(row.getToolLabels());
                items.forEach(out::putIfAbsent);
            }
        } catch (Exception e) {
            log.warn("[MCP-LABEL] uid={} 读取工具中文名缓存失败（按空处理）: {}", uid, e.getMessage());
        }
        return out;
    }

    /** 解析 tool_labels 列的 items 段；坏 JSON 按空处理（缓存列，不值得为它抛） */
    private static Map<String, LabelHint> parseItems(String json) {
        Map<String, LabelHint> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> root = JSON.parseObject(json);
            Object items = root.get("items");
            if (!(items instanceof Map<?, ?> map)) return out;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> v)) continue;
                Object label = v.get("label"), hint = v.get("hint");
                String bare = String.valueOf(e.getKey());
                if (bare.isBlank()) continue;
                out.put(bare, new LabelHint(
                        label == null ? null : String.valueOf(label),
                        hint == null || String.valueOf(hint).isBlank() ? null : String.valueOf(hint)));
            }
        } catch (Exception ignore) {
            // 坏数据当不存在
        }
        return out;
    }

    // ==================== 生成（连接成功后触发） ====================

    /**
     * 连接成功后异步刷新某 server 的工具中文名：指纹比对，工具集没变就不重译。
     * 永不抛出、绝不阻塞调用方——翻译失败只影响展示名档次，不影响连接与问答。
     *
     * @param uid    归属用户
     * @param server server 显示名（c_ai_user_mcp.name，行按 uid+name 定位）
     * @param tools  该 server 刚拉到的工具清单（name + description）
     */
    public void refreshAsync(String uid, String server, List<McpSchemaTool> tools) {
        if (uid == null || uid.isBlank() || server == null || server.isBlank()
                || tools == null || tools.isEmpty() || tools.size() > MAX_TRANSLATE_TOOLS) return;
        String key = uid + "/" + server;
        if (!inFlight.add(key)) return; // 连接与重试同时触发只跑一次
        translateExecutor.submit(() -> {
            try {
                refresh(uid, server, tools);
            } catch (Exception e) {
                log.info("[MCP-LABEL] uid={} server {} 工具中文名刷新失败（展示退规则词典）: {}",
                        uid, server, e.getMessage());
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private void refresh(String uid, String server, List<McpSchemaTool> tools) {
        String fp = fingerprint(tools);
        UserMcp row = userMcpMapper.selectOne(new LambdaQueryWrapper<UserMcp>()
                .eq(UserMcp::getUid, uid).eq(UserMcp::getName, server).last("LIMIT 1"));
        if (row == null) return; // 配置刚被删了，没处可写
        if (sameFingerprint(row.getToolLabels(), fp)) return; // 工具集没变，缓存放着用

        Map<String, LabelHint> translated = translateByLlm(uid, tools);
        if (translated.isEmpty()) return; // 翻译失败不落库：下次连接再试 LLM，展示层先走规则词典

        String json = JSON.toJSONString(Map.of("fp", fp, "items", translated));
        UserMcp patch = new UserMcp();
        patch.setId(row.getId());
        patch.setToolLabels(json);
        userMcpMapper.updateById(patch);
        cache.remove(uid); // 令按 uid 的聚合缓存失效
        log.info("[MCP-LABEL] uid={} server {} 工具中文名已翻译落库（{} 个工具）", uid, server, translated.size());
    }

    /** LLM 批量翻译：一次请求译整份清单；任何异常都按「没翻出来」处理（返回空 map） */
    private Map<String, LabelHint> translateByLlm(String uid, List<McpSchemaTool> tools) {
        // 翻译用用户自己的默认聊天模型：不另设键、零配置，质量跟着该人选的模型走
        String model = chatService.resolveChatModel(uid, null);
        if (model == null || model.isBlank()) return Map.of(); // 用户尚未配置默认模型：退规则词典，不调 LLM
        StringBuilder input = new StringBuilder();
        for (McpSchemaTool t : tools) {
            input.append("{\"name\":\"").append(esc(t.name())).append("\",\"description\":\"")
                    .append(esc(t.description() == null ? "" : t.description())).append("\"}\n");
        }
        String system = """
                你是工具命名翻译员。输入是 MCP 工具清单（每行一个 JSON：name 与 description）。
                给每个工具产出中文展示名 label 与悬停说明 hint：
                - label：书面动宾短语，2~12 字，写「它在干什么」（如 sql_query → 执行 SQL 查询），不要机制名与口语；
                - hint：一句话（≤30 字）说明这个工具做什么、结果给谁；
                - description 已是中文时可提炼改写，不必逐字翻译。
                只输出一个 JSON 数组，不要任何解释：
                [{"name":"原样照抄输入的 name","label":"…","hint":"…"}]""";
        String content = chatClient.prompt()
                .system(system)
                .user(input.toString())
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.2)
                        .maxTokens(2048)
                        .build())
                .call()
                .content();
        Map<String, LabelHint> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) return out;
        // 模型偶尔包一层 ```json 围栏：只取第一个 [ 到最后一个 ] 之间
        int l = content.indexOf('['), r = content.lastIndexOf(']');
        if (l < 0 || r <= l) return out;
        com.alibaba.fastjson2.JSONArray arr = JSON.parseArray(content.substring(l, r + 1));
        for (Object o : arr) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> item = (Map<?, ?>) o;
            Object name = item.get("name"), label = item.get("label");
            if (name == null || label == null) continue;
            String n = String.valueOf(name).trim();
            String lb = String.valueOf(label).trim();
            if (n.isEmpty() || lb.isEmpty()) continue;
            Object hint = item.get("hint");
            out.put(n, new LabelHint(lb, hint == null || String.valueOf(hint).isBlank()
                    ? null : String.valueOf(hint).trim()));
        }
        return out;
    }

    // ==================== 规则词典兜底（零成本，宁浅勿编） ====================

    /** 动词词典：snake_case 首段（或任意段）命中就按它定动作 */
    private static final Map<String, String> VERBS = Map.ofEntries(
            Map.entry("query", "查询"), Map.entry("select", "查询"), Map.entry("fetch", "获取"),
            Map.entry("get", "获取"), Map.entry("read", "读取"), Map.entry("search", "检索"),
            Map.entry("find", "查找"), Map.entry("list", "列出"), Map.entry("ls", "列出"),
            Map.entry("write", "写入"), Map.entry("create", "创建"), Map.entry("add", "添加"),
            Map.entry("insert", "插入"), Map.entry("update", "更新"), Map.entry("edit", "编辑"),
            Map.entry("modify", "修改"), Map.entry("set", "设置"), Map.entry("delete", "删除"),
            Map.entry("remove", "删除"), Map.entry("drop", "删除"), Map.entry("execute", "执行"),
            Map.entry("run", "执行"), Map.entry("call", "调用"), Map.entry("invoke", "调用"),
            Map.entry("send", "发送"), Map.entry("post", "发送"), Map.entry("ask", "提问"),
            Map.entry("convert", "转换"), Map.entry("transform", "转换"), Map.entry("generate", "生成"),
            Map.entry("translate", "翻译"), Map.entry("summarize", "总结"), Map.entry("summary", "总结"),
            Map.entry("upload", "上传"), Map.entry("download", "下载"), Map.entry("resolve", "解析"),
            Map.entry("parse", "解析"), Map.entry("submit", "提交"), Map.entry("approve", "批准"),
            Map.entry("cancel", "取消"), Map.entry("stop", "停止"), Map.entry("start", "启动"),
            Map.entry("restart", "重启"), Map.entry("close", "关闭"), Map.entry("open", "打开"),
            Map.entry("save", "保存"), Map.entry("load", "加载"), Map.entry("move", "移动"),
            Map.entry("copy", "复制"), Map.entry("rename", "重命名"), Map.entry("check", "检查"),
            Map.entry("verify", "校验"), Map.entry("validate", "校验"), Map.entry("test", "测试"),
            Map.entry("count", "统计"), Map.entry("calc", "计算"), Map.entry("calculate", "计算"));

    /** 名词词典：能认出的译中文，认不出的保留原文 */
    private static final Map<String, String> NOUNS = Map.ofEntries(
            Map.entry("sql", "SQL"), Map.entry("file", "文件"), Map.entry("files", "文件"),
            Map.entry("folder", "目录"), Map.entry("directory", "目录"), Map.entry("dir", "目录"),
            Map.entry("doc", "文档"), Map.entry("docs", "文档"), Map.entry("documentation", "文档"),
            Map.entry("document", "文档"), Map.entry("image", "图片"), Map.entry("images", "图片"),
            Map.entry("video", "视频"), Map.entry("audio", "音频"), Map.entry("weather", "天气"),
            Map.entry("mail", "邮件"), Map.entry("email", "邮件"), Map.entry("calendar", "日历"),
            Map.entry("event", "事件"), Map.entry("events", "事件"), Map.entry("task", "任务"),
            Map.entry("tasks", "任务"), Map.entry("todo", "待办"), Map.entry("todos", "待办"),
            Map.entry("user", "用户"), Map.entry("users", "用户"), Map.entry("data", "数据"),
            Map.entry("table", "表格"), Map.entry("tables", "表格"), Map.entry("row", "行"),
            Map.entry("rows", "行"), Map.entry("page", "页面"), Map.entry("pages", "页面"),
            Map.entry("web", "网页"), Map.entry("url", "链接"), Map.entry("link", "链接"),
            Map.entry("links", "链接"), Map.entry("wiki", "Wiki"), Map.entry("repo", "仓库"),
            Map.entry("repositories", "仓库"), Map.entry("repository", "仓库"), Map.entry("code", "代码"),
            Map.entry("issue", "议题"), Map.entry("issues", "议题"), Map.entry("commit", "提交"),
            Map.entry("commits", "提交"), Map.entry("branch", "分支"), Map.entry("branches", "分支"),
            Map.entry("knowledge", "知识"), Map.entry("memory", "记忆"), Map.entry("skill", "技能"),
            Map.entry("skills", "技能"), Map.entry("session", "会话"), Map.entry("sessions", "会话"),
            Map.entry("message", "消息"), Map.entry("messages", "消息"), Map.entry("contact", "联系人"),
            Map.entry("contacts", "联系人"), Map.entry("ticket", "工单"), Map.entry("tickets", "工单"),
            Map.entry("order", "订单"), Map.entry("orders", "订单"), Map.entry("product", "商品"),
            Map.entry("products", "商品"), Map.entry("customer", "客户"), Map.entry("customers", "客户"),
            Map.entry("id", "ID"), Map.entry("ids", "ID"), Map.entry("db", "数据库"),
            Map.entry("database", "数据库"), Map.entry("api", "API"), Map.entry("token", "令牌"),
            Map.entry("secret", "密钥"), Map.entry("config", "配置"), Map.entry("configuration", "配置"),
            Map.entry("log", "日志"), Map.entry("logs", "日志"), Map.entry("metric", "指标"),
            Map.entry("metrics", "指标"), Map.entry("report", "报告"), Map.entry("reports", "报告"),
            Map.entry("article", "文章"), Map.entry("articles", "文章"), Map.entry("note", "笔记"),
            Map.entry("notes", "笔记"), Map.entry("library", "文档库"), Map.entry("libraries", "文档库"),
            Map.entry("content", "内容"), Map.entry("contents", "内容"), Map.entry("structure", "结构"),
            Map.entry("question", "问题"), Map.entry("questions", "问题"), Map.entry("answer", "回答"),
            Map.entry("price", "价格"), Map.entry("prices", "价格"), Map.entry("status", "状态"),
            Map.entry("info", "信息"), Map.entry("information", "信息"), Map.entry("detail", "详情"),
            Map.entry("details", "详情"));

    /**
     * 规则翻译：动词定动作、名词拼对象（{@code sql_query → 查询 SQL}）。
     * 一个词都认不出时返回 null——宁缺毋滥，不把瞎拼的当中文下发。
     */
    static LabelHint ruleLabel(String bareName) {
        if (bareName == null || bareName.isBlank()) return null;
        String[] parts = bareName.toLowerCase().split("[_\\-.]+");
        String verb = null;
        List<String> nouns = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (verb == null && VERBS.containsKey(p)) {
                verb = VERBS.get(p);
                continue;
            }
            nouns.add(NOUNS.getOrDefault(p, p));
        }
        if (verb == null || nouns.isEmpty()) return null;
        String label = verb + " " + String.join(" ", nouns);
        if (label.length() > 18) label = label.substring(0, 18);
        return new LabelHint(label, null);
    }

    // ==================== 小件 ====================

    /**
     * 剥 clientInfo 前缀，还原 server 侧的裸工具名。
     * <p>前缀规则已反编译 spring-ai-mcp 1.1.8 核实：{@code prefixedToolName} 把
     * {@code clientInfo.name}（"wen-qu"）格式化成 {@code wen_qu} 后<b>无分隔符直拼</b>裸名
     * （{@code wen_qusql_query}）；历史口径 {@code w_q_} 带下划线，保留兼容。与前端
     * {@code projections.js#bareToolName} 同一条规则——两边各写一遍是因为载荷里永远带着原名，
     * 剥法漂移的后果只是查不到缓存而退规则词典，可观测。
     */
    static String stripPrefix(String name) {
        if (name.startsWith("wen_qu") && name.length() > 6) return name.substring(6);
        if (name.startsWith("w_q_") && name.length() > 4) return name.substring(4);
        return null;
    }

    /** 工具集指纹：name|description 逐条拼接后 SHA-256，工具集任何变化都会翻脸 */
    private static String fingerprint(List<McpSchemaTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (McpSchemaTool t : tools) {
            sb.append(t.name()).append('|').append(t.description() == null ? "" : t.description()).append('\n');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(sb.toString().hashCode()); // 摘要算法不可用时退 hashcode（强度足够做缓存键）
        }
    }

    private static boolean sameFingerprint(String storedJson, String fp) {
        if (storedJson == null || storedJson.isBlank()) return false;
        try {
            return fp.equals(String.valueOf(JSON.parseObject(storedJson).get("fp")));
        } catch (Exception e) {
            return false;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /**
     * MCP 工具的最小投影（name + description）：刻意不引 {@code McpSchema.Tool} 进本服务的公开签名，
     * 调用方（{@code McpClientService}）从 SDK 类型现摘这两样传进来，本服务对 MCP SDK 保持无依赖。
     */
    public record McpSchemaTool(String name, String description) {
    }
}
