package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.KeywordIndexService;
import com.wenqu.ai.service.RoleService;
import com.wenqu.ai.util.RequestUser;
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
import com.wenqu.ai.security.Permission;

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
    private final RoleService roleService;

    /** 重建/清空是全平台破坏性运维操作，状态含索引规模信息，整体收紧到管理员 */
    private boolean admin() {
        return roleService.isAdminAny(RequestUser.roles());
    }

    @Operation(summary = "引擎状态与索引统计（indexedCount 与 mysqlCount 对比可发现索引漂移）")
    @Permission("search-index:manage")
    @GetMapping("/stats")
    public ResultJson<Map<String, Object>> stats() {
        if (!admin()) throw new BizException("仅管理员可查看关键词索引状态");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", keywordIndexService.engine());
        m.put("available", keywordIndexService.isAvailable());
        m.put("unavailableReason", keywordIndexService.debugUnavailableReason());
        m.put("indexedCount", keywordIndexService.indexedCount());
        m.put("writeFailCount", keywordIndexService.writeFailCount()); // M12 fail-loud：写失败累计（>0 说明有过写故障）
        // 与重建/对账同口径：只数有效块（生效文档的块 + 手动块）。数全表会把手动删除文档仍留在
        // 回收站里的块算进来，索引永远追不平，漂移信号常亮成噪声
        long effectiveChunks;
        try {
            effectiveChunks = keywordIndexService.countEffectiveChunks();
        } catch (Exception e) {
            effectiveChunks = -1;
        }
        m.put("mysqlCount", effectiveChunks);
        return ResultJson.ok(m);
    }

    @Operation(summary = "全量重建索引（切换引擎保存配置时已自动触发；漂移修复或手动重建用）")
    @Permission("search-index:manage")
    @PostMapping("/reindex")
    public ResultJson<String> reindex() {
        if (!admin()) throw new BizException("仅管理员可重建关键词索引");
        if (!keywordIndexService.enabled()) {
            return ResultJson.error("关键词引擎未启用 Meilisearch（keyword.engine=mysql），无需重建");
        }
        keywordIndexService.reindexAll();
        return ResultJson.ok("全量重建已启动（后台执行，可通过 /stats 观察 indexedCount 增长）");
    }

    @Operation(summary = "清空索引全部文档")
    @Permission("search-index:manage")
    @DeleteMapping
    public ResultJson<String> clear() {
        if (!admin()) throw new BizException("仅管理员可清空关键词索引");
        keywordIndexService.clearAll();
        return ResultJson.ok("索引已清空（下次解析/重解析会增量写回；也可执行 /reindex 全量重建）");
    }
}
