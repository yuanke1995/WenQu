package com.wenqu.ai.service;

import com.wenqu.ai.common.TenantResolutionException;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.User;
import com.wenqu.ai.service.contentsafety.ContentSafetyService;
import com.wenqu.ai.startup.ContentSafetySeedRunner;
import com.wenqu.ai.startup.RbacSeedRunner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantServiceFailClosedTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TenantService service = new TenantService(
            tenantMapper,
            userMapper,
            mock(AuditService.class),
            mock(JdbcTemplate.class),
            mock(ContentSafetySeedRunner.class),
            mock(RbacSeedRunner.class),
            mock(ContentSafetyService.class));

    @Test
    void dbFailureThrowsAndIsNotCached() {
        when(userMapper.selectById("u1")).thenThrow(new RuntimeException("db down"));

        assertThrows(TenantResolutionException.class, () -> service.tenantOf("u1"));
        assertThrows(TenantResolutionException.class, () -> service.tenantOf("u1"));

        verify(userMapper, times(2)).selectById("u1");
    }

    @Test
    void unknownUserIsNotNegativelyCached() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        assertNull(service.tenantOf("ghost"));
        assertNull(service.tenantOf("ghost"));

        verify(userMapper, times(2)).selectById("ghost");
    }

    @Test
    void positiveResultIsCached() {
        User u = new User();
        u.setUid("u1");
        u.setTenantId("t1");
        when(userMapper.selectById("u1")).thenReturn(u);

        assertEquals("t1", service.tenantOf("u1"));
        assertEquals("t1", service.tenantOf("u1"));

        verify(userMapper, times(1)).selectById("u1");
    }

    @Test
    void systemIdentityDoesNotHitDb() {
        assertNull(service.tenantOf(com.wenqu.ai.util.RequestUser.SYSTEM));

        verify(userMapper, times(0)).selectById(com.wenqu.ai.util.RequestUser.SYSTEM);
    }
}
