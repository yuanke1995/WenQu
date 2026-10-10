package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库精确检索族。
 * <p>
 * guestAllowed=true：免登录分享会话保留知识检索（公开分享的智能体本来就靠知识库作答），
 * 但检索范围仍受本轮库界约束（kbIds/docIds 由本轮上下文传入，不是按「谁问的」放宽）。
 * <p>
 * 本族是本次迁移的重点对象：它的引用注册器与检索范围迁移前走类级 ThreadLocal，
 * 是「跨会话串号」风险最直接的一处——命中的知识块一旦注册进别人的回答，用户看到的引用就是错的。
 */
@Component
public class KnowledgeToolProvider implements ToolFamilyProvider {

    private final KnowledgeRetrievalTool tool;

    public KnowledgeToolProvider(KnowledgeRetrievalTool tool) {
        this.tool = tool;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.KNOWLEDGE;
    }

    @Override
    public Object holder() {
        return tool;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor("searchKnowledge", ToolFamily.KNOWLEDGE,
                "知识库精确检索", "查知识库资料", "在你上传的知识库里检索相关资料片段",
                SideEffect.READ, "tool.knowledgeRetrieval.enabled", true, false, null));
    }

    /**
     * 检索-反思循环轮次强制暴露：那个模式本身就是「模型自主多轮检索」的显式开启，
     * 不再要求 tool.knowledgeRetrieval.enabled 子开关（与迁移前装配处同一语义）。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> mc.reflectiveRetrieval()
                || mc.gateOnFor(family(), d.gateKey()));
    }
}
