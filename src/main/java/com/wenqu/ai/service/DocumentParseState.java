package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.model.AiDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析过程的进程内状态：删除标志、解析线程引用、进度节流值。
 * <p>
 * 从 {@link DocumentService} 抽出：这三份状态被解析主流程、回收站删除、版本回滚三处共用，
 * 放在任何一方都会让另一方反向依赖。语义保持单实例内存态——多实例场景由 DB 兜底感知
 * （删除后 selectById 为 null，解析线程在阶段检查点自行停止）。
 */
@Component
@RequiredArgsConstructor
public class DocumentParseState {

    private final AiDocumentMapper documentMapper;

    /** 解析进度节流守卫：docId -> 已上报 progress（值未变化不写库） */
    private final Map<String, Integer> progressGuard = new ConcurrentHashMap<>();
    /** 删除标志：delete() 立即置位，解析线程检查点秒查（不等 DB） */
    private final Map<String, Boolean> deletedFlags = new ConcurrentHashMap<>();
    /** 解析线程引用：delete() 时 interrupt 实现立即中断（图片 join 等待立即响应） */
    private final Map<String, Thread> parseThreads = new ConcurrentHashMap<>();

    public boolean isDeleted(String docId) {
        return deletedFlags.containsKey(docId);
    }

    public void markDeleted(String docId) {
        deletedFlags.put(docId, true);
    }

    public void clearDeleted(String docId) {
        deletedFlags.remove(docId);
    }

    public void bindThread(String docId, Thread thread) {
        parseThreads.put(docId, thread);
    }

    public Thread threadOf(String docId) {
        return parseThreads.get(docId);
    }

    public void unbindThread(String docId) {
        parseThreads.remove(docId);
    }

    public Integer lastProgress(String docId) {
        return progressGuard.get(docId);
    }

    public int lastProgressOrZero(String docId) {
        return progressGuard.getOrDefault(docId, 0);
    }

    public void setProgress(String docId, int progress) {
        progressGuard.put(docId, progress);
    }

    public void clearProgress(String docId) {
        progressGuard.remove(docId);
    }

    /**
     * 解析进度上报（节流：progress 未变化且非终态时不写库；只更新进度两字段，避免整行 update）。
     * <p>terminal=true 的终态描述（解析完成/失败/补描述失败）不许被「进度没变」节流吞掉：
     * 失败回退与补描述 fail-loud 都发生在进度已到过同一值之后（回退取 guard 现值、补描述恒 100），
     * 吞掉就是状态页永远停在「向量化 12/30」这类半程文案上。终态判定也不能靠 desc 字符串反推——
     * 实际文案从不等于「解析完成/解析失败」裸串，老判据在主路径上从来没命中过。
     */
    public void update(String docId, int progress, String desc, boolean terminal) {
        Integer last = progressGuard.get(docId);
        if (last != null && last.equals(progress) && !terminal) return;
        progressGuard.put(docId, progress);
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getParseProgress, progress)
                .set(AiDocument::getParseDesc, desc));
    }

    public void update(String docId, int progress, String desc) {
        update(docId, progress, desc, false);
    }
}
