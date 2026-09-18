package com.wisesoft.wenqu.models;

import java.util.List;

/**
 * 模型层的模块级常量。
 *
 * <p>由参考实现 storage/postgres/models_business.py 顶部的模块级常量逐条翻译
 * （终态集合、审计消息类型、登录锁定阈值等）。这些常量被多个仓储与服务共用，
 * 故与实体分开放在同一个包内。
 *
 * <p>说明：约束 SQL 常量（PROJECT_STATUS_CONSTRAINT_SQL、AGENT_RUN_SHAPE_CONSTRAINT_SQL 等）
 * 已经落在建表脚本 db/schema-mysql.sql 里，不在运行时使用，因此不在此重复。
 */
public final class ModelConstants {

    /** 登录失败次数上限。 */
    public static final int MAX_LOGIN_FAILED_ATTEMPTS = 5;

    /** 登录锁定秒数。 */
    public static final int LOGIN_LOCK_DURATION_SECONDS = 300;

    /** AgentRun 终态。 */
    public static final List<String> AGENT_RUN_TERMINAL_STATUSES =
            List.of("completed", "failed", "cancelled", "interrupted");

    /** 模型调用审计消息类型。 */
    public static final String MODEL_AUDIT_MESSAGE_TYPE = "model_audit";

    /** 工具调用审计消息类型。 */
    public static final String TOOL_AUDIT_MESSAGE_TYPE = "tool_audit";

    /** 审计消息类型集合。 */
    public static final List<String> AUDIT_MESSAGE_TYPES =
            List.of(MODEL_AUDIT_MESSAGE_TYPE, TOOL_AUDIT_MESSAGE_TYPE);

    /** 未查看运行标记。 */
    public static final String UNVIEWED_RUN_MARKER = "__unviewed__";

    private ModelConstants() {}
}
