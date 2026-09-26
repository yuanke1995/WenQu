package com.wisesoft.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.UserMcpMapper;
import com.wisesoft.ai.model.UserMcp;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.McpClientService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @Operation(summary = "连接状态一览", description = "工具调用总开关 + 本人每个 server 的地址/类型/启停/连接状态/可用工具数（连接失败带原因）")
    @GetMapping("/status")
    public ResultJson status() {
        String uid = RequestUser.uid();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolsEnabled", configService.getBoolean("tool.enabled"));
        data.put("servers", mcpClientService.serverStatuses(uid));
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
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
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
    public ResultJson setEnabled(@PathVariable String id, @RequestBody Map<String, Object> body) {
        UserMcp row = own(id);
        row.setEnabled(Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0);
        userMcpMapper.updateById(row);
        mcpClientService.reload(row.getUid());
        return ResultJson.ok(Map.of("id", row.getId(), "enabled", Integer.valueOf(1).equals(row.getEnabled())));
    }

    @Operation(summary = "删除 MCP 服务", description = "连带关闭该连接（只删自己的）")
    @DeleteMapping("/servers/{id}")
    public ResultJson delete(@PathVariable String id) {
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
        return status();
    }

    @Operation(summary = "测试连接", description = "临时连接一个 MCP 服务（不落配置），返回是否可达与工具清单；用于\"先测再存\"")
    @PostMapping("/probe")
    public ResultJson probe(@RequestBody Map<String, String> body) {
        return ResultJson.ok(mcpClientService.probe(body.get("url"), body.get("type")));
    }

    // ==================== 内部 ====================

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
