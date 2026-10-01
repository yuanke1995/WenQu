package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ToolInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具清单（管理员视角：本平台注册了哪些 @Tool、各自开关状态）。
 * <p>
 * 权限：<b>运维视图，不进普通用户白名单</b>——RBAC 下管理员直通，普通角色默认 403
 * （同 ScheduleController / TraceController 口径，可在权限管理为角色显式绑定 /api/ai/tools/**）。
 * 只读，不提供任何修改能力——工具的启停在「设置 → 工具调用」面板与智能体能力卡片，别处不开洞。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/tools")
@RequiredArgsConstructor
@Tag(name = "工具清单", description = "已注册 @Tool 工具的全景清单（按工具族分组，含全局开关状态）")
public class ToolInventoryController {

    private final ToolInventoryService toolInventoryService;

    @Operation(summary = "工具全景清单", description = "按工具族（内置/知识检索/联网搜索/产物/技能/沙盒/MCP）"
            + "分组返回全部已注册工具：name 与 @Tool 方法名一致、label 为中文展示名、description 为模型可见的"
            + "工具说明、sensitive 标记「有副作用工具」、enabled 为该族当前是否可用（总闸 × 族开关）")
    @GetMapping
    public ResultJson inventory() {
        return ResultJson.ok(toolInventoryService.inventory());
    }
}
