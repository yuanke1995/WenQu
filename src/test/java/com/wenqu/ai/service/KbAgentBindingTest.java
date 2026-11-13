package com.wenqu.ai.service;

import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.KnowledgeBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库的「使用它的智能体」判据单测：显式绑定、内置「问渠」的动态范围、停用检索的排除。
 * 纯判据，不起 Spring 上下文。
 *
 * <p>钉住的回归：内置「问渠」的库范围按使用者动态解析、不落在 {@code knowledge_base_ids} 上，
 * 只认 CSV 时它在<b>每个</b>库的影响面里都是缺席的（默认库页签显示「还没有智能体绑定这个库」）。
 */
class KbAgentBindingTest {

    private static Agent agent(String csv, Integer builtin, Integer knowledgeDisabled) {
        Agent a = new Agent();
        a.setKnowledgeBaseIds(csv);
        a.setIsBuiltin(builtin);
        a.setKnowledgeDisabled(knowledgeDisabled);
        return a;
    }

    private static KnowledgeBase kb(String id, Integer builtin, Integer isDefault) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setBuiltin(builtin);
        k.setIsDefault(isDefault);
        return k;
    }

    @Test
    @DisplayName("内置「问渠」：默认库与官方手册库都算它用，且标为动态范围")
    void builtinCoversDefaultAndManual() {
        Agent wenqu = agent(null, 1, 0);
        assertThat(KbInsightsService.bindingKind(wenqu, kb("kb-default", 0, 1))).isEqualTo("default");
        assertThat(KbInsightsService.bindingKind(wenqu, kb("kb-manual", 1, 0))).isEqualTo("default");
    }

    @Test
    @DisplayName("内置「问渠」：专业域库不在它的固定范围内，不能算进去")
    void builtinDoesNotCoverDomainKb() {
        Agent wenqu = agent(null, 1, 0);
        assertThat(KbInsightsService.bindingKind(wenqu, kb("kb-law", 0, 0))).isNull();
    }

    @Test
    @DisplayName("普通智能体：CSV 含该库才算绑定，逐项 trim")
    void explicitBindingMatchesCsv() {
        Agent a = agent("kb-law, kb-manual ", 0, 0);
        assertThat(KbInsightsService.bindingKind(a, kb("kb-manual", 1, 0))).isEqualTo("bound");
        assertThat(KbInsightsService.bindingKind(a, kb("kb-other", 0, 0))).isNull();
    }

    @Test
    @DisplayName("普通智能体没绑库：默认库也不算它用（动态范围只属于内置「问渠」）")
    void unboundAgentDoesNotCoverDefaultKb() {
        assertThat(KbInsightsService.bindingKind(agent(null, 0, 0), kb("kb-default", 0, 1))).isNull();
    }

    @Test
    @DisplayName("knowledgeDisabled=1 整条跳过检索：显式绑定与内置范围都不算")
    void knowledgeDisabledExcluded() {
        assertThat(KbInsightsService.bindingKind(agent("kb-law", 0, 1), kb("kb-law", 0, 0))).isNull();
        assertThat(KbInsightsService.bindingKind(agent(null, 1, 1), kb("kb-default", 0, 1))).isNull();
    }

    @Test
    @DisplayName("knowledgeDisabled 为 null/0 按「使用知识库」：字段没写不能被读成用户要求关掉")
    void knowledgeDisabledNullMeansEnabled() {
        assertThat(KbInsightsService.bindingKind(agent("kb-law", 0, null), kb("kb-law", 0, 0))).isEqualTo("bound");
        assertThat(KbInsightsService.bindingKind(agent(null, 1, null), kb("kb-manual", 1, 0))).isEqualTo("default");
    }
}