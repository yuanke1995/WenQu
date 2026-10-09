package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话表实体
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_session")
public class Session {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（登录用户 uid；anonymous=历史兼容池，全局可见） */
    private String userId;

    /** 会话标题（取自首条用户问题前50字） */
    private String title;

    /** 消息条数 */
    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 是否置顶: 0=否,1=是 */
    private Integer isPinned;

    /** 是否收藏: 0=否,1=是 */
    private Integer isFavorite;

    /**
     * 会话级绑定的智能体（首问时锁定：显式选择或自动派遣的结果）。
     * NULL=尚未绑定（新会话未发过消息），空串=已决定不绑定智能体（走全局配置），有值=已锁定。
     * 锁定后本会话所有轮次都用它，人设/知识库/工具集全程一致（切换智能体 = 新会话）。
     */
    private String agentId;

    /** 绑定时的智能体名称快照（智能体改名/删除后会话仍可展示原名称） */
    private String agentName;

    /**
     * 会话级模型覆盖（聊天页手动切换模型时写入，引用 providerId/modelId 或遗留模型名）。
     * NULL=未切换过（跟随个人默认）。与智能体绑定不同：模型可随时切回，后续轮次均以会话已存覆盖优先。
     */
    private String model;

    /** 滚动历史摘要（上下文压缩：更早轮次压缩后的摘要，注入 prompt 的「早期对话摘要」段；NULL=未压缩过） */
    private String historySummary;

    /** 摘要已覆盖的最大消息 sequence（该序号及更早的原样历史已被摘要吸收，压缩时增量合并） */
    private Long summaryUntilSeq;

    /**
     * 会话当前任务清单（JSON：{@code {turnId,items:[{content,status}],updatedAt}}）。
     * <p>整表覆盖、只留最新一份：清单表达的是"现在还剩几步"，不是历史；历次写入由
     * {@code c_ai_session_event} 的 todo 事件留痕。NULL=本会话没有清单。
     */
    private String todos;
}
