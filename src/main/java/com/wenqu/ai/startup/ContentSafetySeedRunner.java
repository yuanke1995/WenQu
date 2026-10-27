package com.wenqu.ai.startup;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.mapper.ContentRuleMapper;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.util.TenantContext;
import com.wenqu.ai.model.ContentRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容安全规则的首批登记（Phase 3 · P12，docs/enterprise-upgrade-plan.md §4.3）。
 *
 * <p><b>只登记两条「个人信息」类的只告警规则，不预置任何拦截词表</b>：哪些字该拦属于部署方的
 * 合规判断（行业、地域、机构口径都不同），把一份词表写死进代码等于替客户做合规决定，
 * 而且没人能在上线前替你验证它对不对。所以「拦什么」留给管理员在界面上定，
 * 首启动只给出两条<b>不改变任何行为</b>的示例：命中只在审计里留一行，回答照旧。
 *
 * <p>为什么是这两条：《生成式人工智能服务管理暂行办法》与个人信息保护的要求里，
 * 「模型把身份证号/卡号整串复述出来」是最常见、也最容易自查的一类泄露，
 * 用它可以立刻验证出口闸真的在判（见手册「内容安全」一节），不需要先编一句违规话去试。
 *
 * <p>幂等：某个租户名下已有任何规则就一条都不加（管理员删掉的规则不会被重启塞回来）；
 * 词表按租户各一份（Phase 4），所以这里是逐租户各补一次，开通新租户时也走同一个入口。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class ContentSafetySeedRunner implements ApplicationRunner {

    private final ContentRuleMapper ruleMapper;
    private final TenantMapper tenantMapper;

    @Override
    public void run(ApplicationArguments args) {
        // 词表是按租户各一份的（Phase 4），所以首批规则要每家各登记一次：
        // 只给 default 种，等于新租户天生没有底线示例，管理员删掉自己那条之后就再也看不出闸门在不在跑
        try {
            List<String> tenants = new ArrayList<>();
            for (Tenant t : tenantMapper.selectList(null)) {
                tenants.add(t.getId());
            }
            if (tenants.isEmpty()) tenants.add(TenantContext.DEFAULT_TENANT);
            int seeded = 0;
            for (String t : tenants) {
                seeded += seedBaseline(t);
            }
            if (seeded > 0) {
                log.info("[SETUP] 内容安全：为 {} 个租户共登记 {} 条个人信息类只告警规则（拦截词表由管理员在「权限管理 → 内容安全」里定）",
                        tenants.size(), seeded);
            }
        } catch (Exception e) {
            log.warn("[SETUP] 内容安全规则登记失败（闸门按现有规则继续工作）: {}", e.toString());
        }
    }

    /**
     * 给某个租户登记首批规则：该租户名下已有任何规则就一条都不加（幂等，管理员删掉的不会被重启塞回来）。
     * <p>开通新租户时也调这里，新租户因此一开就有一份能验证闸门真的在判的示例。
     */
    public int seedBaseline(String tenantId) {
        int[] done = {0};
        TenantContext.as(tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT : tenantId, () -> {
            try {
                Long existing = ruleMapper.selectCount(new QueryWrapper<>());
                if (existing != null && existing > 0) return;
                ruleMapper.insert(rule("中国大陆身份证号", "(?<!\\d)\\d{17}[\\dXx](?!\\d)", 10,
                        "18 位身份证号被完整复述是最常见的一类个人信息泄露；先只记审计，观察一个周期再决定要不要拦"));
                ruleMapper.insert(rule("16-19 位连续卡号", "(?<!\\d)\\d{16,19}(?!\\d)", 20,
                        "银行卡/卡号整串出现在回答里同样属于个人信息；只告警不拦，避免误伤正常数字"));
                done[0] = 2;
            } catch (Exception e) {
                log.warn("[SETUP] 租户 {} 的内容安全首批规则失败: {}", tenantId, e.toString());
            }
        });
        return done[0];
    }

    private static ContentRule rule(String name, String pattern, int priority, String note) {
        ContentRule r = new ContentRule();
        r.setName(name);
        r.setCategory("个人信息");
        r.setStage("both");
        r.setMatchType("regex");
        r.setPattern(pattern);
        // 首批一律 warn：新策略先看一个周期的命中情况，再决定收紧成 block
        r.setAction("warn");
        r.setCaseSensitive(0);
        r.setEnabled(1);
        r.setPriority(priority);
        r.setNote(note);
        return r;
    }
}
