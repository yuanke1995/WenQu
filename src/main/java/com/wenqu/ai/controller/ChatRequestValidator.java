package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.config.ConfigKeys;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ChatUploadService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.ResourceVisibilityService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答请求的入参校验（从 ChatController 抽出）。
 * <p>这四条校验原本是 {@code ChatController} 的私有方法，被「发问 / 插话 / 插话排队 / 接流」
 * 四个入口共用。留在控制器里意味着任何一个控制器要发问就得同时持有
 * upload / kb / agent / document / visibility / config / session 这一串依赖——
 * 控制器因此涨到 30 多个字段。抽成独立协作者后，入口类只依赖「校验器 + 本轮运行时」两件事。
 * <p>全部是同步段校验、失败即抛 {@link BizException}（fail-loud），不做任何 IO 写操作。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class ChatRequestValidator {

    private final ConfigService configService;
    private final ChatUploadService chatUploadService;
    private final KnowledgeBaseService kbService;
    private final AgentService agentService;
    private final AiDocumentMapper documentMapper;
    private final ResourceVisibilityService visibility;
    private final AdminGuard adminGuard;
    private final ProjectService projectService;
    private final SessionService sessionService;

    /**
     * API Key 只开放问答链路：会话一旦绑定本机工作区，工具面就能读写/执行用户电脑上的文件与命令，
     * 这不是 Key 的设计能力。网页登录态不受影响；集成方请使用不绑定执行器的会话。
     */
    public void rejectApiKeyForWorkspaceSession(HttpServletRequest request, String sessionId) {
        if (request == null || sessionId == null || sessionId.isBlank()) return;
        if (request.getAttribute(com.wenqu.ai.config.SecurityConfig.ATTR_API_KEY_ID) == null) return;
        com.wenqu.ai.model.Project project = projectService.projectOfSession(sessionId);
        if (project != null && projectService.isDirect(project)) {
            throw new BizException("API Key 不开放绑定了本机工作区的会话：请在网页端使用，或改用不绑定执行器的会话");
        }
    }

    /**
     * @ 引用校验（fail-loud）：类型合法、资源存在、当前用户在共享范围内。
     * 不可见按「不存在」处理（不泄露存在性，与文档接口同一口径）；展示名以库里实名为准，不信任客户端传值。
     * 返回归一后的引用列表（null 表示无引用）。
     */
    public List<ChatRequest.Mention> validateMentions(List<ChatRequest.Mention> mentions,
                                                       HttpServletRequest httpRequest) {
        if (mentions == null || mentions.isEmpty()) return null;
        int max = Math.max(1, configService.getInt(ConfigKeys.CHAT_MAX_MENTIONS_PER_MESSAGE, 10));
        if (mentions.size() > max) {
            throw new BizException("一次最多引用 " + max + " 个知识库/文档/智能体");
        }
        boolean admin = adminGuard.isAdmin(httpRequest);
        var principal = new com.wenqu.ai.service.ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
        List<ChatRequest.Mention> out = new ArrayList<>();
        for (ChatRequest.Mention m : mentions) {
            if (m == null) continue;
            String type = m.getType() == null ? "" : m.getType().trim().toLowerCase();
            String id = m.getId() == null ? "" : m.getId().trim();
            if (id.isEmpty()) {
                throw new BizException("引用项缺少资源 ID");
            }
            ChatRequest.Mention normalized = switch (type) {
                case "kb" -> normalizeKb(id, principal, admin);
                case "doc" -> normalizeDoc(id, principal, admin);
                case "agent" -> normalizeAgent(id);
                default -> throw new BizException(
                        "不支持的引用类型：" + m.getType() + "（仅支持 kb / doc / agent）");
            };
            out.add(normalized);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 知识库引用：消费侧库门——官方内置库（手册）登录即可读，其余谁建归谁。
     * 管理员另有全量管理视角，不在这道门里放开。
     */
    private ChatRequest.Mention normalizeKb(String id,
                                            com.wenqu.ai.service.ResourceVisibilityService.Principal principal,
                                            boolean admin) {
        var kb = kbService.get(id);
        if (kb == null || (!admin
                && !visibility.canReadBuiltinKb(principal, kb.getBuiltin(), kb.getCreatedBy()))) {
            throw new BizException("引用的知识库不存在或无权访问");
        }
        ChatRequest.Mention normalized = new ChatRequest.Mention();
        normalized.setType("kb");
        normalized.setId(kb.getId());
        normalized.setName(kb.getName());
        return normalized;
    }

    /** 文档引用：文档恒跟随所属库，库可见即文档可见（与文档列表接口的过滤同一口径）。 */
    private ChatRequest.Mention normalizeDoc(String id,
                                             com.wenqu.ai.service.ResourceVisibilityService.Principal principal,
                                             boolean admin) {
        var doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException("引用的文档不存在或无权访问");
        }
        if (!admin && doc.getKbId() != null) {
            var kb = kbService.get(doc.getKbId());
            if (kb == null || !visibility.canReadBuiltinKb(principal, kb.getBuiltin(), kb.getCreatedBy())) {
                throw new BizException("引用的文档不存在或无权访问");
            }
        }
        ChatRequest.Mention normalized = new ChatRequest.Mention();
        normalized.setType("doc");
        normalized.setId(doc.getId());
        normalized.setName(doc.getFileName());
        normalized.setKbId(doc.getKbId() == null ? kbService.defaultId(RequestUser.uid()) : doc.getKbId());
        return normalized;
    }

    /**
     * @ 智能体引用（本轮强制咨询它）：{@code findReadable} 内含可读校验（共享范围外按不存在处理，
     * 不泄露存在性）。子智能体同样可以被 @——@ 不再是「换它来作答」而是「派它去查」。
     */
    private ChatRequest.Mention normalizeAgent(String id) {
        var a = agentService.findReadable(id);
        if (a == null) {
            throw new BizException("提及的智能体不存在或无权访问");
        }
        if (com.wenqu.ai.service.AgentService.isDisabled(a)) {
            throw new BizException("提及的智能体「" + a.getName() + "」已停用，请改提及其它智能体");
        }
        ChatRequest.Mention normalized = new ChatRequest.Mention();
        normalized.setType("agent");
        normalized.setId(a.getId());
        normalized.setName(a.getName());
        return normalized;
    }

    /**
     * # 历史引用校验（fail-loud，与会话归属同一同步段）：messageId 必填、必须属于当前会话。
     * 角色与内容一律按库回填，客户端传值不采信（与会话内消息同一份事实源）。
     * 返回归一后的引用列表（null 表示无引用）。
     */
    public List<ChatRequest.HistoryRef> validateHistoryRefs(String sessionId,
                                                             List<ChatRequest.HistoryRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        int max = Math.max(1, configService.getInt(ConfigKeys.CHAT_MAX_HISTORY_REFS_PER_MESSAGE, 10));
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


    public void validateImages(List<String> images) {
        if (images == null || images.isEmpty()) return;
        int maxImages = Math.max(1, ConfigDefaults.CHAT_MAX_IMAGES_PER_MESSAGE);
        if (images.size() > maxImages) {
            throw new BizException("一次最多发送 " + maxImages + " 张图片");
        }
        int maxImageMb = Math.max(1, ConfigDefaults.CHAT_MAX_IMAGE_MB);
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

    /**
     * 附件的同步段校验（发问与插话共用一份）：数量上限、fileId 存在且归属调用者，
     * 名称/MIME 一律以服务端落盘时的元信息为准——客户端可随意伪造展示名，解析与展示都该信记录。
     * <p>只对非空列表生效；返回同一个列表（已就地归一），空列表原样返回 null。
     */
    public List<ChatRequest.Attachment> normalizeAttachments(List<ChatRequest.Attachment> attachments, String userId) {
        if (attachments == null || attachments.isEmpty()) return attachments;
        int maxAtt = Math.max(1, configService.getInt(ConfigKeys.CHAT_MAX_ATTACHMENTS_PER_MESSAGE, 5));
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
            att.setName(meta.name());
            att.setMime(meta.mime());
        }
        return attachments;
    }
}
