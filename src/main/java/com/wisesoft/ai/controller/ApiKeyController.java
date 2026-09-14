package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ApiKeyService;
import com.wisesoft.ai.util.UserContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * API Key 管理接口（管理员）：签发 / 列表 / 启停 / 删除。
 *
 * <p>Key 的权限固定为「问答链路」——持有 Key 的调用方走 X-Api-Key 头，
 * 仅能访问与普通用户等价的端点（问答/会话/反馈/引用溯源等），管理端点一律拒绝。
 * 本控制器自身不在白名单内，因此天然需要管理员身份。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/api-key")
@RequiredArgsConstructor
@Tag(name = "API Key 管理", description = "对外开放问答能力的密钥签发与吊销")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "Key 列表", description = "不含明文与哈希，仅前缀与使用/过期状态")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(apiKeyService.list());
    }

    @Operation(summary = "签发 Key", description = "返回明文 apiKey——仅此一次，请立即保存；库里只存哈希")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String name = body.get("name");
        LocalDateTime expireAt = parseExpire(body.get("expireAt"));
        return ResultJson.ok(apiKeyService.create(name, expireAt, UserContext.resolve(request)));
    }

    @Operation(summary = "启用/停用", description = "停用即吊销（保留记录便于审计）；body: {\"disabled\": true}")
    @PutMapping("/{id}/disabled")
    public ResultJson setDisabled(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        apiKeyService.setDisabled(id, disabled);
        return ResultJson.ok(Map.of("id", id, "disabled", disabled));
    }

    @Operation(summary = "删除 Key", description = "物理删除记录（不再需要时清理；日常吊销建议用停用）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
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
