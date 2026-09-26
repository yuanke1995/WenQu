package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ArtifactService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产物接口（我的产物）：模型在回答中生成的可下载文件。
 * <p>
 * 归属口径：**按用户**（不是按会话）——会话可以清理，成果仍归人。普通用户只能看/删自己的，
 * 管理员可越权（与知识库/文档同一套资源可见性口径）。下载本身走签名 URL（
 * {@code /ai/artifacts/**} 静态映射 + 签名拦截器），所以列表返回的就是可直接下载的地址。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/artifact")
@RequiredArgsConstructor
@Tag(name = "产物", description = "模型生成的产物文件（我的产物）")
public class ArtifactController {

    private final ArtifactService artifactService;
    private final com.wisesoft.ai.service.RoleService roleService;

    private boolean admin() {
        return roleService.isAdminCode(RequestUser.role());
    }

    @Operation(summary = "我的产物列表", description = "当前用户生成的产物（时间倒序；keyword 匹配文件名）；url 为可直接下载的签名地址")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(required = false) String keyword) {
        return ResultJson.ok(artifactService.list(RequestUser.uid(), keyword));
    }

    @Operation(summary = "删除产物", description = "删除自己的产物（同时删除文件）；删他人的返回 403")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        try {
            boolean ok = artifactService.softDelete(id, RequestUser.uid(), admin());
            return ok ? ResultJson.ok(null, "已删除") : ResultJson.error("产物不存在或已删除");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(403, e.getMessage());
        }
    }
}
