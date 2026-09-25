package com.wisesoft.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AdminGuard;
import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.dto.SessionInfo;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.service.ImageUrlSigner;
import com.wisesoft.ai.service.QaLogService;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.service.SessionService;
import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天控制器（SSE 流式）
 * 用户身份：登录用户 uid（未登录为 anonymous）；会话按用户隔离
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "智能问答", description = "SSE 流式问答、会话管理、知识块详情")
public class ChatController {

    private final RagService ragService;
    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wisesoft.ai.mapper.MessageMapper messageMapper;
    private final ConfigService configService;
    private final RateLimitService rateLimitService;
    private final QaLogService qaLogService;
    private final AdminGuard adminGuard;
    private final AuthService authService;

    /** 当前身份与权限（普通用户问答 UI 据此隐藏/显示管理入口；白名单端点，无需管理员即可调用） */
    @Operation(summary = "当前身份与权限", description = "返回当前登录用户（uid/username/role/departmentId）与是否管理员（admin=true 时前端展示文档/看板/评估/设置等管理入口）")
    @GetMapping("/auth/me")
    public ResultJson authMe(HttpServletRequest httpRequest) {
        return ResultJson.ok(authService.currentUser(RequestUser.uid(), adminGuard.isAdmin(httpRequest)));
    }

    @Operation(summary = "SSE 流式问答",
            description = "发送问题（可含图片），通过 SSE 流式返回 AI 回答。事件类型：thinking（深度思考增量）、thinking_done（思考结束）、token（文本增量）、image（图片 URL 列表）、done（引用来源/相关推荐/消息ID/思考全文）、error（错误信息）。按用户限频（ratelimit.chatPerMinute）")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request, HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        // 按用户限频（anonymous 落到 IP 维度，避免匿名共享池互相挤兑）
        rateLimitService.checkRateLimit("chat", RequestUser.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);

        String question = request.getQuestion().trim();

        // 聊天传图上限：数量与单张体积（防 base64 洪峰压垮解码/视觉处理；数据 URL 字符量≈体积×4/3）
        List<String> images = request.getImages();
        if (images != null && !images.isEmpty()) {
            int maxImages = Math.max(1, configService.getInt("chat.maxImagesPerMessage", 9));
            if (images.size() > maxImages) {
                throw new BizException("一次最多发送 " + maxImages + " 张图片");
            }
            int maxImageMb = Math.max(1, configService.getInt("chat.maxImageMb", 10));
            long maxBase64Chars = maxImageMb * 4L * 1024 * 1024 / 3; // base64 膨胀 4/3 后的字符量上限
            for (String img : images) {
                int comma = img == null ? -1 : img.indexOf(',');
                if (comma <= 0 || !img.startsWith("data:image/")) {
                    throw new BizException("图片格式不正确（需 data:image/* 的 data URL）");
                }
                if (img.length() - comma - 1 > maxBase64Chars) { // ≈maxImageMb MB 原图（base64 膨胀 4/3）
                    throw new BizException("单张图片不能超过 " + maxImageMb + "MB");
                }
            }
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession(userId);
        } else {
            // 客户端传入会话 ID：存在则校验归属（防跨用户读写），不存在则按当前用户补建会话记录（兼容旧客户端行为）
            try {
                sessionService.assertOwned(sessionId, userId);
            } catch (BizException e) {
                if (e.getCode() == 404) {
                    sessionId = sessionService.ensureSession(sessionId, userId);
                } else {
                    throw e;
                }
            }
        }

        // 超时配置化（chat.sseTimeoutMs，默认 5 分钟）；超时由 RagService.onTimeout 先发 warn 再 dispose（fail-loud）
        long sseTimeout = configService.getLong("chat.sseTimeoutMs");
        if (sseTimeout <= 0) sseTimeout = 300000L;
        SseEmitter emitter = new SseEmitter(sseTimeout);
        ragService.chat(sessionId, question, images, request.isDeepThink(), request.getAgentId(),
                request.getModel(), userId, emitter);
        return emitter;
    }

    @Operation(summary = "会话列表", description = "列出当前用户的会话（含 anonymous 历史兼容池；置顶优先、按更新时间倒序）；支持 keyword 按标题或消息内容模糊搜索")
    @GetMapping("/sessions")
    public ResultJson listSessions(
            @Parameter(description = "搜索关键词（可选，按标题/消息内容模糊匹配）")
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpServletRequest httpRequest) {
        List<SessionInfo> sessions = sessionService.listSessions(RequestUser.uid(), keyword);
        return ResultJson.ok(sessions);
    }

    @Operation(summary = "置顶/取消置顶会话", description = "设置会话置顶状态，置顶会话排在列表最前")
    @PutMapping("/session/{sessionId}/pin")
    public ResultJson updatePin(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        sessionService.updatePin(RequestUser.uid(), sessionId, pinned);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "收藏/取消收藏会话", description = "设置会话收藏状态，收藏会话可在侧边栏筛选")
    @PutMapping("/session/{sessionId}/favorite")
    public ResultJson updateFavorite(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean favorite = Boolean.TRUE.equals(body.get("favorite"));
        sessionService.updateFavorite(RequestUser.uid(), sessionId, favorite);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "重命名会话", description = "修改会话标题（≤50 字；校验归属）")
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

    @Operation(summary = "会话历史", description = "获取指定会话的完整对话历史（含图片与引用来源；校验会话归属）")
    @GetMapping("/session/{sessionId}")
    public ResultJson getHistory(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        List<Map<String, Object>> history = sessionService.getHistory(sessionId);
        // 历史图片存的是原始 URL，响应时动态签名（避免签名过期导致恢复会话图片 401）
        // 同步带回各回答消息的既有评价（fb）：前端"有/没帮助单选锁定"依赖此状态，刷新后不丢
        List<String> msgIds = history.stream()
                .map(m -> m.get("messageId") == null ? null : String.valueOf(m.get("messageId")))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, Integer> ratings = qaLogService.loadFeedbackRatings(msgIds);
        for (Map<String, Object> msg : history) {
            Object imgs = msg.get("images");
            if (imgs instanceof List<?> list && !list.isEmpty()) {
                msg.put("images", list.stream()
                        .map(String::valueOf)
                        .map(imageUrlSigner::signUrl)
                        .toList());
            }
            // 引用来源内的图片同样动态签名（引用弹窗直接加载；原始 URL 存库，避免签名过期 401）
            Object srcs = msg.get("sources");
            if (srcs instanceof List<?> srcList && !srcList.isEmpty()
                    && srcList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) srcList;
                msg.put("sources", imageUrlSigner.signSourceImages(typed));
            }
            // 产物卡片 URL 动态签名（下载/预览走静态资源鉴权；原始 URL 存库）
            Object arts = msg.get("artifacts");
            if (arts instanceof List<?> artList && !artList.isEmpty()
                    && artList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typedArts = (List<Map<String, Object>>) artList;
                for (Map<String, Object> a : typedArts) {
                    Object u = a.get("url");
                    if (u != null) a.put("url", imageUrlSigner.signUrl(String.valueOf(u)));
                }
            }
            Object mid = msg.get("messageId");
            if (mid != null) {
                Integer fb = ratings.get(String.valueOf(mid));
                if (fb != null) msg.put("fb", fb);
            }
        }
        return ResultJson.ok(history);
    }

    @Operation(summary = "删除会话", description = "删除指定会话（MySQL 软删除 + Redis 清理；校验会话归属）")
    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.deleteSession(RequestUser.uid(), sessionId);
        return ResultJson.ok("会话已删除");
    }

    @Operation(summary = "清空会话", description = "清空当前用户名下的全部会话数据")
    @DeleteMapping("/sessions")
    public ResultJson clearAllSessions(HttpServletRequest httpRequest) {
        sessionService.clearAll(RequestUser.uid());
        return ResultJson.ok("会话已清空");
    }

    @Operation(summary = "批量删除会话", description = "按 ID 列表软删除多个会话（逐个校验归属，单条失败不中断）；返回成功删除数")
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

    @Operation(summary = "新建会话", description = "创建一个新的对话会话（归属当前用户），返回会话 ID")
    @PostMapping("/session/new")
    public ResultJson newSession(HttpServletRequest httpRequest) {
        return ResultJson.ok(Map.of("sessionId", sessionService.createSession(RequestUser.uid())));
    }

    @Operation(summary = "知识块详情", description = "获取指定知识块的全文内容（引用溯源：弹窗展示来源知识块全文与图片）")
    @GetMapping("/knowledge/{knowledgeId}")
    public ResultJson knowledgeDetail(
            @Parameter(description = "知识块 ID") @PathVariable("knowledgeId") String knowledgeId) {
        Knowledge k = knowledgeMapper.selectById(knowledgeId);
        if (k == null) {
            return ResultJson.error(404, "知识块不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("docId", k.getDocId());
        m.put("title", k.getTitle());
        m.put("titlePath", k.getTitlePath());
        m.put("content", k.getContent());
        m.put("chunkIndex", k.getChunkIndex());
        // 知识块原文内嵌图片：原始 URL 存库，响应时动态签名（否则引用弹窗图片 401）
        List<String> kImgs = (k.getImages() == null || k.getImages().isBlank())
                ? List.of() : JSON.parseArray(k.getImages(), String.class);
        m.put("images", imageUrlSigner.signUrls(kImgs));
        return ResultJson.ok(m);
    }

    /** 推荐问题池单条上限（与欢迎页展示数量一致，配置里超出的行忽略） */
    private static final int SUGGESTED_MAX = 8;

    @Operation(summary = "删除一轮对话", description = "按对话组删除：指定该轮回答（assistant 消息）ID，连同其前面的用户问题一起软删除，并清理 Redis 兜底缓存。立即生效，前端 5 秒内可调撤销接口恢复")
    @DeleteMapping("/message-group/{assistantMessageId}")
    public ResultJson deleteMessageGroup(
            @Parameter(description = "该轮回答（assistant 消息）ID") @PathVariable("assistantMessageId") String assistantMessageId,
            HttpServletRequest httpRequest) {
        com.wisesoft.ai.model.Message assistant = messageMapper.selectById(assistantMessageId);
        if (assistant == null) throw new BizException(404, "消息不存在");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int deleted = sessionService.deleteRound(assistant.getSessionId(), assistantMessageId);
        if (deleted == 0) throw new BizException(404, "消息不存在或已删除");
        return ResultJson.ok("已删除该轮对话");
    }

    @Operation(summary = "撤销删除一轮对话", description = "恢复最近一次按组删除的对话（回答 + 同组用户问题），撤销期内有效")
    @PostMapping("/message-group/undo")
    public ResultJson undoDeleteMessageGroup(
            @Parameter(description = "{\"messageId\": \"该轮回答（assistant 消息）ID\"}")
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String messageId = body.get("messageId");
        if (messageId == null || messageId.isBlank()) throw new BizException("缺少 messageId");
        // 已软删消息的归属校验：忽略删除标记取回，会话本身仍须存在且属于当前用户
        com.wisesoft.ai.model.Message assistant = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (assistant == null) throw new BizException(404, "消息不存在或已过撤销期");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int restored = sessionService.undoDeleteRound(assistant.getSessionId(), messageId);
        if (restored == 0) throw new BizException(410, "已过撤销期，无法恢复");
        return ResultJson.ok(Map.of("restored", restored), "已恢复该轮对话");
    }

    @Operation(summary = "推荐问题列表", description = "获取欢迎页展示的推荐问题（DB 配置 chat.suggestedQuestions，每行一条，最多 8 条）")
    @GetMapping("/suggested")
    public ResultJson suggested() {
        return ResultJson.ok(parseSuggested(configService.get("chat.suggestedQuestions")));
    }

    @Operation(summary = "加入推荐问题", description = "向推荐问题池追加一条问题（去重、超出 8 条时挤掉最早的），数据看板热门问题一键加入用")
    @PostMapping("/suggested")
    public ResultJson addSuggested(
            @Parameter(description = "{\"question\": \"问题文本\"}") @RequestBody Map<String, String> body) {
        String q = body.getOrDefault("question", "").trim();
        if (q.isEmpty()) throw new BizException("问题不能为空");
        if (q.length() > 100) throw new BizException("问题过长（最多100字）");
        List<String> list = new java.util.ArrayList<>(parseSuggested(configService.get("chat.suggestedQuestions")));
        list.removeIf(s -> s.equals(q));
        list.add(0, q);
        while (list.size() > SUGGESTED_MAX) list.remove(list.size() - 1);
        configService.update(Map.of("chat", Map.of("suggestedQuestions", String.join("\n", list))));
        return ResultJson.ok(list, "已加入推荐");
    }

    /** 推荐问题配置解析：按行拆分、去空白、截前 8 条 */
    private List<String> parseSuggested(String config) {
        if (config == null || config.isBlank()) return List.of();
        return java.util.Arrays.stream(config.split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .limit(SUGGESTED_MAX).toList();
    }

    /** 客户端真实 IP（nginx 反代场景取 X-Forwarded-For 首段，兜底 remoteAddr） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}