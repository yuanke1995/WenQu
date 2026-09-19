package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计与监控路由，逐端点对齐参考实现 {@code server/routers/dashboard_router.py}。
 *
 * <p>权限照搬：全部端点仅超级管理员（{@code get_superadmin_user}）；会话详情不存在时 404
 * {@code Conversation not found}。
 *
 * <p>平台差异（必要替换）：
 * <ul>
 *   <li>响应模型（pydantic）→ 直接返回服务层装配的 {@code Map}（字段由服务层对齐，不再二次建模）；</li>
 *   <li>{@code Literal[...]} 取值域约束 → 未在 HTTP 层强制（参考实现由 FastAPI 校验返回 422），
 *       非法值交由服务层按既有分支处理——如需逐字对齐，后续补显式校验；</li>
 *   <li>{@code Query(limit, ge=1, le=200)} / {@code Query(max_length=255)} → 同范围人工夹取。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "仪表盘统计与监控")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "基础统计指标", description = "超级管理员权限")
    @GetMapping("/stats")
    public Map<String, Object> getDashboardStats() {
        AuthGuards.requireSuperadmin();
        return dashboardService.getBasicStats();
    }

    @Operation(summary = "用户活动统计", description = "超级管理员权限")
    @GetMapping("/stats/users")
    public Map<String, Object> getUserActivityStats() {
        AuthGuards.requireSuperadmin();
        return dashboardService.getUserActivityStats(DateTimeUtils.utcNowNaive());
    }

    @Operation(summary = "工具调用统计", description = "超级管理员权限")
    @GetMapping("/stats/tools")
    public Map<String, Object> getToolCallStats() {
        AuthGuards.requireSuperadmin();
        return dashboardService.getToolCallStats(DateTimeUtils.utcNowNaive());
    }

    @Operation(summary = "智能体分析", description = "超级管理员权限")
    @GetMapping("/stats/agents")
    public Map<String, Object> getAgentAnalytics() {
        AuthGuards.requireSuperadmin();
        return dashboardService.getAgentAnalytics();
    }

    @Operation(summary = "调用分析时间序列", description = "超级管理员权限；type=models|agents|tokens|tools，"
            + "time_range=14hours|14days|14weeks")
    @GetMapping("/stats/calls/timeseries")
    public Map<String, Object> getCallTimeseriesStats(
            @RequestParam(value = "type", defaultValue = "models") String type,
            @RequestParam(value = "time_range", defaultValue = "14days") String timeRange) {
        AuthGuards.requireSuperadmin();
        return dashboardService.getCallTimeseries(type, timeRange);
    }

    @Operation(summary = "会话多维分析", description = "超级管理员权限")
    @GetMapping("/stats/threads")
    public Map<String, Object> getThreadAnalyticsStats(
            @RequestParam(value = "time_range", defaultValue = "30days") String timeRange,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @Parameter(description = "是否将子智能体会话纳入统计")
            @RequestParam(value = "include_subagents", defaultValue = "false") boolean includeSubagents) {
        AuthGuards.requireSuperadmin();
        return dashboardService.getThreadAnalytics(timeRange, agentId, includeSubagents);
    }

    @Operation(summary = "反馈记录列表", description = "超级管理员权限")
    @GetMapping("/feedbacks")
    public List<Map<String, Object>> getAllFeedbacks(
            @RequestParam(value = "rating", required = false) String rating,
            @RequestParam(value = "agent_id", required = false) String agentId) {
        AuthGuards.requireSuperadmin();
        return dashboardService.getFeedbacks(rating, agentId);
    }

    @Operation(summary = "会话审计筛选项", description = "超级管理员权限")
    @GetMapping("/conversations/options")
    public Map<String, List<Map<String, Object>>> getConversationFilterOptions() {
        AuthGuards.requireSuperadmin();
        return dashboardService.getConversationFilterOptions();
    }

    @Operation(summary = "会话审计列表", description = "超级管理员权限；limit 范围 1..200，默认 100")
    @GetMapping("/conversations")
    public Map<String, Object> getAllConversations(
            @RequestParam(value = "uid", required = false) String uid,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "status", defaultValue = "all") String status,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        AuthGuards.requireSuperadmin();
        String boundedSearch = search != null && search.length() > 255 ? search.substring(0, 255) : search;
        int boundedLimit = Math.min(200, Math.max(1, limit));
        return dashboardService.listConversations(uid, agentId, status, boundedSearch, boundedLimit, Math.max(0, offset));
    }

    @Operation(summary = "会话详情", description = "超级管理员权限")
    @GetMapping("/conversations/{thread_id}")
    public Map<String, Object> getConversationDetail(@PathVariable("thread_id") String threadId) {
        AuthGuards.requireSuperadmin();
        Map<String, Object> data = dashboardService.getConversationDetail(threadId);
        if (data == null) {
            throw new ApiHttpException(404, "Conversation not found");
        }
        return data;
    }
}
