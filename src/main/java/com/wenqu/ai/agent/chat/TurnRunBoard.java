package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当作已退役的控制流删除。

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本轮沙盒脚本的<b>运行面板</b>（P3b；P6 刀 0 第二步从手写运行时的平铺字段下沉，
 * 行为与簿记本体在 {@code agent/runcode/RunCodeSubCallRuntime}）。
 *
 * <p><b>作用域是一次运行，不是本轮</b>：{@code RunCodeSubCallRuntime#startRunBoard} 在每次 run_code
 * 起头清空/复位这些字段，否则同一轮里的第二段脚本，卡片会显示成第一段做过的事。
 * 之所以仍然挂在轮上而不是每次新建：面板 ticker、子调用线程与终态卡片都要在<b>没有共同闭包</b>的
 * 三个位置之间交接同一个计数袋，按轮存一处比传三份对象更接近"谁都能看见同一份"。
 *
 * <p>刻意不各开顶层工具卡、也不进模型直调的工具卡清单：那是"模型自己做的操作"的清单，
 * 一段脚本扇出的几十次调用混进去会把过程区刷成一堵墙，也让人看不出它们属于同一次运行。
 *
 * @author yuanke
 */
public final class TurnRunBoard {

    /** 本段脚本的子调用清单：一条一次调用一行，随 run_code 那张卡片下发与落库 */
    public final List<Map<String, Object>> subCallLog = Collections.synchronizedList(new ArrayList<>());
    /** 本段脚本<b>正在途</b>的子调用数：start 加、终态减，随每次 run_code 起头复位 */
    public final AtomicInteger subCallInFlight = new AtomicInteger();
    /** 在途子调用按工具名的计数（同名并发合成一条「在跑×N」），终态即摘名 */
    public final Map<String, Integer> subCallRunning = Collections.synchronizedMap(new LinkedHashMap<>());
    /** 面板有变化待下发：子调用线程只置脏，实际 send 由 ticker 每秒合并一帧 */
    public final AtomicBoolean subCallDirty = new AtomicBoolean();
    /** 本轮已发生的子调用次数（子调用的独立预算计数，不进模型的单轮步数） */
    public final AtomicInteger subCallCount = new AtomicInteger();
    /** 本段脚本的面板 ticker 句柄（每次 run_code 起头新建，终态与整轮收尾取消） */
    public volatile ScheduledFuture<?> runProgress;
    /** 本段脚本还在跑：false 时 ticker 自停，不留一个空转的周期任务 */
    public volatile boolean runBoardActive;
    /** 本段脚本运行的结构化结果（退出码/超时/沙盒故障），随 run_code 终态卡片下发并落库 */
    public volatile Map<String, Object> runOutcome;
}
