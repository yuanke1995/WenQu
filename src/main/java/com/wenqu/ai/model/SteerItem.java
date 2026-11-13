package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行中插话的待发项（服务端持有的队列，前端读投影）。
 * <p>为什么落库：队列原来只活在前端内存，刷新即丢；「等这一轮结束再发」也就不可能跨刷新成立。
 * <p>状态机：{@code PENDING}（排队）→ {@code STEERED}（已送进框架 inbox，等模型听到）→
 * {@code APPLIED}（框架 {@code inbox/applied} 到了）；{@code DROPPED} 是用户删除或目标终态收敛。
 */
@Data
@TableName("c_ai_steer_item")
public class SteerItem {

    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;

    private String userId;

    /** 送达时那一刻的轮（goal:… 或用户轮 turnId） */
    private String turnId;

    /** 框架 steer 收据号：inbox/applied 据此把这一项置 APPLIED */
    private String receiptId;

    private String text;

    /** JSON 数组：回显 URL（落盘后的地址） */
    private String images;

    /** JSON 数组：[{name,mime,size,fileId}]：随这句话送出的文档附件（排队期间用于回显，送达时按 fileId 现读正文） */
    private String attachments;

    /** PENDING / STEERED / APPLIED / DROPPED */
    private String status;

    private Integer attempts;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
