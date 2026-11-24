package com.wenqu.ai.startup;

import com.wenqu.ai.mapper.AgentBindingMapper;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentBinding;
import com.wenqu.ai.service.AgentBindingCodec;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力绑定表的一次性回填（幂等）：把 {@code c_ai_agent} 现有能力列搬进 {@code c_ai_agent_binding}。
 * <p>为什么不写在 SQL 迁移里：列 ↔ 绑定的映射只允许有一份实现（{@link AgentBindingCodec}），
 * 在 SQL 里再写一遍 INSERT ... SELECT 就是第二份真源，改一处必漏另一处；而且 SQL 版本
 * 一旦写错（语法/字符集/JSON 函数差异）会让整次迁移失败，回填却什么都没做。
 * <p>幂等：已有绑定行的智能体跳过；没跳过的是「新智能体」——它们的绑定由保存路径双写产生。
 * <p>顺序：必须晚于 SchemaMigrator(@Order(0)) 与 Flyway（建表在 V41）。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AgentBindingBackfill implements ApplicationRunner {

    private final AgentMapper agentMapper;
    private final AgentBindingMapper bindingMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Agent> agents = TenantContext.asPlatform(() -> agentMapper.selectList(null));
            int filled = 0;
            for (Agent a : agents) {
                if (a == null || a.getId() == null) continue;
                Long exists = TenantContext.asPlatform(
                        () -> bindingMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentBinding>()
                                .eq(AgentBinding::getAgentId, a.getId())));
                if (exists != null && exists > 0) continue;
                List<AgentBinding> rows = AgentBindingCodec.fromAgent(a);
                if (rows.isEmpty()) continue;
                TenantContext.asPlatform(() -> {
                    for (AgentBinding b : rows) bindingMapper.insert(b);
                    return null;
                });
                filled++;
            }
            if (filled > 0) {
                log.info("[AGENT-BINDING] 能力绑定回填完成：{} 个智能体（幂等，已有绑定的跳过）", filled);
            }
        } catch (Exception e) {
            // 回填失败不阻塞启动：读侧默认仍读旧列，双写会在后续保存时补齐
            log.warn("[AGENT-BINDING] 能力绑定回填失败（不阻塞启动，读侧仍走旧列）: {}", e.toString());
        }
    }
}
