package com.wenqu.ai.parser.ocr;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.parser.DocumentParser;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 版面解析引擎并发闸：同一引擎同一时刻只放行一个解析任务，其余排队而非失败。
 * <p>
 * 背景：doc-parse 线程池为 fixed(2)，批量重新解析时多个任务并发打版面服务
 * （MinerU/PP 均为 CPU 自托管单实例，同步接口无内部排队），服务被打满后连
 * 5s 健康检查都超时，批量任务集体 fail-loud 失败。正确解法不是降级也不是
 * 放大超时，而是在调用方排队：一次只提交一份，等引擎空闲再发。
 * <p>
 * 并发数可配（parse.ocrGateConcurrency，默认 1）：自托管的单实例版面服务一次只能吃一份，
 * 默认守住 1；把解析并发（parse.concurrency）调大时，这里是真正的瓶颈，需要一起放宽才有用。
 *
 * 语义：
 * <ul>
 *   <li>按引擎 id 各一把闸（MinerU 与 PP-StructureV3 是不同服务，互不阻塞）；
 *       vision 走 LLM API 不参与。</li>
 *   <li>排队期间每 5s 经 ParseProgress 上报（进度恒 10%，desc 带已等秒数），
 *       用户在文档列表能看到在动、知道为什么慢。</li>
 *   <li>线程被中断（任务取消/关闭）时抛 BizException 退出排队，不吞中断。</li>
 *   <li>acquire/release 必须配对（调用方 try/finally）；持锁后才做健康检查，
 *       此时服务必然空闲，健康检查秒回，也避免「健康检查过了但 parse 时
 *       被别人打满」的窗口。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Component
public class OcrEngineGate {

    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final ConfigService configService;

    public OcrEngineGate(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 排队获取引擎独占权；获得后调用方负责在 finally 中 {@link #release}。
     *
     * @param engineId 引擎 id（mineru / pp_structure_v3）
     * @param label    展示名（MinerU / PP-StructureV3），仅用于进度文案与日志
     * @param fileName 文件名（仅用于日志与错误信息）
     * @param cb       进度回调（可为 null）
     */
    public void acquire(String engineId, String label, String fileName, DocumentParser.ParseProgress cb) {
        syncPermits(engineId);
        Gate gate = gates.computeIfAbsent(engineId, k -> new Gate(1));
        long waitedMs = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new BizException("「" + fileName + "」排队等待 " + label + " 时被取消");
            }
            if (gate.tryAcquire()) {
                if (waitedMs > 0) {
                    log.info("[OCR-GATE] {} 排队 {} 秒后获得 {} 独占权", fileName, waitedMs / 1000, label);
                }
                return;
            }
            try {
                Thread.sleep(500);
                waitedMs += 500;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException("「" + fileName + "」排队等待 " + label + " 时被取消");
            }
            if (cb != null && waitedMs % 5000 == 0) {
                cb.onProgress(10, "排队等待 " + label + " 空闲（已等 " + (waitedMs / 1000)
                        + " 秒；该引擎一次只解析一份，避免并发打满服务）");
            }
        }
    }

    /** 释放引擎独占权（与 acquire 配对；多调 release 会让信号量超发，调用方必须 try/finally） */
    public void release(String engineId) {
        Semaphore gate = gates.get(engineId);
        if (gate != null) gate.release();
    }

    /**
     * 按配置增减许可数（parse.ocrGateConcurrency，默认 1）。
     * 用 availablePermits 差值增减而不是重建信号量：重建会让排队中的线程对着旧信号量死等。
     */
    private void syncPermits(String engineId) {
        int want = Math.max(1, configService.getInt("parse.ocrGateConcurrency", 1));
        Gate gate = gates.computeIfAbsent(engineId, k -> new Gate(want));
        int have = gate.availablePermits();
        if (want > have) {
            gate.release(want - have);
        } else if (want < have) {
            gate.reduce(want - have);
        }
    }

    /** 并发闸：暴露 Semaphore 的 protected reducePermits（配置下调并发时要真的减少许可） */
    private static final class Gate extends Semaphore {
        Gate(int permits) {
            super(permits);
        }

        void reduce(int reductions) {
            reducePermits(reductions);
        }
    }
}
