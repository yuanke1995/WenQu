package com.wisesoft.wenqu.common;

/**
 * 应用版本号：参考实现的 {@code get_version()}（读取包版本，项目配置中为 {@code 0.7.3}）的等价常量。
 *
 * <p>必要替换（显式标注）：版本号取本系统自身的版本标识（{@code wenqu-server} 的 artifact 版本
 * {@code 1.0.0}），不沿用参考实现的版本号——版本号属于产品标识，与逻辑无关。
 */
public final class AppVersion {

    /** 本系统版本号（与 wenqu-server/pom.xml 的 artifact version 对齐）。 */
    public static final String VERSION = "1.0.0";

    /** 产品标识（参考实现中该字段为对标产品名）。 */
    public static final String PRODUCT_NAME = "WenQu";

    /** 中文产品名。 */
    public static final String PRODUCT_NAME_ZH = "问渠";

    private AppVersion() {}
}
