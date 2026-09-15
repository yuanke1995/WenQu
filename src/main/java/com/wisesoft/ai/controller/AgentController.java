package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.AgentService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体配置接口（管理员）：列表 / 新建 / 编辑 / 删除 / 设默认。
 * <p>路径不在问答用户白名单内，需管理员身份（SecurityConfig fail-closed 拦截）。</p>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
@Tag(name = "智能体配置", description = "P3：4.1 Agent 配置——模型/知识库/工具/提示词打包成命名预设")
public class AgentController {

    private final AgentService agentService;

    @Operation(summary = "智能体列表", description = "默认智能体在前；其余按创建时间倒序")
    @GetMapping("/list")
    public ResultJson list() {
        List<?> agents = agentService.list();
        return ResultJson.ok(agents);
    }

    @Operation(summary = "对话页可用的智能体", description = "问答用户可读的精简列表（仅 id/name/description/model/isDefault），供对话页下拉选择；"
            + "不含提示词/知识库范围/工具开关等管理配置。该路径在问答用户白名单内")
    @GetMapping("/available")
    public ResultJson available() {
        return ResultJson.ok(agentService.available());
    }

    @Operation(summary = "新建智能体", description = "body 字段：name(必填)/description/model/systemPrompt/knowledgeScope/"
            + "toolKnowledge/toolBuiltin/toolSkill/toolArtifact/toolMcp(1开0关，省略=继承)/isDefault")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(agentService.create(body));
    }

    @Operation(summary = "编辑智能体", description = "仅更新 body 中出现的字段；工具开关传 null 表示恢复继承")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(agentService.update(id, body));
    }

    @Operation(summary = "删除智能体", description = "物理删除")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        agentService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "设为默认", description = "设为默认智能体（其余清零）；仅影响前端下拉预选，不自动强制应用")
    @PostMapping("/{id}/default")
    public ResultJson setDefault(@PathVariable String id) {
        agentService.setDefault(id);
        return ResultJson.ok(Map.of("id", id, "isDefault", 1));
    }
}
