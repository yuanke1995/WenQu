package com.wisesoft.wenqu.common;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 容器持有器。
 *
 * <p>参考实现的任务处理器是模块级异步函数，内部按需自建仓储（如 {@code TaskRepository()}）。
 * 本工程的处理器是容器内服务实例上的方法，注册表按类名反射解析处理器时经此取得
 * 容器实例——这是"模块级函数自建仓储"在 Java/Spring 下的等价承载。
 */
@Component
public class SpringContext implements ApplicationContextAware {

    private static ApplicationContext context;

    public static <T> T bean(Class<T> clazz) {
        if (context == null) {
            throw new IllegalStateException("Spring 容器尚未就绪");
        }
        return context.getBean(clazz);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }
}
