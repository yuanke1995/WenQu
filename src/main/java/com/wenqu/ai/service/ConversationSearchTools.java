package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 历史对话检索 / 回溯工具族（模型侧入口）：
 * <ul>
 *   <li>{@code searchConversations} / {@code readConversation}：按正文搜「我问过/聊过什么」；</li>
 *   <li>{@code session_event_search} / {@code session_event_read} / {@code session_event_trace}：
 *       按过程事件检索「哪一轮出过什么工具/审批/错误」，并回放某一轮的执行轨迹。</li>
 * </ul>
 *
 * <p>工具名与 DSH 规划表里的 {@code session_search} 略不同（这里叫 {@code searchConversations}）：
 * 框架的 AgentScope harness 已经占用 {@code session_search}/{@code session_list}/{@code session_history}
 * 三个名字（搜的是运行时执行日志），同名会与框架 Toolkit 注册冲突。产品语义要的是「我在问渠上问过什么」，
 * 所以另起不冲突的名字，指向 {@code c_ai_message}/{@code c_ai_session_event} 这层平台记录。
 *
 * <p>只对当前登录用户自己的问答记录生效（uid 从 ToolContext 透传，跨线程安全）。
 * 关键词为空时退化为「最近会话列表」，便于模型先定位再读细节。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ConversationSearchTools {

    private final ConversationSearchService service;
    private final SessionEventSearchService eventService;

    public ConversationSearchTools(ConversationSearchService service, SessionEventSearchService eventService) {
        this.service = service;
        this.eventService = eventService;
    }

    @Tool(name = "searchConversations",
            description = "在你自己以往的问渠对话记录里按关键词搜索（不是知识库、不是联网）。"
                    + "用户问「我之前问过/我们上次聊过…」时用它定位；返回命中的会话与片段，"
                    + "再用 readConversation 读完整往来。keyword 省略=列出最近会话。")
    public String searchConversations(
            @ToolParam(description = "检索关键词（人名、主题、关键句）；省略=按时间列最近会话", required = false) String keyword,
            @ToolParam(description = "只看最近多少天；省略=不限", required = false) Integer days,
            @ToolParam(description = "最多返回几个会话（1~30，默认 8）", required = false) Integer limit,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，历史对话检索失败";
        try {
            Map<String, Object> res = service.search(uid, keyword, days, limit);
            return JSON.toJSONString(res);
        } catch (Exception e) {
            log.warn("[SESSION] 检索历史对话失败 uid={} kw={}: {}", uid, keyword, e.toString());
            return "检索历史对话失败：" + e.getMessage();
        }
    }

    @Tool(name = "readConversation",
            description = "读取你自己某一次历史对话的往来内容（按 searchConversations 返回的 sessionId）。"
                    + "只读、只限本人的会话。")
    public String readConversation(
            @ToolParam(description = "会话 id（searchConversations 返回的 sessionId）") String sessionId,
            @ToolParam(description = "最多读取多少条消息（默认 40，上限 200）", required = false) Integer limit,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，历史对话读取失败";
        try {
            Map<String, Object> res = service.readConversation(uid, sessionId, limit);
            if (res == null) return "找不到这个会话（不存在，或不属于你）。";
            return JSON.toJSONString(res);
        } catch (Exception e) {
            log.warn("[SESSION] 读取历史对话失败 uid={} sid={}: {}", uid, sessionId, e.toString());
            return "读取历史对话失败：" + e.getMessage();
        }
    }

    // ==================== 回溯：过程事件（工具/审批/错误/终态） ====================

    @Tool(name = "session_event_search",
            description = "在你自己的历史对话的“过程事件账本”里检索：某轮调过什么工具、弹过什么审批、出过什么错误、"
                    + "子智能体咨询、产物交付等结构性事件。与 searchConversations（按正文搜）互补——"
                    + "那个答「我问过什么」，这个答「哪一轮跑过什么操作」。keyword 为空=列最近的事件。")
    public String sessionEventSearch(
            @ToolParam(description = "检索关键词（工具名、错误信息、审批事项等）；省略=最近事件", required = false) String keyword,
            @ToolParam(description = "事件类型过滤：tool / approval / ask_user / plan / artifact / subagent / error / done 等；省略=不限", required = false) String type,
            @ToolParam(description = "只看最近多少天；省略=不限", required = false) Integer days,
            @ToolParam(description = "最多返回多少条事件（1~50，默认 12）", required = false) Integer limit,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，事件检索失败";
        try {
            return JSON.toJSONString(eventService.search(uid, keyword, type, days, limit));
        } catch (Exception e) {
            log.warn("[SESSION] 检索过程事件失败 uid={} kw={} type={}: {}", uid, keyword, type, e.toString());
            return "检索过程事件失败：" + e.getMessage();
        }
    }

    @Tool(name = "session_event_read",
            description = "读取你自己某个历史会话（或其中某一轮）的过程事件清单，拿到事件 id / 类型 / 载荷。"
                    + "要回答“这次是怎么做的、用了哪些工具”先用它，再用 session_event_trace 看整轮轨迹。只读、只限本人。")
    public String sessionEventRead(
            @ToolParam(description = "会话 id（searchConversations/session_event_search 返回的 sessionId）") String sessionId,
            @ToolParam(description = "只看某一轮（turnId）；省略=该会话最近的事件", required = false) String turnId,
            @ToolParam(description = "最多读取多少条（默认 100，上限 500）", required = false) Integer limit,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，事件读取失败";
        try {
            Map<String, Object> res = eventService.read(uid, sessionId, turnId, limit);
            if (res == null) return "找不到这个会话（不存在，或不属于你）。";
            return JSON.toJSONString(res);
        } catch (Exception e) {
            log.warn("[SESSION] 读取过程事件失败 uid={} sid={} turn={}: {}", uid, sessionId, turnId, e.toString());
            return "读取过程事件失败：" + e.getMessage();
        }
    }

    @Tool(name = "session_event_trace",
            description = "还原你自己某个历史会话中某一轮的执行轨迹（按发生顺序：第几步调了什么工具、成没成、耗时、入参结果摘要，"
                    + "以及审批/错误/终态等事件）。用户问“你上次那条回答是怎么弄出来的/当时跑了哪些操作”时用它。只读、只限本人。")
    public String sessionEventTrace(
            @ToolParam(description = "会话 id") String sessionId,
            @ToolParam(description = "轮次 id（消息行的 turnId；可用 session_event_read 查到）") String turnId,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，轨迹读取失败";
        try {
            return eventService.trace(uid, sessionId, turnId);
        } catch (Exception e) {
            log.warn("[SESSION] 还原执行轨迹失败 uid={} sid={} turn={}: {}", uid, sessionId, turnId, e.toString());
            return "还原执行轨迹失败：" + e.getMessage();
        }
    }

    private static String uid(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_USER_ID);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
