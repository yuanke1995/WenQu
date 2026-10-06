package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.schedule.ScheduleCenter;
import com.wenqu.ai.service.ScheduleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 定时任务管理（ScheduleCenter 运行快照 / 执行日志 / 手动触发）。
 * <p>
 * 权限：<b>运维功能，不进普通用户白名单</b>——RBAC 下管理员直通，普通角色默认 403
 * （同 TraceController 口径，可在权限管理为角色显式绑定 /api/ai/schedule/**）。
 * 暂停/恢复不设专门端点：任务的间隔即配置键，前端复用既有 PUT /api/ai/config 写 0（暂停）
 * 或默认值（恢复），校验/掩码/多副本广播与设置页保存走同一条路。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/schedule")
@RequiredArgsConstructor
@Tag(name = "定时任务管理", description = "任务运行快照、执行日志查询与手动触发（ScheduleCenter）")
public class ScheduleController {

    private final ScheduleCenter scheduleCenter;
    private final ScheduleAdminService scheduleAdminService;

    @Operation(summary = "任务快照", description = "全部注册任务的元数据与运行统计：实时间隔/暂停态、上次结果与耗时、"
            + "下次预期触发时间（now 为服务端当前时间，前端算倒计时用）")
    @GetMapping("/tasks")
    public ResultJson tasks() {
        return ResultJson.ok(scheduleCenter.snapshot());
    }

    @Operation(summary = "手动触发一次", description = "body: {name:任务名}——复用同一防重叠与执行管线（trigger=manual 落日志）；"
            + "暂停中的任务也允许手动触发；上一轮还在跑时返回 accepted=false")
    @PostMapping("/tasks/trigger")
    public ResultJson trigger(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少任务名 name");
        }
        return ResultJson.ok(scheduleCenter.triggerNow(name.trim()));
    }

    @Operation(summary = "执行日志", description = "c_ai_schedule_run 分页（只记有信息量的执行：失败/手动触发/有产出；"
            + "解析队列扫描等高频任务空跑不落行）。筛选：taskName 任务名、success 1/0")
    @GetMapping("/runs")
    public ResultJson runs(@RequestParam(value = "taskName", required = false) String taskName,
                           @RequestParam(value = "success", required = false) Integer success,
                           @RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResultJson.ok(scheduleAdminService.listRuns(taskName, success, page, size));
    }

    @Operation(summary = "清空执行日志", description = "物理删除全部执行日志（不等保留期清理任务），返回删除行数")
    @DeleteMapping("/runs")
    public ResultJson clearRuns() {
        return ResultJson.ok(scheduleAdminService.clearRuns());
    }
}
