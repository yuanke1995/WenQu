package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 聊天附件（上传 / 有效性校验）。
 * 从 ChatController 拆出：这两个端点只关心「文件能不能用」。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "聊天附件", description = "聊天附件上传与有效性校验")
public class ChatAttachmentController {

private final RateLimitService rateLimitService;
private final com.wenqu.ai.service.ChatUploadService chatUploadService;

    @Operation(summary = "上传聊天附件", description = "把聊天附件先上传换 fileId（问答请求体只带 fileId，不再内联 base64）；"
            + "文件按用户隔离落盘、超期自动清理；按用户限频（ratelimit.uploadPerMinute）")
    @ApiResponse(responseCode = "200", description = "返回 {fileId,name,mime,size}")
    @Permission("chat:use")
    @PostMapping("/chat/attachment")
    public ResultJson uploadAttachment(
            @Parameter(description = "附件文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        rateLimitService.checkRateLimit("upload", RequestUser.SYSTEM.equals(userId)
                ? "ip:" + com.wenqu.ai.util.ClientIp.of(httpRequest) : "user:" + userId);
        return ResultJson.ok(chatUploadService.save(file, userId), "上传完成");
    }

    /**
     * 批量校验附件是否仍可重发：返回入参里字节仍在盘上的那部分（{@code {live:[fileId]}}）。
     * 界面上「已失效」这三个字得有据可查——不带这个端点，前端只能把「这一端没拿到 fileId」
     * 一律报成「已过保留期」，而文件其实还在（保留期默认 24h，刷新页面只需 0 秒）。
     */
    @Operation(summary = "批量校验聊天附件是否仍可重发",
            description = "入参为 fileId 的 JSON 数组，返回其中仍可读回的子集 live；"
                    + "只查当前用户自己的上传目录，形态非法或不属于本人的 id 一律按不在处理。一次最多 50 个")
    @ApiResponse(responseCode = "200", description = "返回 {live:[fileId]}")
    @Permission("chat:use")
    @PostMapping("/chat/attachment/live")
    public ResultJson liveAttachments(
            @Parameter(description = "待校验的 fileId 数组") @RequestBody List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return ResultJson.ok(Map.of("live", List.of()));
        if (fileIds.size() > 50) throw new BizException("一次最多校验 50 个附件");
        return ResultJson.ok(Map.of("live", chatUploadService.liveIds(fileIds, RequestUser.uid())));
    }
}
