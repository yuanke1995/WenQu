package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigSchemaService;
import com.wenqu.ai.mapper.ConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置保存对「不在字段定义里的键」的口径断言。
 * <p>
 * 回归对象是 2026-10-13 前的一处假成功：PUT /api/ai/config 收到 schema 里没有的键时把它静默扔掉，
 * 其余照常落库，接口仍返回"配置已保存并生效"——界面显示成功、库里没变，据此做的调参 A/B 结论全是错的。
 * 现在必须整笔拒绝：宁可一笔都不改，也不写一半再报告成功。
 * <p>
 * 用的未知键是 {@code rerank.failCooldownMs}（运行时会读、但不在设置页清单里）。
 * 原先这里写的 {@code rerank.maxHits} 已在 2026-10-13 登记进 schema、成了合法键——本测试当场把它变红，
 * 正是它该起的作用：**换未知键夹具时挑一个真不在清单里的**。
 *
 * @author yuanke
 */
@DisplayName("配置保存：不认识的键必须整笔拒绝")
class ConfigServiceUpdateTest {

    /** 运行时真被读取、但没登记进设置页清单的键（schema 里没有 = 保存必须拒绝） */
    private static final String UNKNOWN_KEY = "failCooldownMs";

    private ConfigMapper configMapper;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configMapper = Mockito.mock(ConfigMapper.class);
        // 白名单与校验规则来自 classpath:config-schema.json（真实定义，不 mock）；
        // 其余协作者在拒绝路径上不会被触碰，传 null 即可
        configService = new ConfigService(configMapper, null, null, null, null, null, null,
                new ConfigSchemaService());
    }

    private static Map<String, Map<String, String>> body(String group, String key, String value) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(key, value);
        Map<String, Map<String, String>> body = new LinkedHashMap<>();
        body.put(group, kv);
        return body;
    }

    @Test
    @DisplayName("未知键：报错点名到具体项，且不碰数据库")
    void rejectsUnknownKeyAndNamesIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> configService.update(body("rerank", UNKNOWN_KEY, "60000")));

        assertTrue(e.getMessage().contains("rerank." + UNKNOWN_KEY), e.getMessage());
        Mockito.verifyNoInteractions(configMapper);
    }

    @Test
    @DisplayName("未知键与合法键混提交：合法项也不落库（不写一半）")
    void mixedPayloadWritesNothing() {
        Map<String, Map<String, String>> body = body("context", "maxBlocksPerDoc", "2");
        body.put("rerank", Map.of(UNKNOWN_KEY, "60000"));

        assertThrows(IllegalArgumentException.class, () -> configService.update(body));

        Mockito.verifyNoInteractions(configMapper);
    }

    @Test
    @DisplayName("拼错的键不会被当成另一个键收下")
    void rejectsNearMissKey() {
        // parse.queueScanIntervalMs 是真发生过的拼错形态（正确键是 parse.queue.scanIntervalMs）
        assertThrows(IllegalArgumentException.class,
                () -> configService.update(body("parse", "queueScanIntervalMs", "1500")));

        Mockito.verifyNoInteractions(configMapper);
    }
}
