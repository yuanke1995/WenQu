package com.wenqu.ai.service;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流事件链深登记（kb_write 防环）：记录「某文档由哪个链深的工作流 run 写入」，
 * 供 {@code WorkflowEventTriggerService} 在文档解析完成事件到达时计算下一次触发的链深——
 * 无登记（外部上传/重启丢失）按 1 层起算，超过上限拒绝派发，自动挡住「写入→解析完成→再触发→再写入」的自环雪崩。
 * <p>
 * 为什么是独立小 bean：登记方（WorkflowEngine）与消费方（WorkflowEventTriggerService，依赖 WorkflowService）
 * 若直接互持会成 Engine → Trigger → WorkflowService → Engine 构造环；收编到无依赖的第三方拆开。
 * <p>
 * 内存登记的边界：重启后登记丢失，链深按 1 重算——环不会静默放大（每一代都是真实 LLM 消耗，
 * 且新一代写入会重新登记），只是极端情况下熔断晚一轮；单实例部署假设内可接受。
 *
 * @author yuanke
 */
@Component
public class WorkflowEventChainRegistry {

    /** 登记有效期：文档解析通常分钟级完成，10 分钟足够覆盖；过期条目按外部文档对待 */
    private static final long TTL_MS = 10 * 60_000L;
    /** 链式触发上限：文档链第 N 代（外部上传=1）触发的工作流，其写入不再产生第 N+1 代触发 */
    public static final int MAX_CHAIN_DEPTH = 2;

    private record Entry(int nextDepth, long at) {
    }

    private final Map<String, Entry> writtenDocs = new ConcurrentHashMap<>();

    /** 登记一次工作流写入：nextDepth = 写入 run 的链深 + 1（事件命中时按它派发） */
    public void registerWrittenDoc(String docId, int nextDepth) {
        if (docId == null || docId.isBlank()) return;
        writtenDocs.put(docId, new Entry(nextDepth, System.currentTimeMillis()));
        if (writtenDocs.size() > 512) sweep();
    }

    /**
     * 文档解析完成事件对应的链深：有登记（工作流写入）按登记值；无登记按 1（外部上传为链头）。
     * 返回值 &gt; {@link #MAX_CHAIN_DEPTH} 时调用方应拒绝派发。
     */
    public int chainDepth(String docId) {
        Entry e = docId == null ? null : writtenDocs.get(docId);
        return e == null ? 1 : e.nextDepth;
    }

    /** 清理过期条目（惰性：登记量级小，写入超阈值才扫一轮） */
    private void sweep() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        Iterator<Map.Entry<String, Entry>> it = writtenDocs.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().at() < cutoff) it.remove();
        }
    }
}
