package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Operation(summary = "我的产物列表", description = "当前用户生成的产物（时间倒序；keyword 匹配文件名）；url 为可直接下载的签名地址")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "keyword", required = false) String keyword) {
        return ResultJson.ok(artifactService.list(RequestUser.uid(), keyword));
    }

    @Operation(summary = "删除产物", description = "删除自己的产物（同时删除文件）；删他人的返回 403")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        try {
            boolean ok = artifactService.softDelete(id, RequestUser.uid());
            return ok ? ResultJson.ok(null, "已删除") : ResultJson.error("产物不存在或已删除");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(403, e.getMessage());
        }
    }

    /** 批量换发签名请求体 */
    public record RefreshSignReq(List<String> ids) {
    }

    /**
     * 按产物 id 批量换发新鲜签名地址（对话页下载用）。
     * <p>产物签名有效期只有 1 小时（{@code images.authExpireSeconds}），对话页的卡片 URL
     * 是SSE 下发 / 历史接口里那一份签好就不变的，页面开久后点下载必然 401；
     * 「我的产物」页因每次进页面都重新签而始终正常。渲染卡片前调本方法换一批新签名即可根治。
     * <p>入参是id 而非 url：避免开放「给任意路径签名」的能力（那会把产物鉴权降级成万能签名器，
     * 可签出他人产物与文档截图）。归属逐条校验，取不回的静默跳过，前端无需处理部分失败。
     */
    @Operation(summary = "换发产物签名", description = "按产物 id 返回带新鲜签名的下载地址（签名 1 小时过期，"
            + "对话页长时间停留后需换发）；取不回的（不存在/已删/无权/文件已清理）直接跳过")
    @PostMapping("/refresh-sign")
    public ResultJson refreshSign(@RequestBody RefreshSignReq req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return ResultJson.error("缺少产物 id");
        }
        return ResultJson.ok(artifactService.refreshSignatures(RequestUser.uid(), req.ids()));
    }

    /** 批量删除请求体 */
    public record BatchDeleteReq(List<String> ids) {
    }

    @Operation(summary = "批量删除产物", description = "逐条删除（逐条校验归属，复用单条口径）；"
            + "返回 {deleted, skipped}，skipped 为不存在/已删除/无权的 id")
    @PostMapping("/batch-delete")
    public ResultJson deleteBatch(@RequestBody BatchDeleteReq req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return ResultJson.error("未选择要删除的产物");
        }
        int deleted = 0;
        List<String> skipped = new ArrayList<>();
        for (String id : req.ids()) {
            try {
                if (artifactService.softDelete(id, RequestUser.uid())) {
                    deleted++;
                } else {
                    skipped.add(id);
                }
            } catch (IllegalArgumentException e) {
                skipped.add(id);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("skipped", skipped);
        return ResultJson.ok(data, "已删除 " + deleted + " 项");
    }
}
