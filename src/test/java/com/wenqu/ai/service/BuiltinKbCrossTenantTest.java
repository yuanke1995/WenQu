package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 官方内置手册库必须跨租户可读：内置「问渠」的检索范围依赖 builtinKb() 拿到
 * tenant_id=default 的全局行。非 default 租户线程里若被租户闸过滤，手册将从检索范围
 * 静默消失（助手答「知识库里没有 logo 资料」）。这里锁定 builtinKb() 以平台视图读取。
 */
class BuiltinKbCrossTenantTest {

    private final KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);

    private KnowledgeBaseService service() {
        return new KnowledgeBaseService(kbMapper, mock(AiDocumentMapper.class),
                mock(AgentMapper.class), mock(ModelRegistryService.class),
                mock(UserMapper.class), mock(KbVectorStoreRegistry.class),
                mock(ConfigService.class), mock(RoleService.class),
                mock(DynamicEmbeddingModel.class), mock(AgentBindingService.class));
    }

    @AfterEach
    void cleanThread() {
        TenantContext.clear();
    }

    @Test
    void builtinKbReadsInPlatformViewEvenUnderTenant() {
        TenantContext.bind("wenqu-ai"); // 非 default 租户的请求线程

        AtomicBoolean platformAtQuery = new AtomicBoolean(false);
        KnowledgeBase kb = new KnowledgeBase();
        when(kbMapper.selectOne(any())).thenAnswer(inv -> {
            platformAtQuery.set(TenantContext.inPlatformView());
            return kb;
        });

        KnowledgeBase got = service().builtinKb();

        assertThat(got).isSameAs(kb);
        assertThat(platformAtQuery).as("查询内置库时必须处于平台视图，否则租户闸会滤掉 default 行")
                .isTrue();
        assertThat(TenantContext.inPlatformView()).as("平台视图必须在调用后复原").isFalse();
        assertThat(TenantContext.current()).as("原租户必须在调用后复原").isEqualTo("wenqu-ai");
    }
}
