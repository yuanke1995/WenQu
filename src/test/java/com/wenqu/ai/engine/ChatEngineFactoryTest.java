package com.wenqu.ai.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ab 灰度分流的落点断言（Phase 6 放量的那只手）。
 * <p>盯住四件事：默认 0＝一条都不走框架（选了 ab 却没配比例时不许随机放量）、同一会话恒定落同一侧、
 * 比例大致兑现（放量看的是数字，不是「感觉有一半走了框架」）、越界与空会话号有确定行为。
 */
@DisplayName("引擎分派：ab 档按会话哈希分流")
class ChatEngineFactoryTest {

    @Test
    void 默认占比为零就不该有任何会话走框架() {
        assertFalse(ChatEngineFactory.hitsFramework("sess-abc", 0));
        assertFalse(ChatEngineFactory.hitsFramework(UUID.randomUUID().toString(), 0));
    }

    @Test
    void 占比一百时全量走框架() {
        assertTrue(ChatEngineFactory.hitsFramework("sess-abc", 100));
        assertTrue(ChatEngineFactory.hitsFramework(UUID.randomUUID().toString(), 100));
    }

    @Test
    void 同一条会话每次判定都落同一侧() {
        String sid = "sess-stable-1";
        boolean first = ChatEngineFactory.hitsFramework(sid, 35);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, ChatEngineFactory.hitsFramework(sid, 35), "分流要的是「一个会话一套行为」");
        }
    }

    @Test
    void 三十五的占比实际落点大致兑现() {
        int n = 4000;
        int hit = 0;
        for (int i = 0; i < n; i++) {
            if (ChatEngineFactory.hitsFramework("sess-" + i, 35)) hit++;
        }
        double ratio = hit * 100.0 / n;
        assertTrue(ratio > 30 && ratio < 40, "占比 35 实测 " + ratio + "%，偏差大到放量不可读");
    }

    @Test
    void 越界占比夹到两端而不是随机行为() {
        assertTrue(ChatEngineFactory.hitsFramework("sess-abc", 150), "150 该夹成 100");
        assertFalse(ChatEngineFactory.hitsFramework("sess-abc", -5), "负数该夹成 0");
    }

    @Test
    void 没有会话号时不参与分流() {
        assertFalse(ChatEngineFactory.hitsFramework(null, 100));
        assertFalse(ChatEngineFactory.hitsFramework("", 100));
    }
}
