package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.UserMemoryService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户长期记忆（个人设置页管理）：全部按 RequestUser.uid() 归属过滤，
 * 只能看/改/删自己的记忆。注入与自动提取在 RagService/UserMemoryService 内完成。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/memory")
@RequiredArgsConstructor
@Tag(name = "用户长期记忆", description = "跨会话记忆：问答后自动提取 + 手动管理，注入本人后续问答")
public class MemoryController {

    private final UserMemoryService memoryService;

    @Operation(summary = "我的记忆列表", description = "按更新时间倒序；含来源（auto/manual）与使用度（被注入次数）")
    @GetMapping
    public ResultJson list() {
        return ResultJson.ok(memoryService.list(RequestUser.uid()));
    }

    @Operation(summary = "手动添加记忆", description = "body: {content, category?}——category: fact/instruction/project，默认 fact")
    @PostMapping
    public ResultJson add(@RequestBody Map<String, String> body) {
        var m = memoryService.addManual(RequestUser.uid(),
                body.get("content"), body.get("category"));
        return ResultJson.ok(m, "已添加");
    }

    @Operation(summary = "编辑记忆", description = "仅可编辑本人的；body: {content}")
    @PutMapping("/{id}")
    public ResultJson update(
            @Parameter(description = "记忆 ID") @PathVariable("id") String id,
            @RequestBody Map<String, String> body) {
        memoryService.updateContent(id, RequestUser.uid(), body.get("content"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除记忆", description = "仅可删除本人的；自动提取的记忆也可在此删除（含来源会话溯源）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@Parameter(description = "记忆 ID") @PathVariable("id") String id) {
        memoryService.delete(id, RequestUser.uid());
        return ResultJson.ok("已删除");
    }
}
