package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.ThinkPreferenceService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个人按模型的思考档位记忆：运行设置里选过的「关闭 / 中 / 高 / 超高 / 极致」随人走，不再只活在本机浏览器。
 * <p>
 * 读一次全量（模型数量有限），写一次一个模型——面板点档位就是一次 PUT，不整包覆盖别人的选择。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/user/think-levels")
@RequiredArgsConstructor
@Tag(name = "思考档位", description = "个人按模型记忆的思考强度（跨设备同步）")
public class ThinkLevelController {

    private final ThinkPreferenceService thinkPreferenceService;

    @Operation(summary = "读取本人按模型的思考档位",
            description = "返回 {levels:{\"providerId/modelId\":\"off|on|low|medium|high|xhigh|max\"}}；"
                    + "不在表里的模型=没单独选过，前端按模型登记默认档位显示")
    @Permission("self:preference")
    @GetMapping
    public ResultJson levels() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("levels", thinkPreferenceService.levelsOf(RequestUser.uid()));
        return ResultJson.ok(data);
    }

    @Operation(summary = "保存某模型的思考档位",
            description = "{\"model\":\"providerId/modelId\",\"level\":\"off|on|low|medium|high|xhigh|max\"}；"
                    + "level 为空串=清除该模型的记忆（回到模型默认）。只写本人，不影响他人")
    @Permission("self:preference")
    @PutMapping
    public ResultJson save(
            @Parameter(description = "{\"model\":\"providerId/modelId\",\"level\":\"high\"}")
            @RequestBody Map<String, Object> body) {
        thinkPreferenceService.save(RequestUser.uid(), RequestUser.role(),
                body.get("model") == null ? null : String.valueOf(body.get("model")),
                body.get("level") == null ? null : String.valueOf(body.get("level")));
        return ResultJson.ok("已保存");
    }
}