package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.memory.MemoryService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 个人长期记忆面板（仅本人）：查看、编辑 {@code MEMORY.md}、按天查看/删除日账、清空、按上限裁剪。
 *
 * <p>记忆的日常写入由框架的 {@code MemoryFlushManager}/{@code MemoryConsolidator} 承担；
 * 本接口只补“用户看得见、管得住”的那一层。所有路径都按登录 uid 解析，不接收调用方声明的用户。
 */
@RestController
@RequestMapping("/api/ai/memory")
@RequiredArgsConstructor
@Tag(name = "长期记忆", description = "个人记忆文件查看与治理（仅本人）")
public class MemoryController {

    private final MemoryService memoryService;

    @Operation(summary = "我的记忆概览", description = "返回 MEMORY.md 内容、大小/token、上限、日账清单与最后合并时间")
    @GetMapping
    public ResultJson overview() {
        return ResultJson.ok(memoryService.overview(RequestUser.uid()));
    }

    @Operation(summary = "读取某个记忆文件", description = "path=MEMORY.md 或 memory/YYYY-MM-DD.md；日账只读")
    @GetMapping("/content")
    public ResultJson content(
            @Parameter(description = "MEMORY.md 或 memory/YYYY-MM-DD.md") @RequestParam("path") String path) {
        return ResultJson.ok(memoryService.content(RequestUser.uid(), path));
    }

    @Operation(summary = "保存 MEMORY.md", description = "{\"content\":\"...\"}；仅本人；超过 token 上限拒绝")
    @PutMapping
    public ResultJson save(@RequestBody Map<String, String> body) {
        return ResultJson.ok(memoryService.saveMemoryMd(RequestUser.uid(), body.get("content")), "已保存");
    }

    @Operation(summary = "按上限裁剪 MEMORY.md", description = "把 MEMORY.md 确定性裁剪到 memory.maxMdTokens 对应的字符预算")
    @PostMapping("/compact")
    public ResultJson compact() {
        return ResultJson.ok(memoryService.compact(RequestUser.uid()), "已裁剪");
    }

    @Operation(summary = "清空我的全部记忆", description = "删除 MEMORY.md、全部日账、归档与 consolidation 游标；不可恢复")
    @DeleteMapping
    public ResultJson clear() {
        return ResultJson.ok(memoryService.clearAll(RequestUser.uid()), "已清空");
    }

    @Operation(summary = "删除某天日账", description = "date=YYYY-MM-DD；只删那一天的原始记录，不回改 MEMORY.md")
    @DeleteMapping("/daily")
    public ResultJson deleteDaily(
            @Parameter(description = "YYYY-MM-DD") @RequestParam("date") String date) {
        return ResultJson.ok(memoryService.deleteDaily(RequestUser.uid(), date), "已删除");
    }
}
