package com.wenqu.ai.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema 基础默认值：作为 {@code ConfigService.defaults()} 的底稿，必须覆盖全部带 def 的字段。
 */
class ConfigSchemaStoredDefaultsTest {

    private final ConfigSchemaService schema = new ConfigSchemaService();

    @Test
    void storedDefaultsCoverSchemaFields() {
        Map<String, String> defaults = schema.storedDefaults();

        assertThat(defaults).isNotEmpty();
        assertThat(defaults).containsKeys("agentWebhook.retryMax", "keyword.engine");
        assertThat(defaults.get("agentWebhook.retryMax")).isEqualTo("4");
        assertThat(defaults.get("keyword.engine")).isEqualTo("mysql");
        assertThat(defaults.entrySet()).allSatisfy(e -> {
            assertThat(e.getKey()).isNotBlank();
            assertThat(e.getValue()).isNotNull();
        });
    }
}
