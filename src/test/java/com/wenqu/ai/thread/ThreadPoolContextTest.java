package com.wenqu.ai.thread;

import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadPoolContextTest {

    @AfterEach
    void cleanup() {
        RequestUser.clear();
        TenantContext.clear();
        TenantContext.setResolver(null);
    }

    @Test
    void propagatesUserAndBoundTenant() throws Exception {
        RequestUser.set("u1", "d1", List.of("user"));
        TenantContext.bind("t1");

        Future<String> f = ThreadPoolManager.submit(
                (Callable<String>) () -> RequestUser.uid() + "/" + TenantContext.boundTenant());
        RequestUser.clear();
        TenantContext.clear();

        assertEquals("u1/t1", f.get(5, TimeUnit.SECONDS));
    }

    @Test
    void propagatesIdentityAndResolvesTenantInWorker() throws Exception {
        TenantContext.setResolver(uid -> "t2");
        RequestUser.set("u2", null, List.of("user"));

        Future<String> f = ThreadPoolManager.submit((Callable<String>) TenantContext::current);
        RequestUser.clear();
        TenantContext.clear();

        assertEquals("t2", f.get(5, TimeUnit.SECONDS));
    }

    @Test
    void contextDoesNotLeakBetweenTasks() throws Exception {
        RequestUser.set("u1", null, List.of("user"));
        TenantContext.bind("t1");
        ThreadPoolManager.submit((Callable<Void>) () -> null).get(5, TimeUnit.SECONDS);
        RequestUser.clear();
        TenantContext.clear();

        Future<String> next = ThreadPoolManager.submit(
                (Callable<String>) () -> RequestUser.uid() + "/" + TenantContext.boundTenant());

        assertEquals("system/null", next.get(5, TimeUnit.SECONDS));
    }
}
