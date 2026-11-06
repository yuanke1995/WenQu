package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.UserMcpMapper;
import com.wenqu.ai.model.UserMcp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具中文名的读写与解析（「工具生态层」的展示名来源）。
 *
 * <p><b>为什么改成手工维护</b>：问渠自己的工具中文名登记在 {@code ToolRegistry}，框架原生工具在
 * {@code FrameworkToolLabels}——MCP 动态工具两边都管不着，名字由第三方 server 决定。
 * 曾试过连接成功后让 LLM 批量译一遍，但同一个工具每次给的叫法都不一样（「列出文档目录」/「列出文档主题」、
 * 「查看仓库文档」/「查看文档内容」），质量不可控，遂改回手工：用户在「智能体 → MCP 外部工具」
 * 的工具清单里逐条起中文名，没起名的工具就展示原名。
 *
 * <p><b>存储格式</b>：{@code c_ai_user_mcp.tool_labels} ＝
 * {@code {"items":{"裸工具名":"中文名"}}}。历史版本写入过
 * {@code {"fp":…,"items":{名:{label,hint}}}}（LLM 自动翻译），解析时<b>只认字符串值</b>，
 * 旧格式条目一律忽略——它们不是用户确认过的名字。
 *
 * <p><b>调用纪律</b>：{@link #lookup} 带按 uid 的短 TTL 内存缓存（工具卡每步都查，不能每步打一次 DB），
 * {@link #save} 写完立刻让该用户的缓存失效，本实例上即时可见。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class McpToolLabelService {

    /** 手工名字长度上限：够写「向仓库提问」这类动宾短语，又不至于撑破工具卡 */
    private static final int MAX_LABEL_CHARS = 16;

    private final UserMcpMapper userMcpMapper;

    /** 按 uid 聚合的中文名（跨该用户全部 server 合并）：uid → (裸工具名 → 中文名) */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = Duration.ofSeconds(15).toMillis();

    private record Cached(Map<String, String> labels, long loadedAt) {
    }

    public McpToolLabelService(UserMcpMapper userMcpMapper) {
        this.userMcpMapper = userMcpMapper;
    }

    // ==================== 解析（工具卡/审批卡/管理页清单共用） ====================

    /**
     * 查某用户某工具的手工中文名（返回 null = 展示原名）。
     *
     * @param uid      归属用户（MCP 是个人资产，名字按归属隔离）
     * @param toolName 工具卡上记录的名字——通常是带 clientInfo 前缀的挂载名
     *                 （Spring AI {@code prefixedToolName} 无分隔符直拼：{@code wen_qusql_query}），
     *                 也可以就是裸名；先按原样查、再剥前缀查
     */
    public String label(String uid, String toolName) {
        if (uid == null || uid.isBlank() || toolName == null || toolName.isBlank()) return null;
        Cached c = cache.get(uid);
        if (c == null || System.currentTimeMillis() - c.loadedAt() > CACHE_TTL_MS) {
            c = new Cached(loadAll(uid), System.currentTimeMillis());
            cache.put(uid, c);
        }
        String hit = c.labels().get(toolName);
        if (hit != null) return hit;
        String bare = stripPrefix(toolName);
        return bare == null ? null : c.labels().get(bare);
    }

    /** 读该用户全部 server 的中文名并合并（缓存过期时才打 DB） */
    private Map<String, String> loadAll(String uid) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            List<UserMcp> rows = userMcpMapper.selectList(new LambdaQueryWrapper<UserMcp>()
                    .eq(UserMcp::getUid, uid));
            // 个人行先入：putIfAbsent 语义下即优先，用户给自己服务起的名字盖过平台行的预置名
            for (UserMcp row : rows) {
                parseItems(row.getToolLabels()).forEach(out::putIfAbsent);
            }
            // 平台行的中文名对全员可读：浏览器这类平台能力的名字由平台预置，
            // 不要求每个人再去面板逐条起一遍（否则工具卡上全是一串英文裸名）
            for (UserMcp row : userMcpMapper.selectList(new LambdaQueryWrapper<UserMcp>()
                    .eq(UserMcp::getScope, UserMcp.SCOPE_PLATFORM))) {
                parseItems(row.getToolLabels()).forEach(out::putIfAbsent);
            }
        } catch (Exception e) {
            log.warn("[MCP-LABEL] uid={} 读取工具中文名失败（按空处理）: {}", uid, e.getMessage());
        }
        return out;
    }

    /** 解析 items 段；只认字符串值，坏 JSON / 旧格式（对象值）按空处理 */
    static Map<String, String> parseItems(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> root = JSON.parseObject(json);
            Object items = root.get("items");
            if (!(items instanceof Map<?, ?> map)) return out;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof String v) || v.isBlank()) continue;
                out.put(String.valueOf(e.getKey()), v);
            }
        } catch (Exception ignore) {
            // 坏数据当不存在
        }
        return out;
    }

    // ==================== 写入（管理页逐条改名） ====================

    /**
     * 设置或清除某个工具的中文名。{@code label} 为空 = 恢复展示原名。
     * 只动这一行（调用方已按归属校验：个人行归本人，平台行归管理员），写完让读到它的缓存失效。
     *
     * @return 落库后的中文名（清除时为 null）
     */
    public String save(UserMcp row, String toolName, String label) {
        if (row == null) throw new BizException("MCP 服务不存在");
        String tool = toolName == null ? "" : toolName.strip();
        if (tool.isEmpty() || tool.length() > 128) throw new BizException("工具名不合法");
        String value = label == null ? "" : label.strip().replaceAll("\\s+", " ");
        if (value.length() > MAX_LABEL_CHARS) {
            throw new BizException("中文名最多 " + MAX_LABEL_CHARS + " 个字");
        }
        String bare = stripPrefix(tool) == null ? tool : stripPrefix(tool);
        Map<String, String> items = parseItems(row.getToolLabels());
        if (value.isEmpty()) items.remove(bare);
        else items.put(bare, value);

        UserMcp patch = new UserMcp();
        patch.setId(row.getId());
        patch.setToolLabels(JSON.toJSONString(Map.of("items", items)));
        userMcpMapper.updateById(patch);
        if (UserMcp.SCOPE_PLATFORM.equals(row.getScope())) {
            // 平台行的读者是全员：按 uid 清只清得掉哨兵那一份，其余人还会拿 15s 旧名。整体清空。
            cache.clear();
        } else {
            cache.remove(row.getUid());
        }
        return value.isEmpty() ? null : value;
    }

    // ==================== 小件 ====================

    /**
     * 剥 clientInfo 前缀，还原 server 侧的裸工具名。
     * <p>前缀规则已反编译 spring-ai-mcp 1.1.8 核实：{@code prefixedToolName} 把
     * {@code clientInfo.name}（"wen-qu"）格式化成 {@code wen_qu} 后<b>无分隔符直拼</b>裸名
     * （{@code wen_qusql_query}）。与前端 {@code projections.js#bareToolName} 同一条规则。
     */
    static String stripPrefix(String name) {
        if (name.startsWith("wen_qu") && name.length() > 6) return name.substring(6);
        return null;
    }
}
