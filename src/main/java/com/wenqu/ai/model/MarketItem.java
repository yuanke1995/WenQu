package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 市场物品登记。一条 = 一个「可安装物」，当前只开放技能（{@code item_type=skill}），
 * 表结构按多物品类型设计（agent / workflow / mcp 预留），扩类型不改表。
 * <p>
 * <b>安装即复制</b>：payload 是上架时的快照包（{@code user-data/market/} 下），装完落进
 * 用户自己的资产目录，与市场条目再无引用关系；重复上架（同作者同 item_key）= 覆盖快照并更新版本。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_market_item")
public class MarketItem {

    public static final String TYPE_SKILL = "skill";
    /** 应用模板 = 一份智能体配置快照（+ 可选绑定工作流 DSL），安装 = fork 成自己的智能体 */
    public static final String TYPE_AGENT = "agent";
    /** 工作流：payload = {name, description, dsl}，安装 = 创建为自己的草稿 */
    public static final String TYPE_WORKFLOW = "workflow";
    /** 官方条目作者（内置模板种入市场用）：显示名固定「平台官方」，不可被用户删除 */
    public static final String AUTHOR_SYSTEM = "system";
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 物品类型: skill（预留 agent/workflow/mcp） */
    private String itemType;

    /** 稳定标识（skill=技能名；同一作者同名物品重复上架=更新同一条） */
    private String itemKey;

    /** 展示名 */
    private String name;

    /** 一句话简介（列表卡片） */
    private String summary;

    /** 详细介绍（详情页，Markdown） */
    private String description;

    /** 上架人（c_ai_user.uid；本人与管理员可管理） */
    private String authorUid;

    /** 标签（逗号分隔，供列表过滤） */
    private String tags;

    /** 版本号（纯展示；「有更新」不依赖它，见 {@link #contentHash}） */
    private String version;

    /** 快照内容指纹（zip 的 SHA-256）：与安装记录比对出「有更新」——忘升版本号也能检出内容变化，只改卡片文案不误报 */
    private String contentHash;

    /** 状态: online=上架中 offline=已下架 */
    private String status;

    /** 物品包相对路径（user-data/market 下；skill=zip 包） */
    private String payloadPath;

    /** 累计安装次数（覆盖重装也计入） */
    private Integer installCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
