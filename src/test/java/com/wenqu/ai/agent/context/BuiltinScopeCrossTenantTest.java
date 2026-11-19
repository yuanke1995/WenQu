package com.wenqu.ai.agent.context;

import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.RetrievalFilter;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import com.wenqu.ai.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内置「问渠」的文档级范围必须包含官方手册库的文档（tenant_id=default）。
 * 非 default 租户线程里租户闸会滤掉这些文档行，导致手册块从检索范围静默消失。
 * 这里锁定 resolve() 在解析内置范围时以平台视图取文档 ID。
 */
class BuiltinScopeCrossTenantTest {

    @AfterEach
    void cleanThread() {
        TenantContext.clear();
    }

    @Test
    void builtinScopeResolvesDocIdsInPlatformView() {
        TenantContext.bind("wenqu-ai");

        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        // builtinScopeKbIds: 默认库 + 手册库
        when(kbService.defaultId("yuki")).thenReturn("default-kb");
        com.wenqu.ai.model.KnowledgeBase manual = new com.wenqu.ai.model.KnowledgeBase();
        manual.setId("manual-kb");
        when(kbService.builtinKb()).thenReturn(manual);

        AtomicBoolean platformAtDocIds = new AtomicBoolean(false);
        when(kbService.docIdsOf(any())).thenAnswer(inv -> {
            platformAtDocIds.set(TenantContext.inPlatformView());
            return Set.of("default-doc", "manual-doc");
        });

        Agent builtin = new Agent();
        builtin.setIsBuiltin(1);

        KnowledgeRetrievalTool.KbScope scope =
                new RetrievalScopeService(kbService).resolve(builtin, "yuki", true, null);

        assertThat(scope.kbIds()).containsExactlyInAnyOrder("default-kb", "manual-kb");
        assertThat(platformAtDocIds)
                .as("解析内置范围的文档 ID 时必须在平台视图，否则手册库文档被租户闸滤掉")
                .isTrue();
        assertThat(TenantContext.inPlatformView()).isFalse();
        assertThat(TenantContext.current()).isEqualTo("wenqu-ai");
    }
}
