package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.ParseTaskMapper;
import com.wenqu.ai.parser.ocr.OcrEngineGate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并发闸热调整的回归测试。
 * <p>历史缺陷：按 availablePermits() 而非「许可总量」比较，配置下调时算出
 * {@code want - have = 负数} 传给 {@code Semaphore.reducePermits}，它会抛无 message 的
 * IllegalArgumentException——表现是「向量化批次失败: null」，整篇文档反复重试也无法成功。
 */
class ParseGatePermitSyncTest {

    @Test
    void embedGateShrinkDoesNotThrowWhenConfigLowered() throws Exception {
        ConfigService config = mock(ConfigService.class);
        // 构造期 3；之后 sync 读到 1（配置下调）
        when(config.getInt(eq("parse.embedConcurrency"), anyInt())).thenReturn(3, 1, 1);
        ParseQueueService service = new ParseQueueService(
                mock(ParseTaskMapper.class), config, mock(DocumentService.class),
                mock(AiDocumentMapper.class), mock(KnowledgeBaseMapper.class), mock(NotificationService.class));

        Method sync = ParseQueueService.class.getDeclaredMethod("syncEmbedGate");
        sync.setAccessible(true);

        assertDoesNotThrow(() -> sync.invoke(service));
        // 收缩后仍能正常取到名额并归还
        assertDoesNotThrow(service::acquireEmbedPermission);
        service.releaseEmbedPermission();
    }

    @Test
    void ocrGateShrinkDoesNotThrowWhenConfigLowered() throws Exception {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt(eq("parse.ocrGateConcurrency"), anyInt())).thenReturn(3, 1, 1);
        OcrEngineGate gate = new OcrEngineGate(config);

        Method sync = OcrEngineGate.class.getDeclaredMethod("syncPermits", String.class);
        sync.setAccessible(true);

        assertDoesNotThrow(() -> sync.invoke(gate, "mineru"));
        assertDoesNotThrow(() -> sync.invoke(gate, "mineru"));
    }
}
