package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.BrowserTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 浏览器操作族：模型侧唯一的浏览器工具入口（6 把工具，执行位置由个人设置决定）。
 *
 * <p>替换原 {@code LocalBrowserToolProvider}（localBrowser*）与平台 MCP 直接挂出的 browser_*：
 * 两者在这里合流，模型永远只看到这一套工具，避免「两套浏览器给两种结论」。执行位置有四种
 * （本机有头/无头、云端、离线不可用），由 {@link com.wenqu.ai.service.BrowserModeResolver} 唯一裁决。
 * 离线且要求本机时仍挂这套工具，但调用返回明确的不可用提示（fail-loud，不静默降级）。
 *
 * @author yuanke
 */
@Component
public class BrowserProvider implements ToolFamilyProvider {

    /** 本族的平台级子开关（schema 已登记，默认 true）。 */
    static final String GATE_KEY = "tool.browser.enabled";

    private final BrowserTools tools;

    public BrowserProvider(BrowserTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.BROWSER;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                desc("browserOpen", "打开网页并读快照", "打开网址并返回页面快照；看页面优先用它，不要点地址栏"),
                desc("browserNavigate", "打开网页", "在浏览器打开网址（本机执行位置可看 localhost/内网）"),
                desc("browserSnapshot", "读取页面快照", "读取当前页面结构化快照，含元素 ref"),
                desc("browserClick", "点击页面元素", "按快照 ref 点击当前页面元素"),
                desc("browserType", "填写输入框", "按快照 ref 向输入框填文字"),
                desc("browserScreenshot", "页面截图", "对当前页面截图留证"),
                // 收尾动作不额外要确认：目标轮收尾本来就会自动关一次
                new ToolDescriptor("browserClose", ToolFamily.BROWSER,
                        "关闭浏览器页面", "关闭浏览器页面", "关掉当前浏览器页面/窗口",
                        SideEffect.EXTERNAL, GATE_KEY, false, false));
    }

    private static ToolDescriptor desc(String name, String label, String hint) {
        return new ToolDescriptor(name, ToolFamily.BROWSER, label, label, hint,
                SideEffect.EXTERNAL, GATE_KEY, false, true);
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }

    @Override
    public boolean exposes(ToolDescriptor d, ToolMountContext mc) {
        // 默认实现过：审批档 + 族三态 + gateKey(tool.browser.enabled) + 具体项白名单
        return ToolFamilyProvider.super.exposes(d, mc) && mc.selectedByAgent(family(), d.name());
    }
}
