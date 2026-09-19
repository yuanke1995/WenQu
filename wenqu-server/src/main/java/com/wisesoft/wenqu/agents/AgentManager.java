package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 内置智能体注册表。
 *
 * <p>由参考实现的 {@code agents/buildin/__init__.py} 中 {@code AgentManager} 逐方法翻译：
 * {@code register_agent} / {@code init_all_agents} / {@code get_agent} / {@code get_agents} /
 * {@code reload_all} / {@code get_agents_info} / {@code auto_discover_agents}。
 *
 * <h3>注册来源（显式标注）：由 Spring 装配，非目录扫描</h3>
 * <p>参考实现的 {@code auto_discover_agents()} 扫描 {@code agents/buildin/} 下的
 * {@code chatbot}/{@code subagent} 子包并导入其中的 {@link BaseAgent} 子类。
 * 这两个子包的等价物在本工程是 {@link ChatbotAgent} 与 {@link SubAgentBackend}——
 * 它们都需要构造期注入（backend / 模型 / 工具注册表 / 仓库），反射无参构造不可行，
 * 故改由 Spring 容器按 {@code List<BaseAgent>} 注入后登记（见 {@link #AgentManager(List)}）。
 *
 * <p>未由容器装配的类仍可按 {@link #registerAgent(Class)} 登记，此时
 * {@link #getAgent(String)} 走反射构造路径（保留参考实现的注册语义）。
 *
 * <p>能力差异（与参考实现的显式差异）：
 * <ul>
 *   <li>参考实现 {@code get_agent(agent_id)} 直接下标 {@code self._classes[agent_id]}，
 *       未知 id 抛 {@code KeyError}（表现为 500）；本工程返回 {@code null}，让调用方既有的
 *       {@code if not agent_backend: raise HTTPException(404, ...)} 分支生效（参考实现里
 *       这些分支实际不可达，属参考实现的既有瑕疵，本工程按调用方意图收敛）。</li>
 *   <li>{@code reload=True} 时参考实现**新建实例**；本工程对已装配的 Spring 单例
 *       收敛为「清空 graph 缓存」（构造依赖不可反射重建），对未装配类才新建实例。</li>
 *   <li>{@code get_agents_info} 是 async；本工程同步。</li>
 *   <li>注册顺序：参考实现按目录遍历；本工程按 Spring 注入顺序（bean 名）。</li>
 * </ul>
 */
@Service
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    private final Map<String, Class<? extends BaseAgent>> classes = new LinkedHashMap<>();
    private final Map<String, BaseAgent> instances = new LinkedHashMap<>();

    /**
     * 从 Spring 容器装配已就绪的后端实例（对应参考实现
     * {@code auto_discover_agents()} 遍历 {@code buildin/} 子包的等价物）。
     *
     * <p>参考实现按目录顺序导入两个子包并注册其 {@code BaseAgent} 子类；
     * 本工程的两个内置后端（{@link ChatbotAgent} / {@link SubAgentBackend}）是
     * Spring 单例（构造期需要注入 backend/模型/工具注册表等依赖，反射无参构造不可行），
     * 故由容器按 {@code List<BaseAgent>} 注入后登记。
     *
     * <p>能力差异（显式标注）：注入顺序由 Spring 决定（按 bean 名），
     * 与参考实现的目录遍历顺序不保证一致；{@link #getAgentsInfo} 的输出顺序随之不同。
     */
    public AgentManager(List<BaseAgent> agentBeans) {
        if (agentBeans != null) {
            for (BaseAgent agent : agentBeans) {
                registerInstance(agent);
            }
        }
    }

    /** 注册一个已构造好的智能体实例（Spring 注入路径；对应参考实现的 {@code _instances} 填充）。 */
    public synchronized void registerInstance(BaseAgent agent) {
        if (agent == null) {
            return;
        }
        String agentId = agent.getClass().getSimpleName();
        classes.put(agentId, agent.getClass());
        instances.put(agentId, agent);
    }

    /** 注册一个智能体类（对应 {@code register_agent}）；无实例时由 {@link #getAgent} 反射构造。 */
    public synchronized void registerAgent(Class<? extends BaseAgent> agentClass) {
        classes.put(agentClass.getSimpleName(), agentClass);
    }

    /** 实例化全部已注册智能体（对应 {@code init_all_agents}）。 */
    public synchronized void initAllAgents() {
        for (String agentId : new ArrayList<>(classes.keySet())) {
            getAgent(agentId);
        }
    }

    /**
     * 获取（必要时创建）智能体实例（对应 {@code get_agent}）。
     *
     * @param agentId 后端 id（参考实现取类名）
     * @return 实例；未注册该 id 时返回 {@code null}（见类注释的能力差异说明）
     */
    public synchronized BaseAgent getAgent(String agentId) {
        return getAgent(agentId, false, false);
    }

    public synchronized BaseAgent getAgent(String agentId, boolean reload, boolean reloadGraph) {
        Class<? extends BaseAgent> agentClass = classes.get(agentId);
        if (agentClass == null) {
            log.debug("智能体后端未注册: {}", agentId);
            return null;
        }
        if (reload) {
            if (instances.containsKey(agentId)) {
                // 能力差异：参考实现 reload=True 时**新建实例**；本工程的内置后端是 Spring 单例
                // （构造依赖不可反射重建），故等价收敛为「清空该实例的 graph 缓存」——
                // 两者对调用方的可观察效果一致（下次 get_graph 重新构图）。
                instances.get(agentId).reloadGraph();
            } else {
                instances.put(agentId, instantiate(agentId, agentClass));
            }
        } else if (!instances.containsKey(agentId)) {
            instances.put(agentId, instantiate(agentId, agentClass));
        }
        BaseAgent agent = instances.get(agentId);
        if (reloadGraph && agent != null) {
            agent.reloadGraph();
        }
        return agent;
    }

    /** 反射构造未由容器装配的后端（对应参考实现 {@code agent_class()}）。 */
    private static BaseAgent instantiate(String agentId, Class<? extends BaseAgent> agentClass) {
        try {
            return agentClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("智能体后端实例化失败: " + agentId, error);
        }
    }

    /** 全部已实例化智能体（对应 {@code get_agents}）。 */
    public synchronized List<BaseAgent> getAgents() {
        return new ArrayList<>(instances.values());
    }

    /** 重建全部实例（对应 {@code reload_all}）。 */
    public synchronized void reloadAll() {
        for (String agentId : new ArrayList<>(classes.keySet())) {
            getAgent(agentId, true, false);
        }
    }

    /** 全部智能体元信息（对应 {@code get_agents_info}）。 */
    public List<Map<String, Object>> getAgentsInfo(boolean includeConfigurableItems) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BaseAgent agent : getAgents()) {
            result.add(agent.getInfo(includeConfigurableItems, null, null));
        }
        return result;
    }

    /** 已注册的后端 id 集合（对应 {@code self._classes.keys()}）。 */
    public synchronized List<String> registeredIds() {
        return new ArrayList<>(classes.keySet());
    }

    /**
     * 自动发现并注册内置智能体（对应 {@code auto_discover_agents}）。
     *
     * <p>能力差异：Java 无运行时扫描包目录的等价机制。本工程的两个内置后端
     * （{@link ChatbotAgent} / {@link SubAgentBackend}）已由 Spring 容器装配并登记
     * （见 {@link #AgentManager(List)}），故本方法只作兼容入口：对尚未登记的类
     * 由调用方按 {@link #registerAgent(Class)} 显式补登。
     */
    public void autoDiscoverAgents() {
        log.debug("内置智能体自动发现：由 Spring 装配完成，当前已登记 {} 个后端 {}", classes.size(), classes.keySet());
    }
}
