package com.wenqu.ai.agent.tool;

import java.util.List;

/**
 * 工具族描述符提供方（编译期 SPI，不做运行期加载）。
 * <p>
 * 接法沿用仓库既有样板 {@code WebSearchService.java:30 List<WebSearchProvider>}：Spring 收集所有
 * 实现 bean，注册中心按族汇总。刻意**不**引入运行期字节码加载——问渠是多租户共享 JVM，
 * 加载第三方代码等于把供应链风险放进安全边界（对标结论见 docs/agent-runtime-gap-2026q4.md §二）。
 * <p>
 * 每个实现要同时提供 {@link #holder()}（承载 @Tool 方法的对象），注册中心在启动期反射核对
 * 「声明的名字」与「真正注册出来的名字」是否一致。这一条核对是描述符敢叫单一真源的前提：
 * 只靠人维护映射表，迟早回到「代码里有、清单里没有」的四处漂移。
 *
 * @author yuanke
 */
public interface ToolFamilyProvider {

    /** 本提供方负责的族 */
    ToolFamily family();

    /** 本族全部工具的声明（顺序即清单展示顺序） */
    List<ToolDescriptor> descriptors();

    /**
     * 承载 {@code @Tool} 方法的对象，供启动期反射核对工具名（与 {@code ToolCallbacks.from(...)} 同源）。
     * <p>返回 null 表示本族没有静态清单（MCP 外部工具按用户登记动态合入），核对跳过。
     */
    default Object holder() {
        return null;
    }
}
