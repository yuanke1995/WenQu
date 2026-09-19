package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.MentionSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提及文件搜索路由，逐端点对齐参考实现 {@code server/routers/mention_router.py}。
 *
 * <p>响应体为条目数组（参考实现 {@code response_model=list[MentionFileItem]}，
 * 字段 {@code name/path/is_dir/source}），**不做外层包装**——与参考实现的线上契约一致。
 *
 * <p>平台差异（必要替换）：FastAPI {@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * 参考实现把 User ORM 对象注入服务（服务内只取 uid），Java 侧按 uid 经
 * {@link UserRepository#getByUid} 装载同一实体。
 */
@Slf4j
@RestController
@RequestMapping("/api/mention")
@RequiredArgsConstructor
@Tag(name = "mention", description = "提及文件搜索接口")
public class MentionController {

    private final MentionSearchService mentionSearchService;
    private final UserRepository userRepository;

    @Operation(summary = "提及文件搜索", description = "未创建 thread 时只搜索用户 workspace；"
            + "已有 thread 时可搜索当前对话文件（thread_id 为空仅搜 workspace）")
    @GetMapping("/search")
    public List<Map<String, Object>> searchMentionFiles(
            @Parameter(description = "当前聊天会话 ID；为空时仅搜索用户工作区")
            @RequestParam(value = "thread_id", required = false) String threadId,
            @Parameter(description = "模糊搜索关键字")
            @RequestParam(value = "query", defaultValue = "") String query,
            @Parameter(description = "搜索来源：workspace,thread；为空时自动选择")
            @RequestParam(value = "sources", required = false) String sources) {
        String uid = AuthGuards.requireUser();
        try {
            return mentionSearchService.searchMentions(threadId, query, sources, userRepository.getByUid(uid));
        } catch (MentionSearchService.MentionThreadNotFoundError exc) {
            throw new ApiHttpException(404, exc.getMessage());
        } catch (MentionSearchService.InvalidMentionThreadError exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
    }
}
