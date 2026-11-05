package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区：<b>用户自己电脑上的一个或多个真实目录</b>，加上若干次会话与产物。
 *
 * <p><b>工作区不再是服务器上的容器</b>：文件只存在于用户电脑（{@link ProjectRoot} 记录绝对路径），
 * 读写由那台电脑上的本机执行器完成；服务器只存「哪台机器、哪些目录、叫什么」与会话/产物归属。
 * 因此本表没有 {@code workdir_path} / {@code directory_mode} / {@code root_path} 这类服务器目录列，
 * 也不存在「托管目录」与「本机目录」两种形态并存——用户建的工作区只有本机目录这一种。
 *
 * <p>三条边界：
 * <ol>
 *   <li>归属 {@code uid}，不是租户也不是部门；不是权限边界，只做归属与过滤；</li>
 *   <li>一个人可以有多个工作区，每个工作区绑定同一台执行器上的一个或多个目录；</li>
 *   <li>删除工作区只摘归属，<b>绝不删用户硬盘上的文件</b>。</li>
 * </ol>
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

    /** 工作区名；implicit 容器可为空（界面上就是侧栏「会话」栏） */
    private String name;

    /**
     * {@link #SELECTION_IMPLICIT} / {@link #SELECTION_SELECTABLE}。
     * <p>implicit 每人最多一个、由后台懒创建、不可删、只用于「没挂工作区」的会话归属；
     * selectable 是用户显式创建的本机工作区。
     */
    private String selectionStatus;

    /**
     * 本机执行器 id（c_ai_executor.id）：工作区的所有目录都在这台电脑上，文件/命令工具经它执行。
     * <p>用户建的工作区必填；implicit 容器为空（它没有目录，会话走服务器沙盒）。
     */
    private String executorId;

    /** 工作区图标（预设图标名；空=默认文件夹） */
    private String icon;

    /** 工作区颜色（十六进制；空=默认色） */
    private String color;

    /**
     * 记忆隔离：1=本区会话不注入也不写入个人记忆。
     */
    private Integer memoryIsolated;

    /** active=正常 / archived=归档（只读保留） */
    private String status;

    /** 逻辑删除（MyBatis-Plus 全局 logic-delete-field） */
    @TableLogic
    private Integer deleted;

    /** 幂等创建键：自动建 implicit 容器时防并发重复插（唯一索引 uk_uid_idem 兜底） */
    private String idempotencyKey;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---------- 常量 ----------

    /** 自动创建的兜底容器：每人一个、不可删、不进工作区列表 */
    public static final String SELECTION_IMPLICIT = "implicit";

    /** 用户显式创建的工作区：可选中、可删 */
    public static final String SELECTION_SELECTABLE = "selectable";

    public static final String STATUS_ACTIVE = "active";

    /** 已归档：容器收工，会话与产物一条不动，只是不再出现在"正在推进"的列表里 */
    public static final String STATUS_ARCHIVED = "archived";

    /** implicit 容器的幂等键固定值 */
    public static final String IMPLICIT_IDEMPOTENCY_KEY = "__implicit__";
}
