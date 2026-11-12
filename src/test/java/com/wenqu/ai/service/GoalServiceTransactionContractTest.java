package com.wenqu.ai.service;

import com.wenqu.ai.engine.RunChannel;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标生命周期的多写路径必须挂事务：pause/resume/edit/cancel/deleteRecord
 * 都会同时改目标行、轮次行或事件账本，半途失败不能留下“目标状态与轮次状态各说各话”。
 *
 * <p>这是防回退的契约检查，不是运行时事务证明；运行时由 Spring 代理保证。
 */
class GoalServiceTransactionContractTest {

    @Test
    void lifecycleWritesAreTransactional() throws Exception {
        assertTransactional("pause", String.class, String.class, RunChannel.class);
        assertTransactional("resume", String.class, String.class, RunChannel.class);
        assertTransactional("edit", String.class, String.class, String.class, RunChannel.class);
        assertTransactional("cancel", String.class, String.class, RunChannel.class);
        assertTransactional("deleteRecord", String.class, String.class, RunChannel.class);
    }

    private static void assertTransactional(String name, Class<?>... params) throws Exception {
        Method m = GoalService.class.getMethod(name, params);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("%s 必须带 @Transactional", name)
                .isTrue();
    }
}
