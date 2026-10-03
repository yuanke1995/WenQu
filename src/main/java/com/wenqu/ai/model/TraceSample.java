package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * P1 Trace 采样池：线上对话按规则自动入池（差评必采 / 无引用 / 随机），
 * 运营在池里标注「期望命中的知识块」后回流评测集（{@code RetrievalEvaluationService.addCase}）。
 * <p>
 * 状态机：pending → labeled（回流）| dismissed（忽略）。qa_log_id 唯一——一条日志只进一次池，
 * 重复采样天然被唯一键挡住（采样任务幂等的兜底，不在任务里自己记账）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_trace_sample")
public class TraceSample {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 问答日志 ID */
    private String qaLogId;

    /** 回答消息 ID（标注时还原全过程） */
    private String messageId;

    /** 采样来源: bad=差评 nohit=无引用 random=随机 */
    private String source;

    /** 状态: pending=待标注 labeled=已回流 dismissed=已忽略 */
    private String status;

    /** 差评原因 / 标注备注 */
    private String note;

    /** 标注人 uid */
    private String labeledBy;

    /** 入池时间 */
    private LocalDateTime createdAt;

    /** 处理时间 */
    private LocalDateTime labeledAt;
}
