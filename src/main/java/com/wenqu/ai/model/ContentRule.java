package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内容安全规则（Phase 3 · P12）。
 *
 * <p>合规口径属于<b>部署方</b>而不是代码：哪些词、哪些模式该拦，各机构各行业的判断不同，
 * 所以规则一律以数据存在这张表里、由管理员在界面上增删改，代码里不写死任何一条拦截词。
 *
 * <p>{@code stage} 决定这条规则作用在闸门的哪一侧：{@code input} 只判用户打进来的字，
 * {@code output} 只判模型吐出来的字，{@code both} 两侧都判。输出侧的命中会把这一轮当场截断，
 * 已流到屏幕上的字收不回来，所以出口规则宜先用 {@code warn} 观察一段时间再改 {@code block}。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_content_rule")
public class ContentRule {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID（Phase 4 多租户启用，当前为空） */
    private String tenantId;

    /** 规则名：审计事件与拦截文案都点名它，必须是能读懂的一句话 */
    private String name;

    /** 类目：把同类规则归一起便于统计（如「个人隐私」「违法信息」），不参与判定 */
    private String category;

    /** 作用侧：input=只判提问 output=只判回答 both=两侧都判 */
    private String stage;

    /** 匹配方式：keyword=字面包含 regex=正则 */
    private String matchType;

    /** 关键词（多个用逗号/换行分隔）或正则表达式 */
    private String pattern;

    /** 命中动作：block=拦下（提问被拒 / 回答截断） warn=放行只记审计 */
    private String action;

    /** 1=区分大小写；0=忽略（只对拉丁字母有意义，默认 0） */
    private Integer caseSensitive;

    /** 1=生效 */
    private Integer enabled;

    /** 排序号：升序判定，先命中的那条说话（同一批规则里给「更具体的一条」排前面） */
    private Integer priority;

    /** 依据：为什么定这条规则（合规检查要看得见出处） */
    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
