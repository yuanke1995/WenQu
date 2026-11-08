package com.wenqu.ai.service;

import com.wenqu.ai.mapper.UsageLogMapper;
import com.wenqu.ai.model.UsageLog;
import com.wenqu.ai.util.TenantContext;
import com.wenqu.ai.util.UsageAttr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 台账租户归属判定核：线程没有身份时（模型框架弹性线程），按这笔用量的归属 uid 反查租户。
 * 修复前只看 {@code TenantContext.current()}，游客/工作流线程的账会落成 default。
 */
class UsageLedgerTenantTest {

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        com.wenqu.ai.util.RequestUser.clear();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        com.wenqu.ai.util.RequestUser.clear();
        TenantContext.setResolver(null);
    }

    @Test
    @DisplayName("线程未绑租户：按归属 uid 反查租户，不再落 default")
    void resolvesTenantFromAttrUid() {
        UsageLogMapper mapper = mock(UsageLogMapper.class);
        when(mapper.insert(any(UsageLog.class))).thenReturn(1);
        TenantContext.setResolver(uid -> "team-a-user".equals(uid) ? "team-a" : null);
        assertThat(TenantContext.resolveFor("team-a-user")).isEqualTo("team-a");

        new UsageLedgerService(mapper).recordFramework(
                UsageAttr.of("team-a-user", null, null, "chat"), "model-x", 10, 5, 0);

        ArgumentCaptor<UsageLog> captor = ArgumentCaptor.forClass(UsageLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("team-a");
        assertThat(captor.getValue().getUid()).isEqualTo("team-a-user");
    }

    @Test
    @DisplayName("线程已绑租户：绑定值优先，不按 uid 覆盖")
    void boundTenantWins() {
        UsageLogMapper mapper = mock(UsageLogMapper.class);
        when(mapper.insert(any(UsageLog.class))).thenReturn(1);
        TenantContext.setResolver(uid -> "team-a");
        TenantContext.bind("team-b");

        new UsageLedgerService(mapper).recordFramework(
                UsageAttr.of("team-a-user", null, null, "chat"), "model-x", 1, 1, 0);

        ArgumentCaptor<UsageLog> captor = ArgumentCaptor.forClass(UsageLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("team-b");
    }
}
