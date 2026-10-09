package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话事件账本的一条记录（结构性事件：工具、人在回路三张卡、产物、子智能体编排、轮终态、用户停止）。
 * <p>
 * 定位与边界：本表只服务<b>过程回放与审计</b>，<b>不参与模型输入</b>——正文与 token/thinking/plan_delta
 * 增量仍在消息表与流里，进本表只会把账本写爆又不带信息量。自增主键即全局写入顺序，
 * 回放时按 {@code (session_id, turn_id)} 聚合、按 id 排序即可还原一轮的执行轨迹。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_session_event")
public class SessionEvent {

    /** 主键ID（自增即写入顺序） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属会话 */
    private String sessionId;

    /** 发起本轮的用户（数据按人隔离，仅本人可见） */
    private String userId;

    /** 本轮唯一ID（一轮的多条事件按它聚成一条轨迹） */
    private String turnId;

    /** 事件类型: tool / approval / ask_user / plan / artifact / subagent / agent / done / error / stop … */
    private String type;

    /** 事件载荷摘要（工具名/状态/耗时/入参，截断 4000 字符） */
    private String payload;

    /** 发生时刻 */
    private LocalDateTime createdAt;
}
