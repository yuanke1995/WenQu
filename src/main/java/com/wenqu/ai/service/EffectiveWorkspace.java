package com.wenqu.ai.service;

import java.util.List;

/**
 * 一条会话<b>某一轮</b>的工作目录解析结果——工作目录的唯一真相源。
 *
 * <p>框架实例把文件系统、prompt、guard roots、技能根烤在 build 期；这些消费者必须读同一份解析结果，
 * 不允许各自查库再算一遍（那正是"key 变了但 FS 仍旧"的来源）。
 *
 * <p><b>不是持久状态</b>：{@code Session.ws_*} 只存已钉住的本机绑定，cloud / scratch 是本轮派生的瞬时后端。
 * {@link #key} 是全部影响 build 期状态的字段的稳定函数——任一字段变化都必须重建实例。
 */
public record EffectiveWorkspace(
        String sessionId,
        String uid,
        String key,
        Kind kind,
        String projectId,
        List<String> roots,
        String primaryRoot,
        String executorId,
        String scopeId,
        boolean serverSandbox,
        String fallbackNotice,
        String displayName) {

    public enum Kind {
        /** 用户建的本机目录工作区（直连执行器） */
        EXPLICIT_LOCAL,
        /** 用户建的 SSH 工作区 */
        EXPLICIT_SSH,
        /** 隐式会话，已钉住本机会话目录 */
        IMPLICIT_LOCAL,
        /** 隐式会话，服务器沙盒（scope=sessionId 或 uid） */
        IMPLICIT_CLOUD,
        /** 聊天档：宿主上的会话级临时目录，无 shell */
        CHAT_SCRATCH
    }

    public EffectiveWorkspace {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }

    public boolean explicit() {
        return kind == Kind.EXPLICIT_LOCAL || kind == Kind.EXPLICIT_SSH;
    }

    public boolean local() {
        return kind == Kind.EXPLICIT_LOCAL || kind == Kind.EXPLICIT_SSH || kind == Kind.IMPLICIT_LOCAL;
    }

    /** 显式工作区名；隐式会话没有工作区名，界面不显示标签。 */
    public String label() {
        return displayName == null ? "" : displayName;
    }
}
