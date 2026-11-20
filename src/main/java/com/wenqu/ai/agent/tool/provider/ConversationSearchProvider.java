package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.service.ConversationSearchTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史对话检索族：正文检索（{@code searchConversations} / {@code readConversation}）
 * 与过程回溯（{@code session_event_search} / {@code session_event_read} / {@code session_event_trace}）。
 * <p>只读、个人资产：游客轮不挂（描述符 guestAllowed=false）。只读工具不需要平台开关——
 * 要关就用按名停用名单，出网与写盘本来就拦不住它读的这部分。
 * <p>工具名刻意不叫 {@code session_search}：框架 AgentScope 已占用该名（搜运行时执行日志），
 * 同名会在 Toolkit 里撞注册名。
 *
 * @author yuanke
 */
@Component
public class ConversationSearchProvider implements ToolFamilyProvider {

    private final ConversationSearchTools tools;

    public ConversationSearchProvider(ConversationSearchTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.SESSION_RETRIEVAL;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("searchConversations", ToolFamily.SESSION_RETRIEVAL,
                        "检索历史对话", "检索历史对话",
                        "在你自己以往的对话记录里按关键词找",
                        SideEffect.READ, null, false, false),
                new ToolDescriptor("readConversation", ToolFamily.SESSION_RETRIEVAL,
                        "读取历史对话", "读取历史对话",
                        "读取某一次历史对话的完整往来",
                        SideEffect.READ, null, false, false),
                new ToolDescriptor("session_event_search", ToolFamily.SESSION_RETRIEVAL,
                        "检索历史过程事件", "检索历史过程事件",
                        "在你历史对话的过程账本里找某轮跑过什么工具/审批/错误",
                        SideEffect.READ, null, false, false),
                new ToolDescriptor("session_event_read", ToolFamily.SESSION_RETRIEVAL,
                        "读取历史过程事件", "读取历史过程事件",
                        "读取某次历史对话（或某一轮）的过程事件清单",
                        SideEffect.READ, null, false, false),
                new ToolDescriptor("session_event_trace", ToolFamily.SESSION_RETRIEVAL,
                        "回溯历史执行轨迹", "回溯历史执行轨迹",
                        "还原某一轮回答是怎么一步步跑出来的",
                        SideEffect.READ, null, false, false));
    }

    @Override
    public List<ToolCallback> callbacks(com.wenqu.ai.agent.tool.ToolMountContext mc) {
        if (mc == null || !mc.masterOn()) return List.of();
        return mountFiltered(mc, d -> true);
    }
}
