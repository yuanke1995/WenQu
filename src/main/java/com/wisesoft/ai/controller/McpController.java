package com.wisesoft.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AdminGuard;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.McpCallLogMapper;
import com.wisesoft.ai.mapper.UserMcpMapper;
import com.wisesoft.ai.model.McpCallLog;
import com.wisesoft.ai.model.UserMcp;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.McpClientService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 外部工具管理接口：**每个用户管自己登记的 Server**（新增/编辑/启停/删除 + 连接状态/重连/探测）。
 *
 * <p>原先 server 列表是管理员在系统设置里维护的全局 JSON（{@code mcp.servers}）+ 全局总开关，
 * 既碰不到也属于别人的答链路；现在落到 {@code c_ai_user_mcp} 按 uid 隔离，
 * 连接池也按用户分池（{@link McpClientService}），取工具时只取本人名下的服务。
 * 归属一律取 {@link RequestUser#uid()}（来自登录令牌），不接受客户端自报的用户标识。
 *
 * <p>唯一保留的全局项是 {@code tool.enabled}（工具调用总开关）——它决定平台是否允许模型调用工具，
 * 与个人如何配置无关；在 {@code /status} 里回传，供界面提示。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 外部工具", description = "个人 MCP Server：增删改 / 连接状态 / 重连 / 测试")
public class McpController {

    /** 每个用户最多登记的 server 数（与连接池上限一致，防止一次接入几十个外部服务拖垮问答） */
    private static final int MAX_SERVERS = 10;

    private final McpClientService mcpClientService;
    private final ConfigService configService;
    private final UserMcpMapper userMcpMapper;
    private final McpCallLogMapper mcpCallLogMapper;
    private final AdminGuard adminGuard;

    @Operation(summary = "连接状态一览", description = "工具调用总开关 + 本人每个 server 的地址/类型/启停/连接状态/可用工具数（连接失败带原因）；retryBroken=true 时先对未连上的服务补一次重连（点「刷新」用），进页面拉状态不要传，避免死服务拖慢首屏")
    @GetMapping("/status")
    public ResultJson status(@RequestParam(value = "retryBroken", required = false) Boolean retryBroken,
                             // verifyOnline=true 才对已连接服务做在线校验（远程 listTools，慢）；只拿服务清单的
                             // 消费方（智能体页等）不传，默认只读最近已知状态——否则页面加载被远程 MCP 握手拖住
                             @RequestParam(value = "verifyOnline", required = false) Boolean verifyOnline) {
        String uid = RequestUser.uid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolsEnabled", configService.getBoolean("tool.enabled"));
        if (Boolean.TRUE.equals(retryBroken)) mcpClientService.retryBroken(uid);
        data.put("servers", mcpClientService.serverStatuses(uid, Boolean.TRUE.equals(verifyOnline)));
        return ResultJson.ok(data);
    }

    @Operation(summary = "新增 MCP 服务", description = "body: {name, url, type?, enabled?}；保存即尝试连接，失败不阻断（状态可在 /status 查看）")
    @PostMapping("/servers")
    public ResultJson add(@RequestBody Map<String, Object> body) {
        String uid = RequestUser.uid();
        long count = userMcpMapper.selectCount(new LambdaQueryWrapper<UserMcp>().eq(UserMcp::getUid, uid));
        if (count >= MAX_SERVERS) {
            throw new BizException("最多接入 " + MAX_SERVERS + " 个 MCP 服务");
        }
        String name = trim(text(body, "name"));
        String url = trim(text(body, "url"));
        String type = normalizeType(text(body, "type"));
        validate(name, url);
        if (userMcpMapper.selectCount(new LambdaQueryWrapper<UserMcp>()
                .eq(UserMcp::getUid, uid).eq(UserMcp::getName, name)) > 0) {
            throw new BizException("已存在同名 MCP 服务（" + name + "）");
        }
        UserMcp row = new UserMcp();
        row.setId(UUID.randomUUID().toString());
        row.setUid(uid);
        row.setName(name);
        row.setUrl(url);
        row.setType(type);
        row.setEnabled(body.get("enabled") == null ? 1 : (Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0));
        userMcpMapper.insert(row);
        mcpClientService.reload(uid);
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "编辑 MCP 服务", description = "body: {name?, url?, type?, enabled?}；只传要改的字段，改完自动重连")
    @PutMapping("/servers/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        UserMcp row = own(id);
        if (body.containsKey("name")) {
            String name = trim(text(body, "name"));
            if (!name.equals(row.getName())) {
                boolean dup = userMcpMapper.selectCount(new LambdaQueryWrapper<UserMcp>()
                        .eq(UserMcp::getUid, row.getUid()).eq(UserMcp::getName, name)) > 0;
                if (dup) throw new BizException("已存在同名 MCP 服务（" + name + "）");
            }
            row.setName(name);
        }
        if (body.containsKey("url")) row.setUrl(trim(text(body, "url")));
        if (body.containsKey("type")) row.setType(normalizeType(text(body, "type")));
        if (body.containsKey("enabled")) row.setEnabled(Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0);
        validate(row.getName(), row.getUrl());
        userMcpMapper.updateById(row);
        mcpClientService.reload(row.getUid());
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "启停 MCP 服务", description = "body: {\"enabled\": true}；停用即断开连接且不再暴露其工具")
    @PostMapping("/servers/{id}/enabled")
    public ResultJson setEnabled(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        UserMcp row = own(id);
        row.setEnabled(Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0);
        userMcpMapper.updateById(row);
        mcpClientService.reload(row.getUid());
        return ResultJson.ok(Map.of("id", row.getId(), "enabled", Integer.valueOf(1).equals(row.getEnabled())));
    }

    @Operation(summary = "删除 MCP 服务", description = "连带关闭该连接（只删自己的）")
    @DeleteMapping("/servers/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        UserMcp row = own(id);
        userMcpMapper.deleteById(row.getId());
        mcpClientService.reload(row.getUid());
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "重连", description = "按当前配置重建本人的全部连接（改完通常已自动生效；此接口用于失败重试），返回最新状态")
    @PostMapping("/reload")
    public ResultJson reload() {
        String uid = RequestUser.uid();
        mcpClientService.reload(uid);
        return status(null, null); // 重连刚整池重建过，无需再补重连
    }

    @Operation(summary = "测试连接", description = "临时连接一个 MCP 服务（不落配置），返回是否可达与工具清单；用于\"先测再存\"")
    @PostMapping("/probe")
    public ResultJson probe(@RequestBody Map<String, String> body) {
        return ResultJson.ok(mcpClientService.probe(body.get("url"), body.get("type")));
    }

    // ==================== MCP 调用审计（仅管理员） ====================

    @Operation(summary = "MCP 调用审计-明细", description = "外部客户端调用 wenqu MCP 端点（智能体端点 /ai/mcp/{token} 与平台级 /ai/mcp）的每次工具执行记录：渠道/工具/凭据指代/归属/IP/耗时/结果。仅管理员；按时间倒序分页，支持渠道/工具/结果筛选")
    @GetMapping("/audit/logs")
    public ResultJson auditLogs(@RequestParam(value = "channel", required = false) String channel,
                                @RequestParam(value = "tool", required = false) String tool,
                                @RequestParam(value = "success", required = false) Integer success,
                                @RequestParam(value = "page", defaultValue = "1") Integer page,
                                @RequestParam(value = "size", defaultValue = "20") Integer size,
                                HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        int p = Math.max(1, page == null ? 1 : page);
        int n = Math.min(100, Math.max(1, size == null ? 20 : size));
        LambdaQueryWrapper<McpCallLog> qw = new LambdaQueryWrapper<McpCallLog>()
                .orderByDesc(McpCallLog::getCreatedAt);
        if (channel != null && !channel.isBlank()) qw.eq(McpCallLog::getChannel, channel.trim());
        if (tool != null && !tool.isBlank()) qw.like(McpCallLog::getToolName, tool.trim());
        if (success != null) qw.eq(McpCallLog::getSuccess, success);
        long total = mcpCallLogMapper.selectCount(qw);
        // count 之后才拼 LIMIT：同一 wrapper 先数总数再取页（项目无分页插件，手工 offset）
        qw.last("LIMIT " + (long) (p - 1) * n + ", " + n);
        List<McpCallLog> rows = mcpCallLogMapper.selectList(qw);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", p);
        data.put("size", n);
        data.put("rows", rows);
        return ResultJson.ok(data);
    }

    @Operation(summary = "MCP 调用审计-汇总", description = "总调用/成功/失败/平均耗时 + 按工具×渠道分组统计（调用量倒序前 20）。仅管理员")
    @GetMapping("/audit/summary")
    public ResultJson auditSummary(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", mcpCallLogMapper.selectCount(null));
        data.put("success", mcpCallLogMapper.selectCount(new LambdaQueryWrapper<McpCallLog>().eq(McpCallLog::getSuccess, 1)));
        data.put("failed", mcpCallLogMapper.selectCount(new LambdaQueryWrapper<McpCallLog>().eq(McpCallLog::getSuccess, 0)));
        // 按工具×渠道分组：同名工具在两类入口下语义不同（如 wenqu_ask 平台级/智能体端点）
        QueryWrapper<McpCallLog> gw = new QueryWrapper<McpCallLog>()
                .select("tool_name AS toolName", "channel", "COUNT(*) AS cnt",
                        "SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failCnt",
                        "ROUND(AVG(duration_ms)) AS avgMs")
                .groupBy("tool_name", "channel")
                .orderByDesc("cnt")
                .last("LIMIT 20");
        data.put("byTool", mcpCallLogMapper.selectMaps(gw));
        return ResultJson.ok(data);
    }

    // ==================== 内部 ====================

    /**
     * 审计是管理员视角（外部调用盘点）。注意 {@code /api/ai/mcp/**} 在 SecurityConfig 的
     * 登录放行名单里（个人 MCP 管理人人可用），且对带 API Key 的请求也放行——
     * 所以这里必须<b>方法内自查 admin</b>（fail-closed），不能依赖拦截器。
     */
    private void requireAdmin(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) {
            throw new BizException(403, "仅管理员可查看 MCP 调用审计");
        }
    }

    /** 取本人的一条记录：不存在或不是本人的都按不存在处理（避免按 ID 遍历到别人的服务） */
    private UserMcp own(String id) {
        UserMcp row = userMcpMapper.selectById(id);
        if (row == null || !RequestUser.uid().equals(row.getUid())) {
            throw new BizException("MCP 服务不存在");
        }
        return row;
    }

    private static void validate(String name, String url) {
        if (name == null || name.isBlank()) throw new BizException("请填写服务名称");
        if (name.length() > 40) throw new BizException("服务名称不超过 40 个字符");
        // 服务名会参与智能体引用串 {uid}/{name} 的拆分，含 '/' 会让引用解析错位
        if (name.contains("/")) throw new BizException("服务名称不能包含 /");
        if (url == null || url.isBlank()) throw new BizException("请填写服务地址");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException("服务地址需以 http:// 或 https:// 开头");
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) return "streamable";
        return "sse".equalsIgnoreCase(type.trim()) ? "sse" : "streamable";
    }

    private static String text(Map<String, ?> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
