package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件外发投递台账（P1 通知外发）。
 * <p>与 {@code c_ai_webhook_delivery} 同一口径：pending → success / exhausted，至少一次重试。
 * 额外多一个 skipped：按规则未发送（总闸开但个人未勾选、无邮箱、聚合窗口内），留痕便于排障。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_mail_delivery")
public class MailDelivery {

    private String tenantId;

    @TableId(type = IdType.INPUT)
    private String id;

    private String uid;

    private String notificationId;

    private String notifyType;

    private String dedupKey;

    private String toAddr;

    private String subject;

    /** pending=待投递 success=已送达 exhausted=重试耗尽 skipped=按规则未发送 */
    private String status;

    private Integer attempts;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createTime;
}
