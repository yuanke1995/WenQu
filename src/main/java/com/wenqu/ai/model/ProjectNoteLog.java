package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区约定留痕：每次约定被替换前，把**旧版**全文抄一行进来。
 *
 * <p><b>为什么留旧版而不是新版</b>：新版永远在 {@code c_ai_project.instructions} 里，
 * 留痕补的是「被换掉的那些」——历史才有信息量。NULL 内容=当时约定为空（首次写入前的状态），
 * 也是一条事实（「这个场子最初没有约定」）。
 * <p>恢复=把旧值再存一版（会再次触发留痕），与智能体配置版本管理同一条思路：
 * 最新版=当前配置，回滚即再存。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_project_note_log")
public class ProjectNoteLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属工作区（c_ai_project.id） */
    private String projectId;

    /** 被替换掉的那一版约定全文（NULL=当时为空） */
    private String content;

    /** 被替换的时刻 */
    private LocalDateTime createTime;
}
