package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.ApiEndpoint;
import com.wisesoft.ai.service.ApiEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.Map;

/**
 * 接口管理接口（权限管理页）：RBAC 鉴权数据源的维护。
 * <p>主体由启动期扫描登记（builtin=1）；此处支持手工登记、名称/模块维护与删除。</p>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/api")
@RequiredArgsConstructor
@Tag(name = "接口管理", description = "RBAC 接口清单（启动期自动扫描 + 手工维护）")
public class ApiEndpointController {

    private final ApiEndpointService apiEndpointService;

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    @Operation(summary = "接口列表", description = "module 传模块名过滤（all=全部）；keyword 模糊匹配路径/名称")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(required = false) String module,
                           @RequestParam(required = false) String keyword) {
        return ResultJson.ok(apiEndpointService.list(module, keyword));
    }

    @Operation(summary = "手工登记接口", description = "body: method(GET/POST/PUT/DELETE/PATCH/ALL)/path(以 /api/ 开头)/name/module")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(apiEndpointService.create(str(body, "method"), str(body, "path"),
                str(body, "name"), str(body, "module")), "已登记");
    }

    @Operation(summary = "编辑接口", description = "扫描登记的仅可改名称/模块；手工的可全改")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        apiEndpointService.update(id, str(body, "method"), str(body, "path"),
                str(body, "name"), str(body, "module"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除接口", description = "级联清理角色绑定；代码中仍存在的端点重启后会重新登记")
    @DeleteMapping("/{id}")
    public ResultJson delete(@Parameter(description = "接口 ID") @PathVariable String id) {
        apiEndpointService.delete(id);
        return ResultJson.ok("已删除");
    }
}
