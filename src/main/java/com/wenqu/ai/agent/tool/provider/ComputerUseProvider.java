package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.ComputerUseTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桌面操作族（Computer Use）：在用户本机执行器所在的 macOS 上操作桌面。
 * <p>读类（列应用/窗口、截图）为 READ，不进审批；动手类（打开应用、点击、输入、按键）为 MUTATE，
 * 「每次问我」档下逐次确认。审批 off 档整族不挂（{@link ToolFamily#blockedWhenApprovalOff()}）。
 *
 * @author yuanke
 */
@Component
public class ComputerUseProvider implements ToolFamilyProvider {

    private final ComputerUseTools tools;

    public ComputerUseProvider(ComputerUseTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.COMPUTER_USE;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                read("cuaListApps", "列出运行中的应用", "列出", "看你电脑上现在开着哪些应用"),
                read("cuaListWindows", "列出应用窗口", "列窗口", "看某个应用开着哪些窗口"),
                read("cuaScreenshot", "截取屏幕", "截图", "对屏幕或某个窗口截图留证"),
                write("cuaOpenApp", "打开应用", "打开应用", "在你电脑上打开一个软件"),
                write("cuaClick", "点击屏幕", "点击", "在屏幕坐标上点击鼠标"),
                write("cuaType", "输入文字", "输入文字", "向当前焦点输入框键入文字"),
                write("cuaKey", "发送按键", "按键", "发送按键或组合键"));
    }

    private static ToolDescriptor read(String name, String label, String cardLabel, String hint) {
        return new ToolDescriptor(name, ToolFamily.COMPUTER_USE, label, cardLabel, hint,
                SideEffect.READ, null, false, false);
    }

    private static ToolDescriptor write(String name, String label, String cardLabel, String hint) {
        return new ToolDescriptor(name, ToolFamily.COMPUTER_USE, label, cardLabel, hint,
                SideEffect.MUTATE, null, false, true);
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn()) return List.of();
        if (!mc.approvalPolicyAllows(family())) return List.of();
        if (mc.guestMode()) return List.of();   // 桌面是用户个人资产，游客不挂
        // 不加平台开关，改用档位做闸门：动用户电脑只在「完全权限」档可用。
        // standard 档的承诺是「工作区内改文件」，把它扩到桌面等价于静默越界；
        // 收窄发生在装配（模型看不见），而不是执行（点了被拒）。
        var policy = mc.permissionPolicy();
        if (policy == null || policy.sandboxMode()
                != com.wenqu.ai.agent.permission.SandboxMode.DANGER_FULL_ACCESS) {
            return List.of();
        }
        return mountFiltered(mc, d -> true);
    }
}
