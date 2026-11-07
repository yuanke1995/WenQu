package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体评测套件（评测集设置）：一个智能体一套（uk agent_id，一期不做多套件选择成本）。
 * <p>
 * 门槛与通过分是<b>门禁判定口径</b>：通过率 ≥ thresholdRate 且无 0 分严重失败 → 达标。
 * 每次运行把当刻的门槛/通过分快照进 run 行——事后改设置不影响历史口径。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_eval_suite")
public class AgentEvalSuite {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 智能体 ID（一期一智能体一套件） */
    private String agentId;

    /** 套件名（NULL = 前端按「默认评测集」展示） */
    private String name;

    /** 通过率门槛（百分比 0-100，默认 80） */
    private Double thresholdRate;

    /** 单条通过综合分（0-100，默认 60） */
    private Double passScore;

    /** 是否参与门禁：1=是 0=暂停（暂停后运行照常可手动触发，只是列表门禁态按未评测外的正常口径不拦保存） */
    private Integer enabled;

    /** 创建人 uid */
    private String createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
