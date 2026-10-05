package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体配置版本快照：每次保存落一行（人设/知识范围/工具集等调优字段的 JSON 快照）。
 * <p>
 * 语义约定（对齐工作流版本的「回滚=再存一版」可审计口径）：
 * <ul>
 *   <li>创建/保存/回滚都产生新版本，版本号同一智能体内从 1 起严格递增；</li>
 *   <li><b>最新版本 = 当前配置</b>（不变式：配置字段只经 create/update/rollback 改写，三处都落快照）；</li>
 *   <li>回滚 = 把历史版本配置重新保存一遍（落新版本，reason 记「回滚自 vX」）——历史不被改写，
 *       回滚动作本身也可被再次回滚；</li>
 *   <li>只快照「调优内容」（{@link com.wenqu.ai.service.AgentService#VERSION_FIELDS} 白名单），
 *       isDefault/shareConfig/manageable 等非调优元数据不进快照，回滚也不会动它们。</li>
 * </ul>
 * 保留份数可配（config: {@code agent.versionKeep}，默认 20），超出按版本号最小先删。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_version")
public class AgentVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 版本号（同一智能体内递增，从 1 起） */
    private Integer version;

    /** 配置快照（JSON：VERSION_FIELDS 白名单字段，值与当时 agent 行一致） */
    private String config;

    /** 产生该版本的操作人 uid */
    private String operator;

    /** 来源说明：创建 / 保存 / 回滚自 vX */
    private String reason;

    /** 快照时间 */
    private LocalDateTime createTime;
}
