package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区源文件夹：工作区不再是一个「服务器上的托管目录」，而是用户电脑上的一个或多个真实目录。
 *
 * <p>为什么是多行而不是一列 JSON：工作区文件页要按目录做单层浏览、移动与删除，每一次寻址都要
 * 「别名 → 绝对路径」的稳定映射；放 JSON 里每次读写都要整段解析，且无法为「同一台机器上不能重复绑定
 * 同一目录」建唯一约束。表结构把这条关系显式化。
 *
 * <p>{@link #primaryRoot}=1 的那一行是主目录：框架的 {@code cwd}、相对路径与命令执行都以它为根；
 * 其余目录是附加根（DSH 的 additional workspace roots），模型用绝对路径或别名访问。
 * 所有根都在同一台执行器上（{@link Project#executorId}），跨机器的工作区不成立。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_project_root")
public class ProjectRoot {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（与工作区同口径；查询按 project 走，uid 做双保险） */
    private String uid;

    /** 所属工作区（c_ai_project.id） */
    private String projectId;

    /** 展示名 / 虚拟路径首段（唯一，不含斜杠；默认取目录名，重名时自动加序号） */
    private String alias;

    /** 执行器上的绝对路径（POSIX 或 Windows 绝对路径，由执行器再校验一次） */
    private String path;

    /** 1=主目录（cwd 与相对路径的基准）；一个工作区有且只有一个 */
    private Integer primaryRoot;

    /** 展示顺序（0 起） */
    private Integer sortNo;

    private LocalDateTime createTime;

    public static final int PRIMARY = 1;

    public static final int ADDITIONAL = 0;
}
