package com.wenqu.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11「限制性参数默认放开」批次的 schema 侧对账：
 * 定义文件能装载、放宽后的默认值过得了保存校验、按面板恢复默认解析得到键、def 折算口径正确。
 */
class ConfigSchemaRelaxTest {

    private final ConfigSchemaService schema = new ConfigSchemaService();

    @Test
    @DisplayName("放宽后的 0/false 默认值通过保存校验（min 已同步降到 0）")
    void relaxedDefaultsPassValidate() {
        assertThat(schema.validate("chat.goal.maxRounds", "0")).isNull();
        assertThat(schema.validate("chat.goal.maxCompletionChecks", "0")).isNull();
        assertThat(schema.validate("chat.goal.maxVerifyFailures", "0")).isNull();
        assertThat(schema.validate("chat.goal.maxTokens", "0")).isNull();
        assertThat(schema.validate("agent.maxToolSteps", "0")).isNull();
        assertThat(schema.validate("chat.sseTimeoutMs", "0")).isNull();
        assertThat(schema.validate("ratelimit.enabled", "false")).isNull();
        assertThat(schema.validate("contentSafety.enabled", "false")).isNull();
        assertThat(schema.validate("upload.maxFileSize", "268435456")).isNull();
    }

    @Test
    @DisplayName("schema def 折算到存储层口径与 defaults() 同步（upload 是 MB→字节）")
    void defStoredValueMatchesRelaxedDefaults() {
        assertThat(schema.defStoredValue("chat.goal.maxRounds")).isEqualTo("0");
        assertThat(schema.defStoredValue("ratelimit.enabled")).isEqualTo("false");
        assertThat(schema.defStoredValue("contentSafety.enabled")).isEqualTo("false");
        assertThat(schema.defStoredValue("upload.maxFileSize")).isEqualTo("268435456");
        assertThat(schema.defStoredValue("chat.maxQuestionChars")).isEqualTo("200000");
    }

    @Test
    @DisplayName("按面板恢复默认：goal 面板 20 键全部解析得到，contentSafety 总闸在面板清单里")
    void panelResolutionCoversRelaxedPanels() {
        List<String> goal = schema.keysOfPanel("goal");
        assertThat(goal).hasSize(20).contains("chat.goal.maxRounds", "chat.goal.maxTokens");
        assertThat(schema.panelKeys()).contains("goal", "contentSafety", "tool", "sandbox");
        assertThat(schema.keysOfPanel("contentSafety")).contains("contentSafety.enabled");
    }
}
