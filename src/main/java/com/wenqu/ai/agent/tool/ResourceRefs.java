package com.wenqu.ai.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能体上「个人资产引用」的解析（技能 / MCP 同构）。
 * <p>
 * 为什么放进工具装配这一层：技能与 MCP 都是**个人资产**，而智能体是公共对象，裸名字不是全局唯一键——
 * 只按名字匹配会出现「使用者装了同名但内容不同的资源，被当成管理员指定的那个来用」（张冠李戴）。
 * 引用因此分两种形态：
 * <ul>
 *   <li>{@code {uid}/{name}}：精确绑定某个人的资源。归属人是当前用户 → 参与匹配；不是 → 判不可用
 *       （<b>不拿当前用户的同名资源顶替</b>）。</li>
 *   <li>裸 {@code name}：存量配置的弱匹配（按名字在当前用户自己的资源里找）；内置技能也走这条——
 *       它随版本分发、人人都有，按名字匹配才是对的。</li>
 * </ul>
 * 迁移前这段是 {@code RagService} 的私有静态方法，MCP 与技能各调一次；MCP 族迁到 provider 之后
 * 判据要在 provider 里跑，纯字符串解析再留在问答侧就变成「provider 得回头问 RagService」，所以搬过来。
 *
 * @author yuanke
 */
public final class ResourceRefs {

    /**
     * @param names   本轮可用的资源名（null = 智能体未指定具体项，按用户自己的全部已启用资源）
     * @param notMine 明确指定但不属于当前用户的资源名（要挂一条可见降级，不能静默当没配）
     */
    public record Scope(Set<String> names, List<String> notMine) {
    }

    private ResourceRefs() {
    }

    /** refs 为 null = 未指定具体项（不是「指定了零个」）；空集合是用户显式选了「不使用」 */
    public static Scope resolve(Set<String> refs, String uid) {
        if (refs == null) return new Scope(null, List.of());
        Set<String> names = new LinkedHashSet<>();
        List<String> notMine = new ArrayList<>();
        for (String r : refs) {
            if (r == null) continue;
            int slash = r.indexOf('/');
            if (slash < 0) {
                names.add(r);            // 裸名：内置技能 / 存量配置
                continue;
            }
            String owner = r.substring(0, slash).trim();
            String name = r.substring(slash + 1).trim();
            if (name.isEmpty()) continue;
            if (owner.isEmpty() || owner.equals(uid)) names.add(name);
            else notMine.add(name);      // 别人的个人资产：不可用
        }
        return new Scope(names, notMine);
    }
}
