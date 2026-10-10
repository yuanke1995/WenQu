package com.wenqu.ai.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 工具注册中心：所有族声明的 {@link ToolDescriptor} 在这里汇总成一份，供挂载决策、审批判定、
 * 卡片命名与管理清单共同读取（P1 seam 化的「单一真源」落点）。
 * <p>
 * 与运行期插件加载无关：本类只在启动期收集编译期存在的 {@link ToolFamilyProvider} bean。
 * <p>
 * 启动期核对（{@link #validate()}）是这一刀真正的收益来源之一：每个提供方声明的工具名要与它那个
 * holder 对象上反射出来的 {@code @Tool} 方法名**逐一对上**，多写、漏写、拼错都当场记录。
 * 之所以不敢让它拦启动：一个措辞层面的漏项把整个平台起不来，代价比它要防的漂移更大；
 * 这里选 fail-loud 日志（ERROR 级，与问答链路的关键降级同屏可见），清单页也会显式标出未声明的工具。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ToolRegistry {

    private final List<ToolFamilyProvider> providers;
    private final Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
    private final Map<ToolFamily, List<ToolDescriptor>> byFamily = new LinkedHashMap<>();
    /**
     * 模型可见的工具说明（@Tool description 反射所得）。
     * <p>刻意不进 {@link ToolDescriptor}：那份文本是喂给模型的提示，与 @Tool 注解同源。再抄一份到
     * 描述符里就又是一个「改一处忘另一处」的副本，清单页直接读运行时注册结果即可。
     */
    private final Map<String, String> modelDescriptions = new LinkedHashMap<>();
    /** 族 → 提供方（一条族只有一个提供方；装配顺序与「这条族归谁判」都查它） */
    private final Map<ToolFamily, ToolFamilyProvider> providerByFamily = new LinkedHashMap<>();

    public ToolRegistry(List<ToolFamilyProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    @PostConstruct
    void init() {
        for (ToolFamilyProvider p : providers) {
            ToolFamilyProvider sameFamily = providerByFamily.put(p.family(), p);
            if (sameFamily != null) {
                // 一族两个提供方 = 装配时后挂的覆盖先挂的，与同名工具一个性质：模型看到的开关归属会变
                throw new IllegalStateException("[TOOL-REGISTRY] 族 " + p.family().code()
                        + " 有两个提供方：" + sameFamily.getClass().getSimpleName()
                        + " 与 " + p.getClass().getSimpleName());
            }
            List<ToolDescriptor> ds = p.descriptors();
            if (ds == null || ds.isEmpty()) continue;
            for (ToolDescriptor d : ds) {
                if (d == null || d.name() == null || d.name().isBlank()) continue;
                ToolDescriptor prev = byName.put(d.name(), d);
                if (prev != null) {
                    // 两个族声明同一个工具名 = 装配时后挂的覆盖先挂的，模型看到的说明与审批属性都变成
                    // 另一族的那一份。这是真冲突，不是笔误，拦下来让开发者当场看到。
                    throw new IllegalStateException("[TOOL-REGISTRY] 工具名冲突：" + d.name()
                            + " 同时由族 " + prev.family().code() + " 与 " + d.family().code() + " 声明");
                }
                byFamily.computeIfAbsent(d.family(), k -> new ArrayList<>()).add(d);
            }
        }
        validate();
        log.info("[TOOL-REGISTRY] 已登记 {} 个工具，{} 个族", byName.size(), byFamily.size());
    }

    /**
     * 声明名 vs 实际 @Tool 名逐族核对，并顺手缓存模型可见说明。
     * <p>漏一项就多一处「代码里有、清单里没有」：writeTodo 上线时清单页只有英文名、卡片名全靠前端
     * 手抄映射，就是这个机制的实际代价。
     */
    private void validate() {
        for (ToolFamilyProvider p : providers) {
            Object holder = p.holder();
            if (holder == null) continue;
            Collection<String> actual = new LinkedHashSet<>();
            try {
                for (var cb : ToolCallbacks.from(holder)) {
                    String n = cb.getToolDefinition().name();
                    actual.add(n);
                    modelDescriptions.put(n, cb.getToolDefinition().description());
                }
            } catch (Exception e) {
                log.error("[TOOL-REGISTRY] 族 {} 的 @Tool 反射核对失败（holder={}）: {}",
                        p.family().code(), holder.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            List<String> declared = p.descriptors().stream().map(ToolDescriptor::name).toList();
            List<String> undeclared = actual.stream().filter(n -> !declared.contains(n)).toList();
            List<String> phantom = declared.stream().filter(n -> !actual.contains(n)).toList();
            if (!undeclared.isEmpty() || !phantom.isEmpty()) {
                log.error("[TOOL-REGISTRY] 族 {} 声明与实现不一致——未声明却真实存在的工具（清单会缺行、"
                                + "审批与卡片名无从解析）={}；声明了却不存在的方法（笔误）={}",
                        p.family().code(), undeclared, phantom);
            }
        }
    }

    /** 模型可见说明（@Tool description）；未注册过的返回 null */
    public String modelDescription(String name) {
        return modelDescriptions.get(name);
    }

    public Optional<ToolDescriptor> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    /** 族归属：MCP 动态工具没有静态声明，按前缀认；再认不出返回 null（调用方按「未知工具」保守处理） */
    public ToolFamily familyOf(String name) {
        ToolDescriptor d = byName.get(name);
        if (d != null) return d.family();
        return ToolFamily.looksLikeMcpTool(name) ? ToolFamily.MCP : null;
    }

    /** 卡片展示名（未知工具返回 null，让前端用它自己那份兜底映射而不是硬造一个翻译） */
    public String displayLabel(String name) {
        ToolDescriptor d = byName.get(name);
        return d == null ? null : d.displayLabel();
    }

    public String cardHint(String name) {
        ToolDescriptor d = byName.get(name);
        return d == null ? null : d.cardHint();
    }

    /**
     * 是否需要用户审批。
     * <p><b>未登记的工具一律要</b>（fail-closed）：外部 MCP server 的工具名前缀来自用户自己起的
     * clientInfo 名（可以是 context7、可以是任意串），靠命名规律去认「这是外部工具」迟早漏——
     * 漏一次的结果是有副作用的工具跳过审批，这比多问用户一次严重得多。
     * 与迁移前「MCP 回调逐个加入 sensitiveTools」的口径等价（那份名单本来就是全量外部工具）。
     */
    public boolean requiresApproval(String name, Predicate<String> configOn) {
        ToolDescriptor d = byName.get(name);
        return d == null || d.requiresApproval(configOn);
    }

    public List<ToolDescriptor> all() {
        return List.copyOf(byName.values());
    }

    public List<ToolDescriptor> ofFamily(ToolFamily family) {
        return List.copyOf(byFamily.getOrDefault(family, List.of()));
    }

    /**
     * 各族提供方（装配循环用）。
     * <p>装配处只负责「问每个族本轮要挂什么」，不再自己抄判据；没迁移的族 {@code callbacks()}
     * 返回空列表，仍由问答侧的老分支挂载——绞杀式的过渡态就长这样，一族迁完删一段老代码。
     */
    public List<ToolFamilyProvider> providers() {
        return List.copyOf(providers);
    }

    /**
     * 按 {@link ToolFamily} 声明顺序排的提供方（装配循环用这个而不是 {@link #providers()}）。
     * <p>Spring 注入的 bean 列表顺序跟类扫描结果走，不保证稳定；工具表顺序虽然不影响语义，但
     * 「本次启用 N 个工具」这条日志是排障时对着看的，顺序每次变会让人怀疑是不是装配改了。
     */
    public List<ToolFamilyProvider> providersInFamilyOrder() {
        List<ToolFamilyProvider> out = new ArrayList<>();
        for (ToolFamily f : ToolFamily.values()) {
            ToolFamilyProvider p = providerByFamily.get(f);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * 这个工具本轮挂不挂（供提示词侧的能力分工段查询，与装配循环共用同一份判据）。
     * <p>未登记的工具名返回 false——它压根不在模型的工具表里，提示词不该提它。
     */
    /**
     * 这个工具本轮挂不挂（供提示词侧的能力分工段查询，与装配循环共用同一份判据）。
     * <p>未登记的工具名返回 false——它压根不在模型的工具表里，提示词不该提它。
     * <p>权限判据（档位裁剪 + 按名黑名单）在这里过一道，与 {@code ToolFamilyProvider.mountFiltered}
     * 那处同一条 {@link ToolMountContext#policyAllows}：提示词侧漏掉它，就会写出「教模型用某个工具、
     * 工具表里却没有它」。
     */
    public boolean exposes(ToolFamily family, String toolName, ToolMountContext mc) {
        ToolFamilyProvider p = providerByFamily.get(family);
        ToolDescriptor d = byName.get(toolName);
        return p != null && d != null && d.family() == family && p.exposes(d, mc)
                && mc.visibleToThisTurn(d) && mc.policyAllows(d);
    }

    /** 本族本轮是否至少有一个工具挂得上（族级判据：任务清单这种一族一工具的入口用它，调用方不必知道工具名） */
    public boolean anyExposed(ToolFamily family, ToolMountContext mc) {
        ToolFamilyProvider p = providerByFamily.get(family);
        if (p == null) return false;
        for (ToolDescriptor d : p.descriptors()) {
            if (p.exposes(d, mc) && mc.visibleToThisTurn(d) && mc.policyAllows(d)) return true;
        }
        return false;
    }
}
