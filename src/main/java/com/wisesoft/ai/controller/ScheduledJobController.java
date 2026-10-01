package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ScheduledJobService;
import com.wisesoft.ai.util.BatchResults;
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

import java.util.ArrayList;
import java.util.List;
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
    public ResultJson detail(@PathVariable("id") String id) {
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
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        try {
            return ResultJson.ok(scheduledJobService.update(RequestUser.uid(), id, body), "已保存");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "删除定时任务", description = "软删；已产生的执行记录与结果会话保留")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        try {
            scheduledJobService.delete(RequestUser.uid(), id);
            return ResultJson.ok(null, "已删除");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——失败条目逐条带原因（结构收口在 BatchResults）
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "批量删除定时任务", description = "body: {ids:[...]}；只删本人的（逐条按单条口径）；"
            + "返回 {succeeded:[id], failed:[{id,name,error}]}；已产生的执行记录与结果会话保留")
    @PostMapping("/batch-delete")
    public ResultJson batchDelete(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        if (ids.isEmpty()) return ResultJson.error("请先选择要删除的任务");
        String uid = RequestUser.uid();
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            String name = jobName(uid, id);
            try {
                scheduledJobService.delete(uid, id);
                succeeded.add(id);
            } catch (BizException e) {
                failed.add(BatchResults.failItem(id, name, e.getMessage()));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    @Operation(summary = "批量启用/停用定时任务", description = "body: {ids:[...], enabled:true|false}；只操作本人的；"
            + "启用时从当前时刻往后重算下次执行；返回 {succeeded:[id], failed:[{id,name,error}]}")
    @PostMapping("/batch-enabled")
    public ResultJson batchEnabled(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (ids.isEmpty()) return ResultJson.error("请先选择要操作的任务");
        String uid = RequestUser.uid();
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            String name = jobName(uid, id);
            try {
                scheduledJobService.toggle(uid, id, enabled);
                succeeded.add(id);
            } catch (BizException e) {
                failed.add(BatchResults.failItem(id, name, e.getMessage()));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    /** 失败条目要带任务名；detail 拿不到（已被删/并发变动）就回落 id 前缀展示 */
    private String jobName(String uid, String id) {
        try {
            Object d = scheduledJobService.detail(uid, id);
            Object name = d instanceof Map<?, ?> m ? m.get("name") : null;
            return name == null ? null : String.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    @Operation(summary = "启用/停用", description = "body: {enabled: true|false}；启用时从当前时刻往后重算下次执行")
    @PutMapping("/{id}/enabled")
    public ResultJson toggle(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
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
    public ResultJson runNow(@PathVariable("id") String id) {
        try {
            scheduledJobService.runNow(RequestUser.uid(), id);
            return ResultJson.ok(null, "已触发执行，稍后可在执行历史里查看结果");
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "执行历史", description = "最近 N 次执行的触发方式/状态/回答摘录（正文在结果会话里）")
    @GetMapping("/{id}/runs")
    public ResultJson runs(@PathVariable("id") String id,
                           @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        try {
            return ResultJson.ok(scheduledJobService.runs(RequestUser.uid(), id, limit));
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }
}
