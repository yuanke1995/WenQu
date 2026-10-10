package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.websearch.WebSearchTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 联网搜索族。
 * <p>
 * 唯一一条「是否需要审批」由配置决定的声明：{@code webSearch.requireApproval}。
 * 它确实会把用户输入发到外网并产生外部计费，性质上与沙盒/MCP 同级，但不默认强制确认——
 * 否则每次搜索都要点一下，联网体验不可用；管理员要收紧时开那一项即可。
 * 迁移前这段判断写在装配处的 if 里，清单页与前端各自另抄了一份，现在只有这一处。
 * <p>
 * guestAllowed=false：匿名访客不暴露联网（游客分支在挂载它之前就返回了）。
 */
@Component
public class WebSearchToolProvider implements ToolFamilyProvider {

    private final WebSearchTools tools;

    public WebSearchToolProvider(WebSearchTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.WEBSEARCH;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor(WebSearchTools.TOOL_NAME, ToolFamily.WEBSEARCH,
                "联网搜索", "联网搜索", "上网搜索相关资料，结果会作为引用来源",
                SideEffect.EXTERNAL, "webSearch.enabled", false, false, "webSearch.requireApproval"));
    }

    /**
     * 挂不挂只看两级开关（全局 webSearch.enabled + 智能体三态）；对匿名访客不暴露由描述符的
     * {@code guestAllowed=false} 决定——迁移前这条靠「游客分支提前 return 掉了联网」实现，
     * 于是「联网到底给不给游客」这件事在两份代码里各有答案。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
