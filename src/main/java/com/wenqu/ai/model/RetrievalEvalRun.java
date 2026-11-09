package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索评估运行：一次实验一行。
 * <p>groupsJson 保存每组参数与指标（含置信区间/分群）快照；metricsJson 保存基线组指标，
 * 供历史列表与趋势图直接读取；timedOutGroups / staleExpected / warning 用于把「少跑/失效」
 * 这类异常显性化，避免把口径变化误读成质量涨跌。
 */
@Data
@TableName("c_ai_retrieval_eval_run")
public class RetrievalEvalRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String setId;

    private Integer setVersion;

    /** manual / scheduled */
    private String triggerKind;

    /** running / succeeded / partial / failed / cancelled */
    private String status;

    private Integer total;

    private Integer done;

    /** JSON 数组字符串，如 [5,10,20] */
    private String kList;

    /** 参数组指纹（SHA-256 前 16 位） */
    private String paramsId;

    private String groupsJson;

    private String metricsJson;

    private String segmentsJson;

    private Integer staleExpected;

    /** JSON 数组字符串 */
    private String timedOutGroups;

    private String warning;

    private String error;

    private String baselineRunId;

    private String judgeModel;

    private Long elapsedMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String createdBy;

    private LocalDateTime createTime;
}
