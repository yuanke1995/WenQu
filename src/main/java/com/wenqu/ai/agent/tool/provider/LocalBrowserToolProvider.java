package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.LocalBrowserTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本机浏览器族：仅当提问用户有在线的本机执行器时挂载。
 * <p>
 * 与云端「浏览器操作」MCP 是同一能力的两个执行位置：云端容器够不到用户的 localhost/内网，
 * 执行器在用户机器上能。两者工具名不同（localBrowser* vs browser_*），不会撞名；
 * 智能体可按需选用，执行器在线时本族自动出现。
 * <p>
 * 有副作用（驱动真实浏览器）→ requiresApproval=true，审批 ask/off 档按现有体系收口；
 * 游客不暴露（guestAllowed=false）。
 *
 * @author yuanke
 */
@Component
public class LocalBrowserToolProvider implements ToolFamilyProvider {

    private final LocalBrowserTools tools;

    public LocalBrowserToolProvider(LocalBrowserTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.LOCAL_BROWSER;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                desc("localBrowserOpen", "打开本机浏览器并读页面", "打开网址并返回页面快照；看页面优先用它，不要点地址栏"),
                desc("localBrowserNavigate", "打开本机浏览器网页", "在你电脑的浏览器打开网址（可看 localhost/内网）"),
                desc("localBrowserSnapshot", "读取本机浏览器页面", "读取当前页面结构化快照，含元素 ref"),
                desc("localBrowserClick", "点击本机浏览器元素", "按快照 ref 点击当前页面元素"),
                desc("localBrowserType", "填写本机浏览器输入框", "按快照 ref 向输入框填文字"),
                desc("localBrowserScreenshot", "本机浏览器截图", "对当前页面截图留证"),
                // 关闭是收尾动作，不额外要确认：目标轮收尾本来就会自动关一次
                new ToolDescriptor("localBrowserClose", ToolFamily.LOCAL_BROWSER,
                        "关闭本机浏览器", "关闭本机浏览器", "关掉你电脑上的浏览器窗口",
                        SideEffect.EXTERNAL, null, false, false, null));
    }

    private static ToolDescriptor desc(String name, String label, String hint) {
        return new ToolDescriptor(name, ToolFamily.LOCAL_BROWSER, label, label, hint,
                SideEffect.EXTERNAL, null, false, true, null);
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        // 执行器不在线=这台电脑没开执行器，本族整体不挂（模型看不见，不会对着空能力空转）
        boolean online = tools.onlineExecutor(mc.userId()) != null;
        return mountFiltered(mc, d -> online && exposes(d, mc));
    }
}
