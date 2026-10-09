package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息表实体
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_message")
public class Message {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话ID */
    private String sessionId;

    /** 角色: user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 思考过程全文（深度思考） */
    private String thinking;

    /** 关联图片URL (JSON数组字符串) */
    private String images;

    /** 引用来源 (JSON数组字符串: [{ref,knowledgeId,docId,fileName,title,snippet}]) */
    private String sources;
    /** 检索状态行数据 (JSON: keywords/refs/terms) */
    private String retrieved;
    /** 相关推荐问句 (JSON数组字符串)：此前只随 done 事件下发，刷新后"接下来可以"整块消失 */
    private String related;
    /** 产物交付 (JSON数组: [{url,filename,size,description}]，原始 URL 存库、展示层签名) */
    private String artifacts;

    /** 工具调用过程 (JSON数组: [{name,status,elapsedMs,args,result|error}]) */
    private String toolCalls;

    /**
     * 回答时间线 (JSON数组: [{kind:'text',from,to} | {kind:'tool',i} | {kind:'artifact',i}])。
     * 记录正文与工具卡片/产物卡片的交错顺序：文本段只存 content 的字符区间，工具段存 toolCalls 下标，
     * 产物段存 artifacts 下标。刷新/历史会话据此还原「边想边做」的交错视图（不落库则只能整段正文+底部汇总）。
     */
    private String timeline;

    /**
     * 过程独白全文 (<process> 标签内的模型思考叙述，与正文分流)。
     * 时间线 process 段 {kind:'process',from,to} 的区间指向本字段，前端灰字弱化渲染、不与正文混淆。
     */
    private String processText;

    /** Token 用量 (JSON: context/budget/hits/output/prompt/outputIsReal/total)，随助手消息落库供历史回看 */
    private String tokens;

    /** 本轮生效模型引用（助手消息落库；用量统计按模型分组的依据，存量行为 NULL 归入未知） */
    private String model;

    /** 附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体，气泡回显) */
    private String attachments;

    /**
     * 本轮生效的智能体（助手消息才有值；取自会话级绑定的当轮快照）。
     * 落库意义：回答归属可追溯（"这条是谁答的"），且智能体改名/删除后历史仍如实回显。
     */
    private String agentId;

    /** 本轮智能体名称快照（与 agentId 同时落库，供气泡/历史直接展示） */
    private String agentName;

    /** 消息序号 (会话内递增) */
    private Integer sequence;

    /**
     * 分支版本组键（编辑重发/重新生成）：同一位置的各版本代表消息共用一个组。
     * 代表消息 = 编辑分支的新用户消息 / 重新生成分支的新旧回答；组内按 sequence 排序即版本序列，
     * 其中 deleted=0 的是当前激活版本。NULL=不属于任何版本组。
     */
    private String variantGroup;

    /**
     * 该版本被替换时刻的可见尾部消息 ID 快照（JSON 数组，含本条）。
     * 切回该版本时按快照精确恢复整条分支（嵌套子分支也一并还原）；
     * sessionRetentionDays 物理清理后快照失效，切换接口返回「已过保留期」。
     */
    private String variantTail;

    /**
     * @ 引用（用户显式 @ 的知识库/文档/智能体）：用户消息常驻标注，刷新/历史回显均保留。
     * 此前仅作轮级上下文注入、不落库，导致刷新后 @ 引用从记录消失、重新生成悄悄丢引用——本列修复该问题。
     * JSON 数组字符串：[{type:'kb'|'doc'|'agent', id, name, kbId}]（type=doc 时 kbId 由服务端回填）。
     */
    private String mentions;

    /**
     * # 历史引用（用户从本会话历史显式挑选的问答）：用户消息常驻标注，刷新/历史回显均保留。
     * JSON 数组字符串：[{messageId, role, content}]（role/content 由服务端按库回填，不采信客户端传值）。
     */
    private String historyRefs;

    /**
     * 计划批准卡 (JSON: {plan,status})：计划模式（人在回路）本轮的执行计划与裁决结果随助手消息落库，
     * 刷新/历史回显按轮重建气泡里的计划卡（此前计划只活在实时流里，刷新即丢、只留得住最新一张待确认）。
     * status=approved（plan 为可能被用户编辑过的批准稿）/ rejected（plan 为模型原稿）；NULL=本轮未走计划模式。
     */
    private String plan;

    /**
     * 本轮终止原因（记在**用户提问**上）：{@code stopped}=用户叫停且本轮没产出任何回答。
     * <p>为什么记在问上而不是落一条「已停止」的空回答：仓库既有口径是空的回答留在对话里比不留更碍眼
     * （分享页同款处理）；而「这一问没有回答」本就是那一问的事实。半程回答/只有工具卡的轮次不走这里
     * ——那些已经有 assistant 消息带着「⏹ 已停止本轮」标记可看。
     */
    private String endReason;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
