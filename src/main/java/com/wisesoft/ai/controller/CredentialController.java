package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.CredentialService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作流凭据管理接口（第 3 期）：个人资产，归属取登录令牌 uid。
 * <p>
 * 工作流的 http 节点里以 {@code {{credential:名称}}} 引用；值加密落库，出参一律脱敏
 * （{@code ****后4位}），明文只在节点执行瞬间解密。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/credential")
@RequiredArgsConstructor
@Tag(name = "工作流凭据", description = "http 节点引用的密钥（个人资产，值加密落库、出参脱敏）")
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "我的凭据列表", description = "值脱敏为 ****后4位，永不回明文")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(credentialService.list(RequestUser.uid()));
    }

    @Operation(summary = "新建凭据", description = "body: {name, value, remark?}；名称同用户内唯一")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(credentialService.create(RequestUser.uid(),
                str(body, "name"), str(body, "value"), str(body, "remark")));
    }

    @Operation(summary = "编辑凭据", description = "body: {name?, value?, remark?}；value 留空或以 **** 开头 = 保持不变")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(credentialService.update(RequestUser.uid(), id,
                str(body, "name"), str(body, "value"), str(body, "remark")));
    }

    @Operation(summary = "删除凭据", description = "只删本人的；已引用它的工作流节点运行时会 fail-loud 提示未配置")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        credentialService.delete(RequestUser.uid(), id);
        return ResultJson.ok(Map.of("id", id));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
