package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.mapper.KnowledgeMapper;

/**
 * 引用溯源要的知识块详情，以及编辑重发产生的消息分支（切换/撤销）。
 * 从 ChatSessionController 拆出：它面向「一条消息的内容与版本」，不面向会话列表。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "知识块与消息分组", description = "知识块详情，消息分支切换与撤销")
public class ChatMessageGroupController {

    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wenqu.ai.mapper.MessageMapper messageMapper;


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
}
