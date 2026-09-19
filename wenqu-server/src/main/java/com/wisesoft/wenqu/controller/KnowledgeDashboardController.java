package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.service.KnowledgeDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识域看板路由，对齐参考实现 {@code server/routers/knowledge_dashboard_router.py}。
 *
 * <p>权限与错误语义照搬：仅超级管理员（403 {@code 需要超级管理员权限}）；
 * 统计失败 500 {@code Failed to get knowledge stats: {原因}}。
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "知识域看板统计")
public class KnowledgeDashboardController {

    private final KnowledgeDashboardService knowledgeDashboardService;

    @Operation(summary = "知识库统计", description = "超级管理员权限")
    @GetMapping("/stats/knowledge")
    public Map<String, Object> readKnowledgeStats() {
        AuthGuards.requireSuperadmin();
        try {
            return knowledgeDashboardService.getKnowledgeStats();
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Error getting knowledge stats: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "Failed to get knowledge stats: " + exc.getMessage());
        }
    }
}
