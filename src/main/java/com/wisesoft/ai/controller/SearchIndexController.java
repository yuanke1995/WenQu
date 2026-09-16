package com.wisesoft.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.service.KeywordIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关键词索引运维：引擎状态 / 全量重建 / 清空。
 * 索引为"最终一致"（写入 best-effort），stats 的 indexedCount 与 mysqlCount 对比即可发现漂移；
 * 切换引擎保存配置时已自动全量重建，本接口用于漂移修复或手动重建。
 */
@Slf4j
@Tag(name = "关键词索引")
@RestController
@RequestMapping("/api/ai/search-index")
@RequiredArgsConstructor
public class SearchIndexController {

    private final KeywordIndexService keywordIndexService;
    private final KnowledgeMapper knowledgeMapper;

    @Operation(summary = "引擎状态与索引统计（indexedCount 与 mysqlCount 对比可发现索引漂移）")
    @GetMapping("/stats")
    public ResultJson<Map<String, Object>> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", keywordIndexService.engine());
        m.put("available", keywordIndexService.isAvailable());
        m.put("unavailableReason", keywordIndexService.debugUnavailableReason());
        m.put("indexedCount", keywordIndexService.indexedCount());
        m.put("writeFailCount", keywordIndexService.writeFailCount()); // M12 fail-loud：写失败累计（>0 说明有过写故障）
        long mysqlCount;
        try {
            mysqlCount = knowledgeMapper.selectCount(new LambdaQueryWrapper<>());
        } catch (Exception e) {
            mysqlCount = -1;
        }
        m.put("mysqlCount", mysqlCount);
        return ResultJson.ok(m);
    }

    @Operation(summary = "全量重建索引（切换引擎保存配置时已自动触发；漂移修复或手动重建用）")
    @PostMapping("/reindex")
    public ResultJson<String> reindex() {
        if (!keywordIndexService.enabled()) {
            return ResultJson.error("关键词引擎未启用 Meilisearch（keyword.engine=mysql），无需重建");
        }
        keywordIndexService.reindexAll();
        return ResultJson.ok("全量重建已启动（后台执行，可通过 /stats 观察 indexedCount 增长）");
    }

    @Operation(summary = "清空索引全部文档")
    @DeleteMapping
    public ResultJson<String> clear() {
        keywordIndexService.clearAll();
        return ResultJson.ok("索引已清空（下次解析/重解析会增量写回；也可执行 /reindex 全量重建）");
    }
}
