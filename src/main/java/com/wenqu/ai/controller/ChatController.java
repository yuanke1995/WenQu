package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.dto.SessionInfo;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.RagService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.AuthService;
import com.wenqu.ai.util.RequestUser;
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
    private final com.wenqu.ai.mapper.MessageMapper messageMapper;
    private final ConfigService configService;
    private final RateLimitService rateLimitService;
    private final QaLogService qaLogService;
    private final AdminGuard adminGuard;
    private final AuthService authService;
    private final com.wenqu.ai.service.MenuService menuService;
    private final com.wenqu.ai.service.ModelRegistryService modelRegistryService;
    private final com.wenqu.ai.service.ResourceVisibilityService visibility;
    private final com.wenqu.ai.service.KnowledgeBaseService kbService;
    private final com.wenqu.ai.service.AgentService agentService;
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;
    private final com.wenqu.ai.service.ChatUploadService chatUploadService;
    private final com.wenqu.ai.service.SessionShareService sessionShareService;

    @Operation(summary = "上传聊天附件", description = "把聊天附件先上传换 fileId（问答请求体只带 fileId，不再内联 base64）；"
            + "文件按用户隔离落盘、超期自动清理；按用户限频（ratelimit.uploadPerMinute）")
    @ApiResponse(responseCode = "200", description = "返回 {fileId,name,mime,size}")
    @PostMapping("/chat/attachment")
    public ResultJson uploadAttachment(
            @Parameter(description = "附件文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        rateLimitService.checkRateLimit("upload", RequestUser.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);
        return ResultJson.ok(chatUploadService.save(file, userId), "上传完成");
    }

    /**
     * 当前身份与权限（普通用户问答 UI 据此隐藏/显示管理入口；白名单端点，无需管理员即可调用）。
     * 2026-09-26 RBAC：响应追加 {@code menus}（当前角色可见的侧边栏菜单树，前端动态渲染导航）；
     * {@code admin} 按管理员级角色判定（含自定义 admin_flag=1 角色）。
     */
    @Operation(summary = "当前身份与权限", description = "返回当前登录用户（uid/username/role/departmentId）、是否管理员（admin）"
            + "与可见菜单树（menus，按角色绑定下发，前端侧边栏数据源）")
    @GetMapping("/auth/me")
    public ResultJson authMe(HttpServletRequest httpRequest) {
        Map<String, Object> me = authService.currentUser(RequestUser.uid(), adminGuard.isAdmin(httpRequest));
        me.put("menus", menuService.menusForUser(RequestUser.uid(), RequestUser.role()));
        return ResultJson.ok(me);
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

        // 附件：请求体只带 fileId（内容已先行上传落盘换号）。
        // 数量上限 + 存在性与归属校验（拿别人的 fileId 一律拒绝）；类型与体积在上传接口已把关，
        // 这里不再按 base64 字符量估算——口径的变化正是这次改造的目的（body 从 ~100MB 降到几十字节）
        List<ChatRequest.Attachment> attachments = request.getAttachments();
        if (attachments != null && !attachments.isEmpty()) {
            int maxAtt = Math.max(1, configService.getInt("chat.maxAttachmentsPerMessage", 5));
            if (attachments.size() > maxAtt) {
                throw new BizException("一次最多上传 " + maxAtt + " 个附件");
            }
            for (ChatRequest.Attachment att : attachments) {
                if (att == null || att.getFileId() == null || att.getFileId().isBlank()) {
                    throw new BizException("附件信息不完整（缺少上传标识，请重新上传）");
                }
                var meta = chatUploadService.stat(att.getFileId(), userId);
                if (meta == null) {
                    throw new BizException("附件不存在或已过期，请重新上传");
                }
                // 名称/MIME 以服务端元信息为准：客户端可随意伪造展示名，解析与展示都该信落盘时的记录
                att.setName(meta.name());
                att.setMime(meta.mime());
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

        // 模型引用归属校验：普通用户只能用自己可用的供应商（平台级 + 自己登记的个人级）。
        // 放在发起问答之前 fail-loud——否则引用他人个人级供应商时会拿对方的 Key 跑通，事后无从发现。
        modelRegistryService.assertUsable(request.getModel(), userId, RequestUser.role());

        // 会话级模型覆盖落库（聊天页手动切换即存，刷新/换端后选择不丢）：只有请求显式携带才写——
        // 清除选择（跟随个人默认）由 PUT /session/{id}/model 在切换时处理；游客/定时任务/工作流等
        // 旁路调用不经过这里，不会把各自的配置模型误存成会话覆盖。引用须可解析：存进会话的值若失效，
        // 后续每轮都会在回答期报错（与上面 PUT 端点同口径）
        if (request.getModel() != null && !request.getModel().isBlank()) {
            String modelRef = request.getModel().trim();
            if (modelRef.contains("/") && modelRegistryService.resolveReference(modelRef) == null) {
                throw new BizException("模型引用无效或已被删除，请重新选择");
            }
            sessionService.updateModel(userId, sessionId, modelRef);
        }

        // @ 引用校验（同样必须在发起问答前 fail-loud）：类型合法 + 资源存在 + 在当前用户共享范围内。
        // 放在这里而不是流水线内：流水线是异步线程，异常只能变成 SSE error，用户拿不到明确的 400 语义
        List<ChatRequest.Mention> mentions = validateMentions(request.getMentions(), httpRequest);

        // # 历史引用校验（同上，同步段 fail-loud）：messageId 必须属于当前会话，内容由服务端查库回填
        List<ChatRequest.HistoryRef> historyRefs = validateHistoryRefs(sessionId, request.getHistoryRefs());

        // 编辑重发（消息分支）：被编辑的用户消息在此校验并完成分支手术（旧分支软删留档+快照，同步段 fail-loud，
        // 异常以明确的 4xx 语义返回而不是流中报错）；返回的组键贯穿本轮，新用户消息落库后挂同一组
        String editVariantGroup = null;
        if (request.getEditMessageId() != null && !request.getEditMessageId().isBlank()) {
            String editId = request.getEditMessageId().trim();
            if (!editId.matches("[0-9a-fA-F]{32}")) {
                throw new BizException("被编辑消息标识无效");
            }
            editVariantGroup = sessionService.prepareEditBranch(sessionId, editId);
        }

        // 不设容器级 SSE 超时：那是一条从请求开始一路走到头的墙钟，且 async 启动后不可续期——
        // 人在回路的提问卡/审批等待会被算进去，用户在第二张卡上选完答案整轮已被掐断。
        // 整轮截断改由 RagService 的存活看门狗按「机器耗时」判定（chat.sseTimeoutMs，人工等待不计入）。
        SseEmitter emitter = new SseEmitter(0L);
        // regenerate：重新生成/自动重试的重发——该问题的用户消息已随上一轮请求即时落库，跳过重复落库
        ragService.chat(sessionId, question, images, attachments, request.getSkills(), mentions,
                request.isDeepThink(), request.getAgentId(), request.getModel(), userId, emitter,
                false, request.isRegenerate(), request.getReplaceMessageId(), historyRefs,
                request.getReasoningLevel(), request.getContextWindow(), editVariantGroup,
                request.isPlanMode());
        return emitter;
    }

    /**
     * @ 引用校验（fail-loud）：类型合法、资源存在、当前用户在共享范围内。
     * 不可见按「不存在」处理（不泄露存在性，与文档接口同一口径）；展示名以库里实名为准，不信任客户端传值。
     * 返回归一后的引用列表（null 表示无引用）。
     */
    private List<ChatRequest.Mention> validateMentions(List<ChatRequest.Mention> mentions,
                                                       HttpServletRequest httpRequest) {
        if (mentions == null || mentions.isEmpty()) return null;
        int max = Math.max(1, configService.getInt("chat.maxMentionsPerMessage", 10));
        if (mentions.size() > max) {
            throw new BizException("一次最多引用 " + max + " 个知识库/文档/智能体");
        }
        boolean admin = adminGuard.isAdmin(httpRequest);
        var kind = com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        var principal = new com.wenqu.ai.service.ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
        List<ChatRequest.Mention> out = new java.util.ArrayList<>();
        for (ChatRequest.Mention m : mentions) {
            if (m == null) continue;
            String type = m.getType() == null ? "" : m.getType().trim().toLowerCase();
            String id = m.getId() == null ? "" : m.getId().trim();
            if (id.isEmpty()) {
                throw new BizException("引用项缺少资源 ID");
            }
            ChatRequest.Mention normalized = new ChatRequest.Mention();
            if ("kb".equals(type)) {
                var kb = kbService.get(id);
                if (kb == null || (!admin
                        && !visibility.canRead(principal, kb.getShareConfig(), kb.getCreatedBy(), kind))) {
                    throw new BizException("引用的知识库不存在或无权访问");
                }
                normalized.setType("kb");
                normalized.setId(kb.getId());
                normalized.setName(kb.getName());
            } else if ("doc".equals(type)) {
                var doc = documentMapper.selectById(id);
                if (doc == null || (!admin
                        && !visibility.canReadDocFollowKb(principal, doc.getShareConfig(), doc.getCreatedBy()))) {
                    throw new BizException("引用的文档不存在或无权访问");
                }
                // 文档所属库同样须可见（与文档列表接口的两级过滤一致）
                if (!admin && doc.getKbId() != null) {
                    var kb = kbService.get(doc.getKbId());
                    if (kb == null || !visibility.canRead(principal, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                        throw new BizException("引用的文档不存在或无权访问");
                    }
                }
                normalized.setType("doc");
                normalized.setId(doc.getId());
                normalized.setName(doc.getFileName());
                normalized.setKbId(doc.getKbId() == null ? kbService.defaultId(RequestUser.uid()) : doc.getKbId());
            } else if ("agent".equals(type)) {
                // @ 智能体（轮级委派）：get() 内含可读校验（共享范围外按不存在处理，不泄露存在性）。
                // 子智能体是主智能体的内部检索角色，不作为问答角色直接提及——与对话页下拉同一口径
                var a = agentService.get(id);
                if (a == null) {
                    throw new BizException("提及的智能体不存在或无权访问");
                }
                if (Integer.valueOf(1).equals(a.getIsSubagent())) {
                    throw new BizException("子智能体不能被 @ 提及（它只能被主智能体委派调用）");
                }
                normalized.setType("agent");
                normalized.setId(a.getId());
                normalized.setName(a.getName());
            } else {
                throw new BizException("不支持的引用类型：" + m.getType() + "（仅支持 kb / doc / agent）");
            }
            out.add(normalized);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * # 历史引用校验（fail-loud，与会话归属同一同步段）：messageId 必填、必须属于当前会话。
     * 角色与内容一律按库回填，客户端传值不采信（与会话内消息同一份事实源）。
     * 返回归一后的引用列表（null 表示无引用）。
     */
    private List<ChatRequest.HistoryRef> validateHistoryRefs(String sessionId,
                                                             List<ChatRequest.HistoryRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        int max = Math.max(1, configService.getInt("chat.maxHistoryRefsPerMessage", 10));
        if (refs.size() > max) {
            throw new BizException("一次最多引用 " + max + " 条历史消息");
        }
        List<ChatRequest.HistoryRef> out = new java.util.ArrayList<>();
        for (ChatRequest.HistoryRef r : refs) {
            if (r == null) continue;
            String mid = r.getMessageId() == null ? "" : r.getMessageId().trim();
            if (mid.isEmpty()) {
                throw new BizException("历史引用缺少消息 ID");
            }
            var msg = sessionService.findMessageInSession(sessionId, mid);
            if (msg == null) {
                throw new BizException("引用的历史消息不存在或不属于当前会话");
            }
            ChatRequest.HistoryRef normalized = new ChatRequest.HistoryRef();
            normalized.setMessageId(msg.getId());
            normalized.setRole(msg.getRole());
            normalized.setContent(msg.getContent() == null ? "" : msg.getContent());
            out.add(normalized);
        }
        return out.isEmpty() ? null : out;
    }

    @Operation(summary = "工具执行审批", description = "裁决智能体的工具执行请求（approval_required 事件下发）：仅本轮用户本人可批，"
            + "拒绝/超时后模型会收到未执行错误并继续回答；审批挂起为内存态，刷新页面即失效")
    @PostMapping("/tool-approval/{approvalId}")
    public ResultJson resolveToolApproval(
            @Parameter(description = "审批请求 ID（approval_required 事件下发）") @PathVariable("approvalId") String approvalId,
            @RequestBody Map<String, Boolean> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        boolean ok = ragService.resolveApproval(approvalId, approved, com.wenqu.ai.util.RequestUser.uid());
        if (!ok) return ResultJson.error("审批请求不存在或已失效（可能已超时）");
        return ResultJson.ok(approved ? "已批准" : "已拒绝");
    }

    @Operation(summary = "工具审批记录（恢复）", description = "按审批 ID 取本人的审批记录（tool.approval 通知点击后重建审批卡用）；" +
            "不存在/非本人返回 null。内存态可能已失效（进程重启/超时），此时记录状态为终态，卡片提示已处理。")
    @GetMapping("/tool-approval/{approvalId}")
    public ResultJson getToolApproval(
            @Parameter(description = "审批请求 ID") @PathVariable("approvalId") String approvalId) {
        return ResultJson.ok(ragService.getApproval(approvalId, com.wenqu.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "回答智能体提问", description = "回答智能体的结构化提问（ask_user 事件下发，人在回路）：仅本轮用户本人可答，"
            + "答案作为工具结果回给模型继续本轮回答。提问卡持久化在 c_ai_tool_approval（PENDING→终态），"
            + "等待期间客户端断开不中止本轮，故刷新页面/换设备后仍可经 /ask-user/pending 取回卡片作答。"
            + "body 传 {answers:[...]} 为作答（与问题下标对齐，兼容旧式单答案 {answer}）；传 {ignore:true} 为忽略（不作答）。"
            + "超时与忽略都不替用户选答案。返回 false 表示这次作答没能送达（已收尾，或唤醒句柄随进程重启消失）。")
    @PostMapping("/ask-user/{askId}")
    public ResultJson answerAgentAsk(
            @Parameter(description = "提问 ID（ask_user 事件下发）") @PathVariable("askId") String askId,
            @RequestBody Map<String, Object> body) {
        boolean ignore = Boolean.TRUE.equals(body.get("ignore"));
        String uid = com.wenqu.ai.util.RequestUser.uid();
        boolean ok;
        if (ignore) {
            ok = ragService.resolveAskIgnore(askId, uid);
        } else {
            // 一卡多问：answers 为与问题下标对齐的答案数组；兼容旧式单答案 {answer}
            java.util.List<String> answers = new java.util.ArrayList<>();
            Object raw = body.get("answers");
            if (raw instanceof java.util.List<?> list) {
                for (Object x : list) if (x != null) answers.add(String.valueOf(x));
            } else if (body.get("answer") != null) {
                answers.add(String.valueOf(body.get("answer")));
            }
            ok = ragService.resolveAsk(askId, answers, uid);
        }
        if (!ok) return ResultJson.error("提问已结束，这次作答没能送达智能体");
        return ResultJson.ok(ignore ? "已忽略，智能体将自行判断" : "已提交");
    }

    @Operation(summary = "待答提问（恢复）", description = "按会话列本人仍挂在 PENDING 的 askUser 提问卡，"
            + "供前端在会话加载/切换与点开 tool.ask 通知时重建卡片。返回 items：{askId,questions,timeoutMs,"
            + "remainingMs,createdAt,expired,live}；live=false 表示唤醒句柄已不在（进程重启），作答送不到模型。")
    @GetMapping("/ask-user/pending")
    public ResultJson listPendingAsks(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(java.util.Map.of("items",
                ragService.listPendingAsks(sessionId, com.wenqu.ai.util.RequestUser.uid())));
    }

    @Operation(summary = "执行计划裁决（计划模式）", description = "批准/拒绝 plan_approval 事件下发的执行计划（人在回路）："
            + "批准可带编辑后的计划全文（body {approved:true,plan:\"...\"}，plan 空串=按模型原稿执行），"
            + "拒绝（approved:false）或超时=本轮终止。仅本轮用户本人可裁决；记录持久化在 c_ai_tool_approval"
            + "（tool_name=planApproval，PENDING→终态），等待期间断开不中止本轮，刷新/换设备后仍可经 "
            + "/plan-approval/pending 取回卡片继续裁决。")
    @PostMapping("/plan-approval/{planApprovalId}")
    public ResultJson resolvePlanApproval(
            @Parameter(description = "计划批准 ID（plan_approval 事件下发）") @PathVariable("planApprovalId") String planApprovalId,
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        Object p = body.get("plan");
        String plan = p == null ? null : String.valueOf(p);
        boolean ok = ragService.resolvePlanApproval(planApprovalId, approved, plan, com.wenqu.ai.util.RequestUser.uid());
        if (!ok) return ResultJson.error("计划已处理或已失效（可能已超时）");
        return ResultJson.ok(approved ? "已批准，按计划执行" : "已取消本轮");
    }

    @Operation(summary = "待批准计划（恢复）", description = "按会话列本人仍挂在 PENDING 的执行计划批准卡，"
            + "供前端在会话加载/切换与点开 tool.approval 通知时重建卡片。返回 items："
            + "{planApprovalId,plan,timeoutMs,remainingMs,createdAt,expired,live}；live=false 表示唤醒句柄已不在"
            + "（进程重启），批准送不到模型。")
    @GetMapping("/plan-approval/pending")
    public ResultJson listPendingPlans(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(java.util.Map.of("items",
                ragService.listPendingPlans(sessionId, com.wenqu.ai.util.RequestUser.uid())));
    }

    @Operation(summary = "会话列表", description = "游标分页列出当前用户的会话（含 anonymous 历史兼容池；置顶优先、按更新时间倒序）。"
            + "首页不传 cursor，后续页传上一页返回的 nextCursor；keyword 按标题或消息内容模糊搜索（分页同样生效）。"
            + "返回 items / nextCursor / hasMore / groupCounts（置顶/今天/7天内/更早分组总数，仅统计有消息的会话） / total")
    @GetMapping("/sessions")
    public ResultJson listSessions(
            @Parameter(description = "搜索关键词（可选，按标题/消息内容模糊匹配）")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "分页游标（首页不传；后续页传上一页返回的 nextCursor）")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "每页条数（默认 10，上限 100）")
            @RequestParam(value = "size", required = false, defaultValue = "10") int size,
            HttpServletRequest httpRequest) {
        return ResultJson.ok(sessionService.listSessions(RequestUser.uid(), keyword, cursor, size));
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

    @Operation(summary = "读取会话模型",
            description = "返回该会话已存的模型覆盖（空串=跟随个人默认）。聊天页切到列表未加载的会话"
                    + "（通知/分享深链进老会话）时按需拉取，保证选择器显示与实际作答模型一致")
    @GetMapping("/session/{sessionId}/model")
    public ResultJson getSessionModel(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        return ResultJson.ok(Map.of("model", sessionService.sessionModelOf(sessionId)));
    }

    @Operation(summary = "切换会话模型",
            description = "设置会话级模型覆盖（聊天页选择器切换即存，刷新/换端/重新生成后仍保留）；"
                    + "model 传空串 = 清除覆盖，回到跟随个人默认。校验会话归属与模型可用性")
    @PutMapping("/session/{sessionId}/model")
    public ResultJson setSessionModel(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        String model = body == null || body.get("model") == null ? "" : String.valueOf(body.get("model")).trim();
        if (!model.isBlank()) {
            // 与 /chat 同口径的 fail-loud：普通用户只能存自己可用的模型；引用须在模型库可解析
            // （否则存进会话后每轮回答期才报错，用户无从定位）。遗留裸模型名不做解析校验
            modelRegistryService.assertUsable(model, userId, RequestUser.role());
            if (model.contains("/") && modelRegistryService.resolveReference(model) == null) {
                throw new BizException("模型引用无效或已被删除，请重新选择");
            }
        }
        sessionService.updateModel(userId, sessionId, model);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "会话历史", description = "获取指定会话的完整对话历史（含图片与引用来源；校验会话归属）")
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

    @Operation(summary = "手动压缩会话上下文",
            description = "把较早的对话压缩成摘要（前端 /compact 斜杠命令）：除最近 2 轮原样保留外，其余全部并入会话摘要，"
                    + "为后续提问腾出上下文空间；可带 instruction 指定这次摘要要保留什么。摘要调用按 chat 桶限频并计入用量台账。"
                    + "返回 {compressedTurns, compressedMessages, keepRecent, summaryTokens, summary, rounds, partial}；"
                    + "compressedTurns=0 表示对话还短、无需压缩")
    @ApiResponse(responseCode = "200", description = "压缩结果统计（含摘要全文，供前端展示）")
    @PostMapping("/session/{sessionId}/compact")
    public ResultJson compactSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        sessionService.assertOwned(sessionId, userId);
        // 摘要要花一次（长会话是数次）模型调用：并入 chat 桶限频，防连点刷额度
        rateLimitService.checkRateLimit("chat", RequestUser.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);
        Object modelArg = body == null ? null : body.get("model");
        Object instrArg = body == null ? null : body.get("instruction");
        // 请求未显式指定时回落会话已存覆盖（刷新后本地选择清空也能压对模型）。
        // 模型解析顺序与会话当轮一致（请求指定 > 会话已存覆盖 > 个人默认）；都没有时 fail-loud 引导配置，
        // 而不是发空 model 到网关
        String requestedModel = modelArg == null ? null : String.valueOf(modelArg);
        if (requestedModel == null || requestedModel.isBlank()) {
            requestedModel = sessionService.sessionModelOf(sessionId);
        }
        String resolvedModel = ragService.resolveChatModel(userId, requestedModel);
        if (resolvedModel.isBlank()) {
            throw new BizException("未指定模型：请先在对话中选择模型，或在个人设置中配置默认模型");
        }
        String instruction = instrArg == null ? null : String.valueOf(instrArg);
        return ResultJson.ok(ragService.compactSession(sessionId, resolvedModel, instruction), "压缩完成");
    }

    @Operation(summary = "删除会话", description = "删除指定会话（MySQL 软删除 + Redis 清理；校验会话归属）")
    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.deleteSession(RequestUser.uid(), sessionId);
        return ResultJson.ok("会话已删除");
    }

    @Operation(summary = "清空会话", description = "清空当前账号名下的全部会话数据")
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

    @Operation(summary = "新建会话", description = "取一个空白会话供「新建对话」使用：优先复用当前用户已有的空会话（空会话恒为 1 条），没有才新建；返回会话 ID")
    @PostMapping("/session/new")
    public ResultJson newSession(HttpServletRequest httpRequest) {
        return ResultJson.ok(Map.of("sessionId", sessionService.createOrReuseEmptySession(RequestUser.uid())));
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

    /**
     * 我分享过的会话列表（个人设置 → 分享管理）。
     *
     * <p>与 {@code /session/{id}/share} 的区别是那是"逐个会话单查"，这里枚举全部——
     * 分享出去的链接一旦发出就散在各处，用户需要一个能回看"哪些还在开着、被访问过几次"的入口。
     *
     * <p>返回项含 {@code orphaned}：会话已被删除时仍列出该行（enabled 强制呈现为 false），
     * 否则用户会以为"没分享过"，而实际是链接散出去了但自己已经无从关闭。
     */
    @Operation(summary = "我的会话分享列表", description = "枚举当前用户分享过的全部会话，含状态、访问量与悬空标记")
    @GetMapping("/session-shares")
    public ResultJson listMySessionShares() {
        return ResultJson.ok(sessionShareService.listMine(RequestUser.uid()));
    }

    /**
     * 按会话停用分享（分享管理页用）。
     *
     * <p>刻意不复用 {@code /session/{id}/share} 的 DELETE：那个端点先 assertOwned，
     * 会话已删除时直接 404，而这恰恰是分享管理页最需要能操作的场景（悬空行只能停、不能再开）。
     * 这里按 created_by 鉴权——分享记录本身就是归属凭据。
     */
    @Operation(summary = "按会话停用分享", description = "分享管理页停用；会话已删除的悬空记录同样可停")
    @DeleteMapping("/session-shares/{sessionId}")
    public ResultJson stopShareBySession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        String uid = RequestUser.uid();
        var share = sessionShareService.get(sessionId);
        // 先查后改：不存在或非本人分享都按 404（不泄露他人会话是否存在分享记录）
        if (share == null || !uid.equals(share.getCreatedBy())) {
            throw new BizException(404, "分享记录不存在");
        }
        sessionShareService.disable(sessionId);
        return ResultJson.ok("已停止分享");
    }

    /**
     * 彻底清除一条分享记录（与"停止分享"分开：那个让链接失效、这个让记录消失）。
     *
     * <p>只接受已停用的记录——生效中的必须先停止，否则用户会在"链接还开着"的同时
     * 把记录删掉，既失去感知也让访问量断档。会话已删除的悬空记录天然是停用态，
     * 这是它们唯一的清理出口。
     */
    @Operation(summary = "清除分享记录", description = "物理删除一条**已停止**的分享记录；生效中的返回 400，须先停止分享")
    @DeleteMapping("/session-shares/{sessionId}/record")
    public ResultJson purgeShareRecord(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        // 归属校验先做（分享记录是归属凭据），再由 service 判停用态
        var share = sessionShareService.get(sessionId);
        if (share == null || !RequestUser.uid().equals(share.getCreatedBy())) {
            throw new BizException(404, "分享记录不存在");
        }
        if (share.getEnabled() != null && share.getEnabled() == 1) {
            throw new BizException(400, "该链接仍在生效中，请先停止分享再清除记录");
        }
        sessionShareService.purge(sessionId, RequestUser.uid());
        return ResultJson.ok("记录已清除");
    }

    @Operation(summary = "查询会话分享状态", description = "返回 {enabled, token, visitCount, lastVisitAt}；未分享返回 enabled=false。仅会话所有者可查")
    @GetMapping("/session/{sessionId}/share")
    public ResultJson getSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        var s = sessionShareService.get(sessionId);
        boolean on = s != null && s.getEnabled() != null && s.getEnabled() == 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", on);
        out.put("token", on ? s.getToken() : "");
        out.put("visitCount", s == null || s.getVisitCount() == null ? 0 : s.getVisitCount());
        out.put("lastVisitAt", s == null ? null : s.getLastVisitAt());
        return ResultJson.ok(out);
    }

    @Operation(summary = "开启会话分享", description = "生成（或重新生成）只读分享链接；停止后重新开启会换新 token，"
            + "旧链接立即失效。仅会话所有者可操作")
    @PostMapping("/session/{sessionId}/share")
    public ResultJson enableSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        var s = sessionShareService.enable(sessionId, RequestUser.uid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("token", s.getToken());
        return ResultJson.ok(out, "分享链接已生成");
    }

    @Operation(summary = "停止会话分享", description = "停用后链接立即失效（记录保留，再次开启会换新 token）")
    @DeleteMapping("/session/{sessionId}/share")
    public ResultJson disableSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        sessionShareService.disable(sessionId);
        return ResultJson.ok("已停止分享");
    }

    @Operation(summary = "删除一轮对话", description = "按对话组删除：指定该轮回答（assistant 消息）ID，连同其前面的用户问题一起软删除，并清理 Redis 兜底缓存。立即生效，前端 5 秒内可调撤销接口恢复")
    @DeleteMapping("/message-group/{assistantMessageId}")
    public ResultJson deleteMessageGroup(
            @Parameter(description = "该轮回答（assistant 消息）ID") @PathVariable("assistantMessageId") String assistantMessageId,
            HttpServletRequest httpRequest) {
        com.wenqu.ai.model.Message assistant = messageMapper.selectById(assistantMessageId);
        if (assistant == null) throw new BizException(404, "消息不存在");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int deleted = sessionService.deleteRound(assistant.getSessionId(), assistantMessageId);
        if (deleted == 0) throw new BizException(404, "消息不存在或已删除");
        return ResultJson.ok("已删除该轮对话");
    }

    @Operation(summary = "切换消息分支版本", description = "编辑重发/重新生成的多版本切换：messageId 为当前可见版本的"
            + "代表消息 ID（编辑分支=用户消息；重新生成分支=回答），delta=±1 沿版本序列偏移。当前分支整体软删留档、"
            + "目标版本按快照恢复；旧版本数据已被保留期清理时返回 410")
    @PostMapping("/message-group/switch")
    public ResultJson switchMessageVariant(
            @Parameter(description = "{\"messageId\": \"当前版本的消息 ID\", \"delta\": -1 或 1}")
            @RequestBody Map<String, Object> body) {
        String messageId = body.get("messageId") == null ? "" : String.valueOf(body.get("messageId")).trim();
        if (messageId.isEmpty()) throw new BizException("缺少 messageId");
        int delta;
        Object d = body.get("delta");
        if (d instanceof Number n) {
            delta = n.intValue();
        } else {
            try {
                delta = Integer.parseInt(String.valueOf(d));
            } catch (Exception e) {
                throw new BizException("delta 必须是 -1 或 1");
            }
        }
        if (delta != -1 && delta != 1) throw new BizException("delta 必须是 -1 或 1");
        // 目标可能是已软删的历史版本代表，先忽略删除标记定位，会话归属照常严格校验
        com.wenqu.ai.model.Message m = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (m == null) throw new BizException(404, "消息不存在");
        sessionService.assertOwned(m.getSessionId(), RequestUser.uid());
        int restored = sessionService.switchVariant(m.getSessionId(), messageId, delta);
        return ResultJson.ok(Map.of("restored", restored), restored > 0 ? "已切换版本" : "已是该版本");
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
        com.wenqu.ai.model.Message assistant = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (assistant == null) throw new BizException(404, "消息不存在或已过撤销期");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int restored = sessionService.undoDeleteRound(assistant.getSessionId(), messageId);
        if (restored == 0) throw new BizException(410, "已过撤销期，无法恢复");
        return ResultJson.ok(Map.of("restored", restored), "已恢复该轮对话");
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