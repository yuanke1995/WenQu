package com.wenqu.ai.agent.runcode;

import java.util.List;
import java.util.Map;

/**
 * 沙盒里的脚本回调平台工具的通道（P3b）。
 * <p>
 * 为什么需要一层接口：DeepSeek Harness 的 {@code run_code} 与工具注册表**同进程**，脚本里的子调用
 * 就是一次函数调用；问渠的代码在 provisioner 容器里、工具在 JVM 里，子调用必须跨网络回来。
 * 这一层把"回来之后做什么"留在问答侧（守卫链、引用注册、工具卡片都在本轮状态里），
 * 容器侧只看到一个 HTTP 端点与一份由 {@link ToolStubRenderer} 生成的客户端。
 * <p>
 * 实现方是 {@code ChatService}（本轮唯一知道 raw 回调、身份快照与 TurnState 的地方）。
 * 刻意按轮回传而不是静态单例——P1 删掉的那对 {@code setSourceRegistrar/setSink} 就是因为跨轮共享
 * 会串号，这里同理会串。
 *
 * @author yuanke
 */
public interface RunCodeBridge {

    /** 归属三元组（凭据登记与日志用；不参与鉴权——鉴权看的是那枚一次性凭据） */
    String sessionId();

    String turnId();

    String userId();

    /**
     * 一个可被子调用触达的工具（生成 Python 函数要用到它的参数名与说明）。
     *
     * @param name        模型可见工具名（同时是 Python 函数名；非法标识符的名字只走 {@code call_tool}）
     * @param cardLabel   卡片展示名（写进函数 docstring，让模型知道这函数是干什么的）
     * @param params      参数名列表（顺序稳定）
     * @param docs        参数名 → 说明（docstring 用）
     * @param required    必填参数名
     */
    record Spec(String name, String cardLabel, List<String> params, Map<String, String> docs, List<String> required) {
    }

    /** 本轮可被脚本调用的工具清单（已排除 run_code 自己与人在工作侧才成立的那些） */
    List<Spec> callableSpecs();

    /**
     * 执行一次子调用。
     * <p>必须过与模型直接调用同一条守卫链（叫停闸、权限档位闸、审批闸、引用注册、工具卡片观测）——
     * 这是 P3b 的全部前提：脚本能绕过守卫，就等于给了一段代码一条无人看守的后门。
     *
     * @param tool     工具名
     * @param argsJson 入参 JSON（脚本侧序列化）
     * @return 工具结果文本
     * @throws RunLimitException 超出本轮子调用上限或已叫停（HTTP 侧转成结构化错误给脚本）
     */
    String invoke(String tool, String argsJson);

    /** 本轮子调用总上限（脚本内并发与串行都计在这里，超了就拒） */
    int maxSubCalls();

    /** 单个子调用的等待上限（毫秒）：脚本里一个卡死的子调用不该把整段运行拖到沙盒命令超时 */
    long subCallTimeoutMs();

    /** 本轮已发生的子调用次数（含被拒的）——说给模型听，它才知道自己扇出了多少；
     *  守卫按轮扣额度，换一段脚本不会把额度还回来，所以面板上标的是「本轮额度」 */
    int callsUsed();

    /**
     * 把这次运行的结构化结果（退出码/超时/沙盒故障/耗时/子调用数）交回问答侧，
     * 让它挂到 {@code run_code} 那张卡片上。默认不处理——卡片没有落点时不影响给模型的文本。
     */
    default void recordRunOutcome(com.wenqu.ai.agent.runcode.RunCodeService.Outcome outcome) {
    }
}
