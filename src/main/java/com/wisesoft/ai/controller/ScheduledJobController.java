package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ScheduledJobService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 定时执行智能体接口（个人资产）：每人管自己的任务，归属校验在 Service（{@code mustOwn}）里做。
 * <p>
 * 执行是异步的（一轮问答可能几十秒）：手动触发的接口立即返回，状态去执行历史里看，
 * 回答正文在该任务的专属会话里（点 sessionId 跳过去即可）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/scheduled")
@RequiredArgsConstructor
@Tag(name = "定时任务", description = "定时执行智能体（个人资产）")
public class ScheduledJobController {

    private final ScheduledJobService scheduledJobService;

    @Operation(summary = "我的定时任务", description = "按创建时间倒序，附最近一次执行状态")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(scheduledJobService.list(RequestUser.uid()));
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public ResultJson detail(@PathVariable String id) {
        try {
            return ResultJson.ok(scheduledJobService.detail(RequestUser.uid(), id));
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "新建定时任务", description = "cron 为 5 段（分 时 日 月 周），按 timezone 解释")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        try {
            return ResultJson.ok(scheduledJobService.create(RequestUser.uid(), body), "已创建");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "修改定时任务", description = "改 cron / 时区 / 启停后会自动重算下次执行时刻")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ResultJson.ok(scheduledJobService.update(RequestUser.uid(), id, body), "已保存");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "删除定时任务", description = "软删；已产生的执行记录与结果会话保留")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        try {
            scheduledJobService.delete(RequestUser.uid(), id);
            return ResultJson.ok(null, "已删除");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "启用/停用", description = "body: {enabled: true|false}；启用时从当前时刻往后重算下次执行")
    @PutMapping("/{id}/enabled")
    public ResultJson toggle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
            return ResultJson.ok(scheduledJobService.toggle(RequestUser.uid(), id, enabled),
                    enabled ? "已启用" : "已停用");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "立即执行一次", description = "异步执行（不等结果）；状态与回答看执行历史与该任务的结果会话")
    @PostMapping("/{id}/run")
    public ResultJson runNow(@PathVariable String id) {
        try {
            scheduledJobService.runNow(RequestUser.uid(), id);
            return ResultJson.ok(null, "已触发执行，稍后可在执行历史里查看结果");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "执行历史", description = "最近 N 次执行的触发方式/状态/回答摘录（正文在结果会话里）")
    @GetMapping("/{id}/runs")
    public ResultJson runs(@PathVariable String id,
                           @RequestParam(required = false, defaultValue = "20") int limit) {
        try {
            return ResultJson.ok(scheduledJobService.runs(RequestUser.uid(), id, limit));
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }
}
