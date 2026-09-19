package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.agents.ToolkitsService;
import com.wisesoft.wenqu.config.AuthGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具体系路由，逐端点对齐参考实现 {@code server/routers/tool_router.py}
 * （前缀 {@code /system/tools}）。
 *
 * <p>响应契约照搬：两个端点都返回 {@code {"success": true, "data": ...}}；
 * 列表端点的 {@code data} 为工具元数据数组（含 {@code slug}/{@code name}/{@code description}/
 * {@code metadata}/{@code args} 以及附加的 {@code category}/{@code tags}/{@code config_guide}），
 * 选项端点的 {@code data} 为 {@code [{"label": name, "value": slug}]}。
 *
 * <p>权限照搬：两者都只要求登录（{@link AuthGuards#requireUser()}），无管理员限制。
 *
 * <p>平台差异（必要替换）：{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * 参考实现用默认值 {@code None} 表示「不按分类过滤」，本工程用
 * {@code @RequestParam(required = false)} 承接，空串同样按「不过滤」处理（保持
 * {@code if category:} 的真值语义）。
 */
@Slf4j
@RestController
@RequestMapping("/api/system/tools")
@RequiredArgsConstructor
@Tag(name = "Tools", description = "工具列表与选项")
public class ToolController {

    /** 获取工具列表。 */
    @Operation(summary = "工具列表", description = "可选按分类过滤")
    @GetMapping
    public Map<String, Object> listTools(
            @RequestParam(value = "category", required = false) String category) {
        AuthGuards.requireUser();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", ToolkitsService.getToolMetadata(category));
        return result;
    }

    /** 获取工具选项（前端下拉框用）。 */
    @Operation(summary = "工具选项", description = "前端下拉框用的 label/value 列表")
    @GetMapping("/options")
    public Map<String, Object> getToolOptions() {
        AuthGuards.requireUser();
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> tool : ToolkitsService.getToolMetadata(null)) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", tool.get("name"));
            option.put("value", tool.get("slug"));
            options.add(option);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", options);
        return result;
    }
}
