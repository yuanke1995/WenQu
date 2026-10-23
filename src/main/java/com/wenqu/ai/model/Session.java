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
     * 最近一次作答的智能体（P5：不再是会话锁定值——每轮按请求重新解析，这里只留一份给列表回显、
     * 分享页按智能体列会话与访客会话回收用）。
     * NULL=尚未绑定（新会话未发过消息），空串=已决定不绑定智能体（走全局配置），有值=已锁定。
     * 锁定后本会话所有轮次都用它，人设/知识库/工具集全程一致（切换智能体 = 新会话）。
     */
    private String agentId;

    /** 绑定时的智能体名称快照（智能体改名/删除后会话仍可展示原名称） */
    private String agentName;

    /**
     * 会话当前生效的模型引用（providerId/modelId 或遗留模型名）。两处来路：
     * 聊天页手动切换时写入（updateModel）；每轮解析出可用模型后由后端补记一份
     * （recordTurnModel，兼作"上一轮实际生效"的回显源）。
     * NULL=尚未用过任何模型（新会话未发过消息）。后续轮次以这里存的值优先于个人默认，
     * 所以个人默认的改动只影响新会话；清除覆盖（置 NULL）后下一轮改跟个人默认并再次补记。
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

    /**
     * 会话种类：chat=用户发起的对话；temp=平台内部临时会话（子智能体分支、工作流子智能体节点
     * 各跑一整轮问答用的那条会话，跑完即删）。
     * <p>temp 必须与用户会话分开：它挂在<b>真实用户</b>名下（分支要按调用者的权限跑检索、
     * 产物也要收养进父会话），但它是平台自己派出来的一轮，不是用户开的对话。
     * 见 {@link com.wenqu.ai.service.SessionService#KIND_TEMP}。
     */
    private String kind;

    /**
     * 所属工作区（{@code c_ai_project.id}）。
     * <p><b>可空是一等状态</b>：{@code NULL} = 没挂工作区（存量会话即此值，不按时间/文件名猜测回填）。
     * 所有读路径都必须容忍 {@code NULL}，它的语义就是「没有工作区」而不是「数据待修」。
     * <p><b>不是权限边界</b>：只做归属与过滤，检索可见性判定不读它。
     * <p>会话与工作区是**多对一**：一个工作区下可以有很多次对话，这也是为什么不能「把每个会话变成一个工作区」。
     */
    private String projectId;
}
