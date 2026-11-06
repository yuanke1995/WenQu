package com.wenqu.ai.agentx.turn;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.UserImageService;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.util.UntrustedContent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 本轮送进模型之前的提示词/上下文装配：附件正文、@ 引用、# 历史引用、指定技能、多模态消息。
 * <p>纯装配，不碰轮次状态、SSE 或落库；代码从 {@code AgentscopeChatEngine} 原样搬出，
 * 改这里时保持提示词逐字不变。
 */
public final class TurnPromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(TurnPromptAssembler.class);

    private final ChatAttachmentService attachments;
    private final WenquSkillRepository skillRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;

    public TurnPromptAssembler(ChatAttachmentService attachments,
                               WenquSkillRepository skillRepository,
                               KnowledgeMapper knowledgeMapper,
                               AiDocumentMapper documentMapper) {
        this.attachments = attachments;
        this.skillRepository = skillRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 本轮「文本 + 原图」的多模态消息：正文一个 TextBlock，每张图一个 ImageBlock。
     * <p>只在模型能读图时走这条路——读不了图还硬塞 ImageBlock，上游要么报错要么静默丢图。
     * <p>图片用 {@code Base64Source} 直发原始字节：data URL 已经就是 base64，不必再落盘读回一遍。
     */
    public Msg buildMultimodalMsg(String text, List<UserImageService.UserImage> imgs) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(text).build());
        for (UserImageService.UserImage img : imgs) {
            String dataUrl = img.dataUrl();
            if (dataUrl == null || !dataUrl.startsWith("data:")) continue;
            int comma = dataUrl.indexOf(',');
            if (comma <= 0) continue;
            // data:<mediaType>;base64,<payload>
            String header = dataUrl.substring(5, comma);
            int semi = header.indexOf(';');
            String mediaType = semi < 0 ? header : header.substring(0, semi);
            if (mediaType.isBlank()) mediaType = "image/jpeg";
            String payload = dataUrl.substring(comma + 1);
            if (payload.isBlank()) continue;
            blocks.add(ImageBlock.builder()
                    .source(new Base64Source(mediaType, payload))
                    .build());
        }
        // 一张都没解析出来（data URL 形态异常）就退回纯文本：绝不 submit 一个只有 TextBlock 以外的空壳
        if (blocks.size() == 1) return null;
        return Msg.builderForRole(MsgRole.USER).content(blocks).build();
    }

    /** @ 引用 / # 历史引用的消息级常驻标注（刷新/历史回显保留）。 */
    public static String mentionsJson(List<ChatRequest.Mention> mentions) {
        if (mentions == null || mentions.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(mentions);
    }

    public static String historyRefsJson(List<ChatRequest.HistoryRef> historyRefs) {
        if (historyRefs == null || historyRefs.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(historyRefs);
    }

    /**
     * 本轮「指定技能」的正文注入。
     * <p>框架每轮会把全部可见技能的名称+描述注入系统提示，正文由模型按需取；用户在输入框点名
     * 某几把技能，意思是「这一轮就照这几条做」——不把正文送进上下文，点名等于没点。
     * <p>取不到必须写进正文而不是静默跳过：技能被删掉或改名时，让模型如实回答「这条技能当前不可用」。
     */
    private String pickedSkillsText(ChatCommand cmd, SkillFilter filter) {
        List<String> picked = cmd.getSkills();
        if (picked == null || picked.isEmpty()) return "";
        String uid = cmd.getUserId();
        if (uid == null || uid.isBlank()) return "";
        int maxChars = ConfigDefaults.SKILL_MAX_FILE_CHARS;
        RuntimeContext ctx = RuntimeContext.builder().userId(uid).sessionId(cmd.getSessionId()).build();
        StringBuilder sb = new StringBuilder();
        for (String raw : picked) {
            if (raw == null || raw.isBlank()) continue;
            // 智能体「指定」模式存的是引用串 {uid}/{name}（个人技能要精确到归属人），输入框点名给的是裸名
            String name = raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1) : raw;
            sb.append("\n\n【本轮指定技能：").append(name).append("】\n");
            // 智能体的「技能范围」是能力边界，不是建议：点名也不能越过它。
            if (filter != null && !filter.isAllowed(name)) {
                sb.append("（本智能体没有启用这条技能：它的「技能范围」未选中「").append(name)
                        .append("」。请如实告知用户，改用本智能体在用的做法作答，不要按这条技能的内容作答）");
                continue;
            }
            try {
                AgentSkill sk = skillRepository.getSkill(name, ctx);
                String body = sk == null ? null : sk.getSkillContent();
                if (body == null || body.isBlank()) {
                    sb.append("（这条技能当前对当前用户不可用：可能已被删除或改名。请如实告知用户，不要凭记忆代答）");
                } else {
                    sb.append(body.length() > maxChars ? body.substring(0, maxChars) + "\n…（正文超长已截断）" : body);
                }
            } catch (RuntimeException e) {
                log.warn("[SKILL] 读取指定技能失败 name={}: {}", name, e.toString());
                sb.append("（这条技能正文读取失败：").append(e.getMessage()).append("）");
            }
        }
        return sb.toString();
    }

    /**
     * 本轮随行内容 → 送进模型的用户文本。
     * <p>附件正文 / @ 引用 / # 历史引用此前只落库、不入上下文——用户上传了文件、@ 了知识库、
     * # 了历史问答，模型却一个字都收不到，于是如实回答「没有看到您上传的文件」。
     * <p>三段各自独立、任一为空就不追加段落；附件解析失败由 ChatAttachmentService 自己兜成
     * 一段可读的错误说明（fail-loud 到用户可见文案，而不是静默丢）。
     */
    public String userContentWithContext(ChatCommand cmd, SkillFilter skillFilter, String agentConsultDirective) {
        StringBuilder sb = new StringBuilder();
        String q = cmd.getQuestion() == null ? "" : cmd.getQuestion();
        // 插话没赶上本轮、随收尾交回后作为新一轮补发的那一条：不带这句声明，模型看到的是孤零零一句
        // 新用户消息，会当成「用户又问了一遍」重新起题。
        sb.append(cmd.isFromSteer() ? ChatCommand.decorateSteerQuestion(q) : q);
        // 1) 文档附件正文（当轮看一眼的轻路径：只抽文本，超长截断，不进向量库）
        if (cmd.getAttachments() != null && !cmd.getAttachments().isEmpty()) {
            List<ChatAttachmentService.PreparedAttachment> prepared =
                    // 按上传归属者读（免登录分享时是访客，不是发布者）——见 ChatCommand#uploadOwnerUid
                    attachments.prepare(cmd.getAttachments(), cmd.uploadOwnerUid());
            String text = attachments.buildContextText(prepared);
            if (!text.isBlank()) sb.append('\n').append(text);
        }
        // 2) @ 引用：kb=收窄本轮检索；doc=该文档内容直接带入上下文；agent=强制咨询。
        if (cmd.getMentions() != null && !cmd.getMentions().isEmpty()) {
            sb.append("\n【本轮 @ 引用】\n");
            for (ChatRequest.Mention m : cmd.getMentions()) {
                if (m == null) continue;
                sb.append("- ").append(m.getType()).append("：").append(m.getName()).append('\n');
            }
            sb.append(docMentionText(cmd.getMentions()));
            sb.append(agentConsultDirective);
        }
        // 3) # 引用：把被引用的历史问答原文带过来，否则模型无从知道"这一句"在说什么
        if (cmd.getHistoryRefs() != null && !cmd.getHistoryRefs().isEmpty()) {
            sb.append("\n【本轮引用的历史问答】\n");
            for (ChatRequest.HistoryRef h : cmd.getHistoryRefs()) {
                if (h == null || h.getContent() == null || h.getContent().isBlank()) continue;
                sb.append("- ").append("assistant".equals(h.getRole()) ? "答" : "问").append("：")
                        .append(h.getContent()).append('\n');
            }
        }
        // 4) 本轮指定技能：点名了就把正文送进去，否则「选了技能」这件事对模型完全不可见
        sb.append(pickedSkillsText(cmd, skillFilter));
        return sb.toString();
    }

    /**
     * @ 文档提及的正文注入（逐份前置到本轮上下文）。
     * <p>正文从知识块按 chunkIndex 顺序拼回：文档表不存全文，解析产物就是块。只注入「生效中」的
     * 文档——把解析中/失败/弃用文档的半成品或旧文喂给模型，比如实说「这份取不到」更害人。
     * 单份与总量各设字符上限：一次 @ 十份文档不设总闸会把本轮输入顶爆，先到先得，没赶上的如实说明。
     */
    private String docMentionText(List<ChatRequest.Mention> mentions) {
        boolean hasDoc = mentions.stream().anyMatch(m -> m != null && "doc".equals(m.getType()));
        if (!hasDoc) return "";
        // 文档正文是不可信数据（网页导入/外部上传都可能埋指令），信封+数据声明与检索工具同口径
        StringBuilder sb = new StringBuilder();
        sb.append(UntrustedContent.DATA_RULE).append('\n');
        int budget = ConfigDefaults.MENTION_DOC_TOTAL_MAX_CHARS;
        for (ChatRequest.Mention m : mentions) {
            if (m == null || !"doc".equals(m.getType())) continue;
            String name = m.getName() == null || m.getName().isBlank() ? m.getId() : m.getName();
            String body = docContentOf(m.getId());
            sb.append("\n【@ 文档：").append(UntrustedContent.sanitize(name)).append("】\n");
            if (body == null) {
                sb.append("（这份文档当前没有可用正文：可能已删除、停用或尚未解析完成。")
                        .append("请如实告知用户，不要凭文件名猜测内容）\n");
                continue;
            }
            if (budget <= 0) {
                sb.append("（本轮引用的文档较多，这份的正文因篇幅上限未注入。请如实告知用户该文档本轮未能读取）\n");
                continue;
            }
            int cap = Math.min(ConfigDefaults.MENTION_DOC_MAX_CHARS, budget);
            if (body.length() > cap) body = body.substring(0, cap) + "\n…（正文过长，已截断）";
            budget -= body.length();
            sb.append(UntrustedContent.envelope("@" + name, body)).append('\n');
        }
        sb.append("（以上 doc 引用的正文已由系统直接前置到本轮上下文，请据此作答，勿声称没有看到）\n");
        return sb.toString();
    }

    /**
     * 单份 @ 文档的正文（按块序拼回）；文档不在生效态或取不到时返回 null，由装配层如实说明。
     * 块级只过滤停用块（status=1，与检索同一判据），文档删没删看文档行本身。
     */
    private String docContentOf(String docId) {
        if (docId == null || docId.isBlank()) return null;
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc == null || (doc.getDeleted() != null && doc.getDeleted() != 0)
                    || doc.getStatus() == null || doc.getStatus() != 0) {
                return null;
            }
            List<Knowledge> chunks = knowledgeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Knowledge>()
                            .eq(Knowledge::getDocId, docId)
                            .ne(Knowledge::getStatus, 1)
                            .orderByAsc(Knowledge::getChunkIndex));
            StringBuilder body = new StringBuilder();
            for (Knowledge c : chunks) {
                if (c.getContent() == null || c.getContent().isBlank()) continue;
                if (body.length() > 0) body.append("\n\n");
                body.append(c.getContent());
            }
            return body.length() == 0 ? null : body.toString();
        } catch (Exception e) {
            log.warn("[MENTION] @ 文档正文装配失败 docId={}: {}", docId, e.toString());
            return null;
        }
    }
}
