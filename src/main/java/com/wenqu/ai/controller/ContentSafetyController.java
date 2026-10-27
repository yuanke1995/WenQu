package com.wenqu.ai.controller;

import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.ContentRule;
import com.wenqu.ai.service.contentsafety.ContentSafetyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容安全规则管理（Phase 3 · P12）。
 *
 * <p>与「系统设置 → 内容安全」的分工：那里是<b>闸门本身的开关与外部服务</b>（总开关、服务商、
 * 超时、文案），这里是<b>规则清单</b>——拦什么字。清单归清单是因为它要按角色授权：
 * 合规岗改词表不该同时拿到系统设置里的一堆全局开关。
 *
 * <p>只有管理员可读写：规则里含拦截词表本身，属于安全策略数据。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/content-safety")
@RequiredArgsConstructor
@Tag(name = "内容安全", description = "生成内容合规规则清单、试跑与闸门状态")
public class ContentSafetyController {

    private final ContentSafetyService safety;
    private final AdminGuard adminGuard;

    @Operation(summary = "规则清单（管理）",
            description = "全部规则（含未启用的），按排序号升序。判定顺序就是这个顺序，先命中的那条说话。")
    @GetMapping
    public ResultJson<List<ContentRule>> list(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("内容安全规则仅管理员可查看");
        return ResultJson.ok(safety.listRules());
    }

    @Operation(summary = "闸门状态（管理）",
            description = "此刻生效中的输入/输出规则条数、外部服务选择与坏正则清单。"
                    + "「配了规则却不生效」的两种成因都在这里露出来：规则被禁用，或正则无法编译被跳过。")
    @GetMapping("/status")
    public ResultJson<Map<String, Object>> status(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("内容安全规则仅管理员可查看");
        return ResultJson.ok(safety.status());
    }

    @Operation(summary = "新建或修改规则",
            description = "stage=input|output|both；matchType=keyword|regex；action=block|warn。"
                    + "关键词支持逗号或换行分隔多条。出口规则建议先 warn 跑一个观察期再改 block："
                    + "已经流到屏幕上的字收不回来，出口命中只能当场截断。")
    @PostMapping
    public ResultJson<Map<String, Object>> save(@RequestBody ContentRule rule, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("内容安全规则仅管理员可修改");
        ContentRule saved = safety.save(rule);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        return ResultJson.ok(out, "已保存");
    }

    @Operation(summary = "删除规则", description = "删除即停止判定（正在跑的那一轮按已生效的判定走完）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("内容安全规则仅管理员可删除");
        safety.delete(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "试跑规则",
            description = "给一段文本与侧别（input/output），返回会命中哪几条、各自会怎么处置。"
                    + "不落库、不写审计、不改任何东西——改词表时先看这一步，别靠真发一句话去猜。")
    @PostMapping("/dry-run")
    public ResultJson<Map<String, Object>> dryRun(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("内容安全规则仅管理员可使用");
        String text = body == null ? null : body.get("text");
        String stage = body == null ? null : body.get("stage");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hits", safety.dryRun(text, stage));
        out.put("broken", safety.brokenRules());
        return ResultJson.ok(out);
    }
}
