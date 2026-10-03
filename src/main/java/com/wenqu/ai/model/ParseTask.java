package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档解析任务（持久化队列的一行）。
 * <p>
 * 上传只做「登记」：落盘 + 建文档记录 + 插一行本表，随即返回；
 * 真正的解析由 {@link com.wenqu.ai.service.ParseQueueService} 定时扫描抢占后执行。
 * 任务持久化而不是丢进内存线程池，目的是队列拥塞时**不丢任务**（旧实现队列满会直接删掉已建好的文档记录）。
 * <p>
 * 状态机：queued(0) ──抢占──▶ running(1) ──成功/终态──▶ succeeded(2) / dead(4)
 * running(1) ──失败可重试──▶ retryable(3) ──退避到 next_run_at──▶ queued(0)
 * running(1) ──租约(lease_until)过期或看门狗超时──▶ queued(0)（由扫描器回收，覆盖崩溃/卡死）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_parse_task")
public class ParseTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档 ID（与 c_ai_document.id 对应） */
    private String docId;

    /** 所属知识库 ID */
    private String kbId;

    /** 状态: 0=queued 待解析, 1=running 执行中, 2=succeeded 成功, 3=retryable 可重试, 4=dead 终态失败 */
    private Integer status;

    /** 已尝试次数（每次抢占 executable 时 +1） */
    private Integer attempt;

    /** 最大尝试次数（到达后失败转 dead，不再退避） */
    private Integer maxAttempt;

    /** 最早可执行时刻（首次入队=now；退避失败按指数递增推迟） */
    private LocalDateTime nextRunAt;

    /** 租约到期时刻：到点仍未回收说明执行线程已死（崩溃/卡死），扫描器把它收回 queued */
    private LocalDateTime leaseUntil;

    /** 优先级（大者优先；用户手动重解析可临时提权插队） */
    private Integer priority;

    /** 失败原因（status=3/4 时可见；落库前截断） */
    private String error;

    /** 执行者标识（实例 id）：任务只由抢占它的实例执行 */
    private String worker;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    // ==================== 状态常量（与 schema.sql 注释一一对应） ====================

    /** 待解析：等待被扫描器抢占 */
    public static final int STATUS_QUEUED = 0;
    /** 执行中：已被某实例抢到，持租约 */
    public static final int STATUS_RUNNING = 1;
    /** 已成功 */
    public static final int STATUS_SUCCEEDED = 2;
    /** 可重试：失败但可退避后重跑 */
    public static final int STATUS_RETRYABLE = 3;
    /** 终态失败：不可重试（格式/内容类错误），需人工处理或重新上传 */
    public static final int STATUS_DEAD = 4;

    /** 终态（不再被扫描）：只有 retryable 与 running 会被扫描器捞走 */
    public static boolean isTerminal(Integer status) {
        return status != null && (status == STATUS_SUCCEEDED || status == STATUS_DEAD);
    }
}
