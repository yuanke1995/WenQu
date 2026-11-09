package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ApiKeyService;
import com.wenqu.ai.util.RequestUser;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * API Key 管理接口（个人资产）：签发 / 列表 / 编辑 / 启停 / 删除 / 明文回显。
 *
 * <p><b>谁能用</b>：全部登录用户（端点在 SecurityConfig 问答白名单内）；能看见/管到哪些 Key 由
 * {@code ResourceVisibilityService} 判定——谁建归谁（只看得到自己签发的）。本控制器同时在
 * Key 拒绝名单内——Key 本身不能管理 Key，否则一把泄露的 Key 能给自己签发续命。
 *
 * <p><b>绑定规则（代表账号）</b>：Key 恒以签发人本人身份发问，签发即绑本人、不支持指定/改绑别人
 * （请求里带别人的 actAsUid 会被拒绝）。
 * Key 的权限固定为「问答链路」——持有 Key 的调用方走 X-Api-Key 头，仅能访问与普通用户等价的
 * 端点（问答/会话/反馈/引用溯源等），白名单之外对 Key 身份一律 403（SecurityConfig 1c 层）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/api-key")
@RequiredArgsConstructor
@Tag(name = "API Key 管理", description = "对外开放问答能力的密钥签发与吊销")
public class ApiKeyController {

    private final com.wenqu.ai.mapper.UsageLogMapper usageLogMapper;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiKeyController.class);

    private final ApiKeyService apiKeyService;

    @Operation(summary = "Key 列表", description = "不含明文与哈希，仅前缀与使用/过期状态；谁建归谁（只看得到自己签发的 Key）")
    @Permission("apikey:read")
    @GetMapping("/list")
    public ResultJson list() {
        java.util.List<?> rows = apiKeyService.list();
        // 近 30 天按 Key 用量小计（OpenAI 兼容端点的计量归属在此显形；
        // 聚合失败不挡列表——小计是增强信息，缺了只少一列）
        java.util.Map<String, java.util.Map<String, Object>> usage = new java.util.LinkedHashMap<>();
        try {
            for (java.util.Map<String, Object> r : usageLogMapper.statApiKeyUsage(
                    com.wenqu.ai.util.RequestUser.uid(),
                    java.time.LocalDateTime.now().minusDays(30))) {
                Object k = r.get("k");
                if (k != null) usage.put(String.valueOf(k), r);
            }
        } catch (Exception e) {
            log.warn("[API-KEY] 用量小计聚合失败（列表照常返回）: {}", e.getMessage());
        }
        com.alibaba.fastjson2.JSONArray out = new com.alibaba.fastjson2.JSONArray();
        for (Object row : rows) {
            com.alibaba.fastjson2.JSONObject o = (com.alibaba.fastjson2.JSONObject) com.alibaba.fastjson2.JSON.toJSON(row);
            Object id = o.get("id");
            java.util.Map<String, Object> u = id == null ? null : usage.get(String.valueOf(id));
            o.put("usage30dCalls", u == null ? 0 : ((Number) u.getOrDefault("c", 0)).intValue());
            o.put("usage30dTokens", u == null ? 0L : ((Number) u.getOrDefault("t", 0L)).longValue());
            out.add(o);
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "签发 Key", description = "返回明文 apiKey（此后可在列表点「眼睛」回显/复制——明文以部署密钥加密保管）。"
            + "body: {\"name\":\"…\",\"expireAt\":\"yyyy-MM-dd\"}——Key 恒以签发人本人身份发问。"
            + "改密/资料/技能/MCP 配置等账号自服务面一律不从 Key 走")
    @Permission("apikey:write")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        LocalDateTime expireAt = parseExpire(body.get("expireAt"));
        // 归属用「当前登录身份」（RequestUser）：与文档/智能体的 created_by 口径一致，
        // 否则创建者短路会因身份来源不同而失效
        return ResultJson.ok(apiKeyService.create(name, expireAt, RequestUser.uid()));
    }

    @Operation(summary = "回显明文", description = "返回该 Key 的明文 apiKey（列表「眼睛」/复制按钮的数据源）；"
            + "仅创建者可回显，每次都落审计日志")
    @Permission("apikey:read")
    @GetMapping("/{id}/reveal")
    public ResultJson reveal(@PathVariable("id") String id) {
        return ResultJson.ok(Map.of("apiKey", apiKeyService.reveal(id)));
    }

    @Operation(summary = "编辑 Key", description = "改用途名称与有效期（expireAt 缺省/空串=改为长期有效）。"
            + "绑定恒为签发人、不可改。body: {\"name\": \"…\", \"expireAt\": \"…\"}")
    @Permission("apikey:write")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) throw new BizException("名称不能为空");
        apiKeyService.updateInfo(id, name.trim(), parseExpire(body.get("expireAt")));
        return ResultJson.ok(Map.of("id", id, "name", name.trim()));
    }

    @Operation(summary = "启用/停用", description = "停用即吊销（保留记录便于审计）；body: {\"disabled\": true}")
    @Permission("apikey:write")
    @PutMapping("/{id}/disabled")
    public ResultJson setDisabled(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        apiKeyService.setDisabled(id, disabled);
        return ResultJson.ok(Map.of("id", id, "disabled", disabled));
    }

    @Operation(summary = "授权 MCP 入口", description = "body: {\"mcpEnabled\": true}——允许该 Key 访问平台级 MCP 入口 /ai/mcp"
            + "（元工具集：知识检索 / 问答 / 列知识库 / 列智能体）。默认不授权；停用该 Key 时入口一并不可用")
    @Permission("apikey:write")
    @PutMapping("/{id}/mcp")
    public ResultJson setMcpEnabled(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("mcpEnabled"));
        apiKeyService.setMcpEnabled(id, enabled);
        return ResultJson.ok(Map.of("id", id, "mcpEnabled", enabled));
    }

    @Operation(summary = "删除 Key", description = "物理删除记录（不再需要时清理；日常吊销建议用停用）")
    @Permission("apikey:delete")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        apiKeyService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    /** 过期时间：接受 yyyy-MM-dd（当天 23:59:59 失效）或 yyyy-MM-ddTHH:mm[:ss]；空=长期有效 */
    private LocalDateTime parseExpire(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            if (v.length() == 10) return LocalDate.parse(v).atTime(23, 59, 59);
            return LocalDateTime.parse(v.length() == 16 ? v + ":00" : v);
        } catch (Exception e) {
            throw new BizException("过期时间格式应为 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm");
        }
    }
}
