package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.QaLogService;

/**
 * 会话的组织：列表、增删改、置顶/收藏/重命名、项目与工作区绑定、批量删除。
 * 会话运行态（模型覆盖、压缩、待办、权限）见 ChatSessionStateController；
 * 知识块与消息分组见 ChatMessageGroupController。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "会话列表、增删改、置顶收藏重命名、项目与工作区绑定")
public class ChatSessionController {

    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final QaLogService qaLogService;


    @GetMapping("/sessions")
    public ResultJson listSessions(
            @Parameter(description = "搜索关键词（可选，按标题/消息内容模糊匹配）")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "分页游标（首页不传；后续页传上一页返回的 nextCursor）")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "每页条数（默认 10，上限 100）")
            @RequestParam(value = "size", required = false, defaultValue = "10") int size,
            @Parameter(description = "工作区过滤（可选）：工作区 id；传 __unassigned__ 只看没挂工作区的存量会话；不传=全部")
            @RequestParam(value = "projectId", required = false) String projectId,
            HttpServletRequest httpRequest) {
        return ResultJson.ok(sessionService.listSessions(RequestUser.uid(), keyword, cursor, size, projectId));
    }

    @PutMapping("/session/{sessionId}/pin")
    public ResultJson updatePin(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        sessionService.updatePin(RequestUser.uid(), sessionId, pinned);
        return ResultJson.ok("操作成功");
    }

    @PutMapping("/session/{sessionId}/favorite")
    public ResultJson updateFavorite(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean favorite = Boolean.TRUE.equals(body.get("favorite"));
        sessionService.updateFavorite(RequestUser.uid(), sessionId, favorite);
        return ResultJson.ok("操作成功");
    }

    @PutMapping("/session/{sessionId}/rename")
    public ResultJson renameSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        if (title.trim().length() > 50) {
            throw new BizException("标题过长（最多 50 字）");
        }
        sessionService.renameSession(RequestUser.uid(), sessionId, title);
        return ResultJson.ok("操作成功");
    }

    @GetMapping("/session/{sessionId}/workspace")
    public ResultJson sessionWorkspace(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        return ResultJson.ok(sessionService.workspaceOfSession(sessionId));
    }

    @PutMapping("/session/{sessionId}/project")
    public ResultJson moveSessionProject(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body) {
        String pid = body == null ? null : body.get("projectId");
        sessionService.moveSessionProject(RequestUser.uid(), sessionId, pid);
        return ResultJson.ok(null, "已移动");
    }

    @GetMapping("/session/{sessionId}")
    public ResultJson getHistory(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        List<Map<String, Object>> history = sessionService.getHistory(sessionId);
        // 分支版本信息回填（编辑重发/重新生成的 ‹ n/N › 切换数据源）：带组标记的消息补 variantCount/variantIndex
        sessionService.attachVariantInfo(sessionId, history);
        // 历史图片存的是原始 URL，响应时动态签名（避免签名过期导致恢复会话图片 401）；
        // 与游客分享历史共用同一份出参签名口径，见 ImageUrlSigner.signMessageMedia
        imageUrlSigner.signMessageMedia(history);
        // 同步带回各回答消息的既有评价（fb）：前端"有/没帮助单选锁定"依赖此状态，刷新后不丢
        List<String> msgIds = history.stream()
                .map(m -> m.get("messageId") == null ? null : String.valueOf(m.get("messageId")))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, Integer> ratings = qaLogService.loadFeedbackRatings(msgIds);
        for (Map<String, Object> msg : history) {
            Object mid = msg.get("messageId");
            if (mid != null) {
                Integer fb = ratings.get(String.valueOf(mid));
                if (fb != null) msg.put("fb", fb);
            }
        }
        return ResultJson.ok(history);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.deleteSession(RequestUser.uid(), sessionId);
        return ResultJson.ok("会话已删除");
    }

    @DeleteMapping("/sessions")
    public ResultJson clearAllSessions(HttpServletRequest httpRequest) {
        sessionService.clearAll(RequestUser.uid());
        return ResultJson.ok("会话已清空");
    }

    @PostMapping("/sessions/batch-delete")
    public ResultJson batchDeleteSessions(
            @Parameter(description = "{\"ids\": [\"会话ID\", ...]}") @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        List<String> ids = body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (ids.isEmpty()) throw new BizException("ids 不能为空");
        int deleted = sessionService.batchDelete(RequestUser.uid(), ids);
        return ResultJson.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/session/new")
    public ResultJson newSession(
            @Parameter(description = "目标工作区（可选）；不传=兜底容器；__none__=不挂工作区")
            @RequestParam(value = "projectId", required = false) String projectId,
            HttpServletRequest httpRequest) {
        return ResultJson.ok(Map.of("sessionId",
                sessionService.createOrReuseEmptySession(RequestUser.uid(), projectId)));
    }
}
