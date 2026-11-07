package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统巡检历史报告：一次巡检一行，供趋势、历史回看与定时告警使用。
 * <p>
 * 原先报告只留在 {@code OpsCheckupService} 的进程内存里，后端一重启就「不记得上次体检」——
 * 对运维而言，恰恰是出事前后那几次报告最有价值。落库后，定时巡检、趋势曲线和
 * 「失败时通知管理员」才有可回溯的底座。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_ops_checkup_report")
public class OpsCheckupReport {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户 ID（行级隔离：租户管理员只看本租户的巡检历史） */
    private String tenantId;

    /** 巡检完成时刻（趋势按它按天聚合） */
    private LocalDateTime at;

    /** 触发方式: manual=手动 schedule=定时 */
    private String triggerType;

    /** 整体结论: ok/warn/fail（取最差项） */
    private String overall;

    /** 本次巡检总耗时（毫秒） */
    private Long tookMs;

    /** 必须处理项数量 */
    private Integer failCount;

    /** 需留意项数量 */
    private Integer warnCount;

    /** 逐项结果 JSON（key/group/name/level/detail/ms/hint） */
    private String checksJson;

    /** 触发人 uid（定时任务为空） */
    private String createdBy;

    private LocalDateTime createTime;
}
