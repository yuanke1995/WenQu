package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.BuiltinTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置高频工具族的声明（纯计算、无副作用、无外部依赖）。
 * <p>
 * 本族是绞杀式迁移的第一族：它没有按轮注入的落点、没有外部计费、不动用户环境，最适合先把
 * 「描述符 → 注册中心 → 管线」这条路跑通；等这条路被最无害的一族验证过，再往检索/联网/沙盒推。
 * <p>
 * guestAllowed=true 是照现有行为记的：免登录分享会话本来就把内置工具留给访客（只收知识检索与内置项）。
 * askUser 的 sideEffect 记为 PURE 是有意的——它不改变世界，只是把人拉回这一轮；
 * 它的阻塞性由人在回路那套机制（超时不代答、跨设备恢复）管，不该混进副作用分级里。
 */
@Component
public class BuiltinToolProvider implements ToolFamilyProvider {

    private final BuiltinTools builtinTools;

    public BuiltinToolProvider(BuiltinTools builtinTools) {
        this.builtinTools = builtinTools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.BUILTIN;
    }

    @Override
    public Object holder() {
        return builtinTools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                desc("calculate", "算术计算", "做算术计算", "精确计算算式，避免模型心算出错"),
                desc("currentDateTime", "当前日期时间", "查看当前时间", "获取今天的日期与时间"),
                desc("daysBetween", "日期相差天数", "算日期相差几天", "计算两个日期之间相差多少天"),
                desc("addDays", "日期推算", "推算日期", "从某个日期加/减 N 天，得到新日期"),
                desc("randomNumber", "随机数", "生成随机数", "在指定范围内生成一个随机数"),
                desc("uuid", "生成 UUID", "生成随机编号", "生成一个不会重复的随机编号（UUID）"),
                desc("unitConvert", "单位换算", "单位换算", "长度、重量、温度等单位互相换算"),
                desc("textStats", "文本统计", "统计字数", "统计文本的字数、行数、段落数等"),
                desc("base64", "Base64 编解码", "Base64 转码", "在文本与 Base64 编码之间互相转换"),
                desc("hash", "哈希计算", "计算哈希值", "给文本算一个「指纹」，用于校验内容是否被改过"),
                // 提问卡是「人在回路」那一件：分支轮（P3a）没有观众，等不到答复，故带 viewerRequired
                desc("askUser", "向用户提问（人在回路）", "向用户提问", "向你提出问题（可多选的题会标出），点选或输入答案后继续回答", true));
    }

    private static ToolDescriptor desc(String name, String label, String cardLabel, String cardHint) {
        return desc(name, label, cardLabel, cardHint, false);
    }

    private static ToolDescriptor desc(String name, String label, String cardLabel, String cardHint,
                                       boolean viewerRequired) {
        return new ToolDescriptor(name, ToolFamily.BUILTIN, label, cardLabel, cardHint,
                SideEffect.PURE, null, true, false, null, viewerRequired);
    }

    /**
     * 两道闸都要过：工具总闸 + 智能体三态；再叠智能体的「具体项」白名单
     * （agent.builtinTools：null=全挂，否则只挂选中的那几个）。
     * <p>askUser 也在白名单里跟着走——这条判据同时被提示词的「人机协同三入口」段查询：
     * 提问卡没被选中时那一行不该写给模型，否则模型会对着不存在的工具连环空转。
     */
    @Override
    public boolean exposes(ToolDescriptor d, ToolMountContext mc) {
        return ToolFamilyProvider.super.exposes(d, mc) && mc.selectedByAgent(family(), d.name());
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
