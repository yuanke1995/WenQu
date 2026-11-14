package com.wenqu.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11「限制性参数默认放开」批次的 schema 侧对账：
 * 定义文件能装载、放宽后的默认值过得了保存校验、按面板恢复默认解析得到键、def 折算口径正确。
 * 本批次后多数限制项已从设置页退役为平台常量（值固定在 ConfigDefaults），
 * 这里只对账仍在 schema 里的键。
 */
class ConfigSchemaRelaxTest {

    private final ConfigSchemaService schema = new ConfigSchemaService();

    @Test
    @DisplayName("放宽后的 0/false 默认值通过保存校验（min 已同步降到 0）")
    void relaxedDefaultsPassValidate() {
        assertThat(schema.validate("chat.sseTimeoutMs", "0")).isNull();
        assertThat(schema.validate("ratelimit.enabled", "false")).isNull();
    }

    @Test
    @DisplayName("已退役为常量的键不再是可编辑槽位（保存校验按未知配置项拒绝）")
    void retiredKeysAreNotEditable() {
        assertThat(schema.validate("chat.goal.maxCompletionChecks", "0")).isNotNull();
        assertThat(schema.validate("agent.maxToolSteps", "0")).isNotNull();
        assertThat(schema.validate("upload.maxFileSize", "268435456")).isNotNull();
    }

    @Test
    @DisplayName("schema def 折算到存储层口径与 defaults() 同步")
    void defStoredValueMatchesRelaxedDefaults() {
        assertThat(schema.defStoredValue("ratelimit.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("按面板恢复默认：goal 面板已随深度执行预算退役，面板清单里不再有")
    void panelResolutionCoversRelaxedPanels() {
        assertThat(schema.panelKeys()).doesNotContain("goal");
        assertThat(schema.panelKeys()).contains("tool", "sandbox");
    }

    @Test
    @DisplayName("内容安全已整体退役（2026-11-14）：面板与键都不再是可编辑槽位")
    void contentSafetyFullyRetired() {
        assertThat(schema.panelKeys()).doesNotContain("contentSafety");
        assertThat(schema.validate("contentSafety.enabled", "false")).isNotNull();
    }
}
