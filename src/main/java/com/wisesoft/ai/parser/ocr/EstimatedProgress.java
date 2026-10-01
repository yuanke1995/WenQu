package com.wisesoft.ai.parser.ocr;

import com.wisesoft.ai.parser.DocumentParser;

/**
 * 版面引擎（MinerU / PP-StructureV3）整份提交期间的<b>时间估算进度</b>。
 * <p>
 * 两个版面服务都是「整份 PDF 一次提交、结果整包返回」，没有逐页回调可订阅——
 * 等待期若不上报，前端进度会卡在「解析文档内容」十分钟级不动，用户无从判断
 * 是在解析还是挂了。本类按「页数 × 经验单页耗时」估出总时长，后台线程每 10s
 * 把进度从 12 推到 28（10=解析入口、30=分块完成，为 DocumentService 的阶段刻度），
 * 文案带已等待与预计时间。纯展示性估算，不影响解析本身；解析结束（正常/异常）
 * 必须 {@link #close()}，防止线程泄漏与终态后的迟到上报。
 *
 * @author yuanke
 */
final class EstimatedProgress implements AutoCloseable {

    /** 经验单页耗时：MinerU pipeline / PP-StructureV3 在 CPU 自托管下约 10s/页（2026-10 实测） */
    static final long PER_PAGE_MS = 10_000L;

    private final DocumentParser.ParseProgress cb;
    private final String engineLabel;
    private final long estimateMs;
    private final long t0 = System.currentTimeMillis();
    private final Thread thread;
    private volatile boolean done;

    private EstimatedProgress(DocumentParser.ParseProgress cb, String engineLabel, long estimateMs) {
        this.cb = cb;
        this.engineLabel = engineLabel;
        this.estimateMs = estimateMs;
        this.thread = new Thread(this::tick, "ocr-estimate-" + engineLabel);
        this.thread.setDaemon(true);
    }

    /**
     * 启动估算进度：先同步上报起点（含页数与预计时长），再起后台线程每 10s 推进。
     *
     * @param raw 引擎收到的回调（可为 null，内部转 no-op）
     */
    static EstimatedProgress start(DocumentParser.ParseProgress raw, String engineLabel,
                                   int pages, long perPageMs) {
        DocumentParser.ParseProgress cb = raw != null ? raw : (p, d) -> { };
        long est = Math.max(5_000L, pages * (long) perPageMs);
        cb.onProgress(12, engineLabel + " 版面解析中（" + pages + " 页，预计约 " + fmt(est) + "）");
        EstimatedProgress ep = new EstimatedProgress(cb, engineLabel, est);
        ep.thread.start();
        return ep;
    }

    private void tick() {
        while (!done) {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                return;
            }
            long elapsed = System.currentTimeMillis() - t0;
            int percent = (int) Math.min(28, 12 + 16 * elapsed / estimateMs);
            cb.onProgress(percent, engineLabel + " 版面解析中（已等待 " + elapsed / 1000
                    + "s / 预计约 " + fmt(estimateMs) + "）");
        }
    }

    @Override
    public void close() {
        done = true;
        thread.interrupt();
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + " 分钟" : s + "s";
    }
}
