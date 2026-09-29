package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具执行审批记录（人在回路）：每次有副作用工具（沙盒/MCP）在 ask 模式下执行前，
 * 落一条 PENDING 记录；用户裁决或超时后更新为终态（APPROVED/REJECTED/TIMEOUT）。
 * <p>
 * 与内存 {@code PENDING_APPROVALS}（CompletableFuture，负责阻塞工具线程）并存：
 * 内存态是运行期唤醒机制，本表是持久化 + 审计 + 进程重启后可见性。两者都为真实需要，
 * 内存态不可省（工具线程必须被 Future 阻塞），本表补全"重启/刷新丢失审批、无审计"的缺口。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_tool_approval")
public class ToolApproval {

    /** 审批请求 ID（业务生成 UUID，与 SSE approval_required 事件下发的 approvalId 一致） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属会话 */
    private String sessionId;

    /** 发起该轮问答的用户（仅本人可裁决） */
    private String userId;

    /** 待审批工具名（沙盒/MCP） */
    private String toolName;

    /** 状态: PENDING / APPROVED / REJECTED / TIMEOUT */
    private String status;

    /** 工具入参摘要（截断 2000 字符，审计与回溯用） */
    private String requestArgs;

    /** 创建时间（挂起时刻） */
    private LocalDateTime createdAt;

    /** 裁决/超时时间 */
    private LocalDateTime resolvedAt;
}
