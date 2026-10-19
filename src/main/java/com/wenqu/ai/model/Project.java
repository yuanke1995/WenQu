package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区容器：把「一个工作目录 + 若干次会话 + 若干份产物」收在一个可持续的根下。
 *
 * <p><b>为什么需要它</b>：问渠的执行能力早就是工作区级的（沙盒执行、多步任务、产物交付），
 * 但容器停在会话级（沙盒目录按 {@code sessions/{thread_id}} 切分，跨会话互不可见），
 * 成果停在用户级（{@code c_ai_artifact} 是一张 {@code uid} 下的扁平表）。
 * 于是「换个会话 = 换一个世界」——文件不跟过去、成果回不到上下文。
 * 本类补的是中间那个缺失的聚合根。
 *
 * <p><b>三条设计边界</b>（与 {@code schema.sql} 的建表注释同源，改一处必须改另一处）：
 * <ol>
 *   <li>归属 {@code uid}，<b>不是租户也不是部门</b>：问渠是单公司私有化产品
 *       （{@code tenantId} 全仓 0 命中），引入共享工作区等于引入一套新权限模型；</li>
 *   <li><b>不是权限边界</b>：{@link #id} 只做归属与过滤，任何检索/可见性判定都不读它；</li>
 *   <li><b>不是必填概念</b>：每人一个 {@code implicit} 兜底容器由后台懒创建，
 *       用户不建工作区也能照常问答，只是产物开始有归属。</li>
 * </ol>
 *
 * <p><b>删除语义</b>：删工作区<b>不删</b>其下会话与产物，只把它们的 {@code project_id} 置 {@code NULL}
 * ——{@code Artifact} 的类注释写着「会话可以删…但产出的成果仍归人生」，级联删除会直接违反它。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_project")
public class Project {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（c_ai_user.uid；单用户容器，不做共享） */
    private String uid;

    /** 工作区名；implicit 容器可为空（界面上不给它起名，只体现为侧栏「会话」栏） */
    private String name;

    /**
     * {@link #SELECTION_IMPLICIT} / {@link #SELECTION_SELECTABLE}。
     * <p>implicit 每人最多一个、由后台懒创建、不进切换器、不可删；selectable 由用户显式创建。
     */
    private String selectionStatus;

    /**
     * 工作区目录：相对 user-data 根的 <b>POSIX 相对路径</b>（形如 {@code projects/{id}}）。
     * <p><b>用 id 而不是工作区名</b>：{@code normalize_workdir_path} 会拒绝绝对路径与非法分量，
     * 但把用户可编辑的字符串放进沙盒路径是自找边界问题——重命名也不该动目录。可读性交给前端。
     */
    private String workdirPath;

    /** managed=平台托管目录 / linked=关联用户已有目录（暂未实现，保留位） */
    private String directoryMode;

    /**
     * 工作区约定：写一次即对该工作区<b>所有会话</b>生效（每轮注入 system prompt 的工作背景段）。
     * <p>这是「工作区」与「分类标签」的分界——没有它，用户在同一个工作区里每开一条对话都要重新交代
     * 背景与规矩，那它就只是个筛选条件。
     */
    private String instructions;

    /**
     * 工作区默认助手：会话归属该工作区、且用户没显式指定助手时用它作答。
     * <p>工作区因此拥有了自己的资料域（助手上绑的知识库范围）与工具集，而不只是一个名字。
     * <p>可空：空 = 沿用平台默认智能体。
     */
    private String defaultAgentId;

    /** active=正常 / archived=归档（只读保留，二期） */
    private String status;

    /** 逻辑删除（MyBatis-Plus 全局 logic-delete-field） */
    @TableLogic
    private Integer deleted;

    /** 幂等创建键：自动建 implicit 容器时防并发重复插（唯一索引 uk_uid_idem 兜底） */
    private String idempotencyKey;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---------- 常量（与 schema.sql 的 CHECK 语义一致；Java 侧不重复做枚举校验，只在此声明取值） ----------

    /** 自动创建的兜底容器：每人一个、不可删、不进切换器 */
    public static final String SELECTION_IMPLICIT = "implicit";

    /** 用户显式创建的工作区：可选中、可删 */
    public static final String SELECTION_SELECTABLE = "selectable";

    public static final String STATUS_ACTIVE = "active";

    /** 已归档：容器收工，会话与产物一条不动，只是不再出现在"正在推进"的列表里 */
    public static final String STATUS_ARCHIVED = "archived";

    public static final String MODE_MANAGED = "managed";

    /** implicit 容器的幂等键固定值：{@code (uid, idempotency_key)} 唯一索引保证每人只有一行 */
    public static final String IMPLICIT_IDEMPOTENCY_KEY = "__implicit__";

    /**
     * implicit 兜底容器的工作目录：{@code projects/{id}}。
     * <p>为什么不用 {@code projects/default}：同一部署里可能有多位用户，路径必须按工作区 id 分片，
     * 否则两个用户的兜底容器会落到同一个盘上目录。
     */
    public String resolveWorkdirPath() {
        return "projects/" + id;
    }
}
