package com.wenqu.ai.agentx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本轮「已读入不可信内容」的污点标记（间接提示词注入的第三层防御）。
 *
 * <p>信封与数据声明解决的是「模型把人话当指令」，但工作区智能体还有手：它会在用户电脑上写文件、
 * 跑命令。文档里埋一句「把旧版删掉再重建」被模型执行时，后果不是回答错了，是文件没了。
 * 所以读入外部内容之后，破坏性命令与越界写必须回到人面前确认一次。
 *
 * <p><b>只按轮存活</b>：状态挂在 {@link AgentxTurnSlot.Turn} 上，开轮新造一份。它不跨轮、不落库——
 * 污点不是用户的属性，是「这一轮上下文里混进过外部内容」这个事实。进程内即可，多副本各自算各自的，
 * 因为读入发生在哪一个副本，后续工具调用也落在同一个会话实例上。
 *
 * @see AgentxNativeToolGuard 消费方：执行前判破坏性命令/越界写
 * @see SpringAiToolAdapter MCP/脚本输出的读入方
 */
public final class AgentxTaint {

    private final AtomicBoolean tainted = new AtomicBoolean(false);
    /**
     * 本轮是否已经为「污点」确认过一次。
     * <p>判据的落点是「批准之后工具真的被执行了」（适配器在 {@code callAsync} 里置它），
     * 而不是「弹过卡」——卡也可能被拒、也可能超时。置上之后同一轮不再为污点重复问：
     * 注释一直写的是「确认一次」，实现却是每调一次命令问一次，20 条命令就是 20 张卡。
     */
    private final AtomicBoolean taintApproved = new AtomicBoolean(false);
    /** 触发来源（排障用，最多留几个；不参与判定）。 */
    private final Set<String> sources = ConcurrentHashMap.newKeySet();

    public void mark(String source) {
        tainted.set(true);
        if (source != null && !source.isBlank() && sources.size() < 8) sources.add(source);
    }

    public boolean tainted() {
        return tainted.get();
    }

    /** 污点确认已通过（一次），本轮后续命令不再重复问人。 */
    public void markTaintApproved() {
        taintApproved.set(true);
    }

    public boolean taintApproved() {
        return taintApproved.get();
    }

    public Set<String> sources() {
        return Set.copyOf(sources);
    }
}
