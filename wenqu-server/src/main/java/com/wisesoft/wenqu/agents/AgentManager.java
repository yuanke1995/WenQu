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
 * <h3>能力差异（显式标注，非遗漏）：注册表当前为空</h3>
 * <p>参考实现的 {@code auto_discover_agents()} 扫描 {@code agents/buildin/} 下的
 * {@code chatbot}/{@code subagent} 子包并导入其中的 {@link BaseAgent} 子类。这两个子包
 * （{@code buildin/chatbot/{context,graph,state}.py}、{@code buildin/subagent/{context,graph}.py}）
 * 属于 §三 agents 运行时的**引擎面**——它们的 {@code create_agent}/{@code StateGraph}
 * 构图直接压在 langgraph 与 deepagents 上，本工程未照搬该运行时框架
 * （见 {@code PORTING_CHECKLIST.md} 的「§三 的根本阻塞」专项说明）。
 *
 * <p>因此本注册表**保留全部注册/解析/重载语义**，但当前没有任何内置后端可注册：
 * {@link #getAgent(String)} 对任何 id 返回 {@code null}。这与参考实现在"后端未注册"时的
 * 业务表现一致（调用方一律以 404「智能体后端 X 不存在」处理）。
 *
 * <p>能力差异（与参考实现的显式差异）：
 * <ul>
 *   <li>参考实现 {@code get_agent(agent_id)} 直接下标 {@code self._classes[agent_id]}，
 *       未知 id 抛 {@code KeyError}（表现为 500）；本工程返回 {@code null}，让调用方既有的
 *       {@code if not agent_backend: raise HTTPException(404, ...)} 分支生效（参考实现里
 *       这些分支实际不可达，属参考实现的既有瑕疵，本工程按调用方意图收敛）。</li>
 *   <li>{@code reload=True} 时参考实现**新建实例**；本工程同步重建实例（等价）。</li>
 *   <li>{@code get_agents_info} 是 async；本工程同步。</li>
 * </ul>
 */
@Service
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    private final Map<String, Class<? extends BaseAgent>> classes = new LinkedHashMap<>();
    private final Map<String, BaseAgent> instances = new LinkedHashMap<>();

    /** 注册一个智能体类（对应 {@code register_agent}）。 */
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
        if (reload || !instances.containsKey(agentId)) {
            try {
                instances.put(agentId, agentClass.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("智能体后端实例化失败: " + agentId, error);
            }
        }
        BaseAgent agent = instances.get(agentId);
        if (reloadGraph && agent != null) {
            agent.reloadGraph();
        }
        return agent;
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
     * <p>能力差异：Java 无运行时扫描 Python 包的等价机制，且 {@code buildin} 子包
     * （引擎面）未照搬，故本方法当前为注册待补的空实现——由后续引擎照搬时按
     * {@link #registerAgent(Class)} 显式登记。
     */
    public void autoDiscoverAgents() {
        log.debug("内置智能体自动发现：当前无已照搬的内置后端（引擎面未照搬）");
    }
}
