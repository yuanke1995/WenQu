package com.wenqu.ai.config;

import com.wenqu.ai.common.TenantResolutionException;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantFailClosedTest {

    private final WenquTenantLineHandler handler = new WenquTenantLineHandler();

    @AfterEach
    void cleanup() {
        RequestUser.clear();
        TenantContext.clear();
        TenantContext.setResolver(null);
    }

    @Test
    void realUserWithoutTenantIsRejected() {
        TenantContext.setResolver(uid -> null);
        RequestUser.set("u1", null, List.of("user"));

        assertThrows(TenantResolutionException.class, () -> handler.ignoreTable("c_ai_session"));
    }

    @Test
    void resolverFailureIsRejectedNotIgnored() {
        TenantContext.setResolver(uid -> {
            throw new TenantResolutionException("db down");
        });
        RequestUser.set("u1", null, List.of("user"));

        assertThrows(TenantResolutionException.class, () -> handler.ignoreTable("c_ai_session"));
    }

    @Test
    void systemThreadStillCrossesTenants() {
        RequestUser.clear();
        TenantContext.clear();

        assertTrue(handler.ignoreTable("c_ai_session"));
    }

    @Test
    void boundTenantIsScoped() {
        TenantContext.bind("t1");

        assertFalse(handler.ignoreTable("c_ai_session"));
    }

    @Test
    void resolverInternalQueryIsAllowed() {
        TenantContext.setResolver(uid -> {
            assertTrue(TenantContext.resolving());
            assertTrue(handler.ignoreTable("c_ai_user"));
            return "t1";
        });
        RequestUser.set("u1", null, List.of("user"));

        assertFalse(handler.ignoreTable("c_ai_session"));
    }

    @Test
    void globalTableNeverFiltered() {
        RequestUser.set("u1", null, List.of("user"));

        assertTrue(handler.ignoreTable("c_ai_tenant"));
    }
}
