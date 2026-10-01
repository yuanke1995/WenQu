package com.wisesoft.ai.parser.ocr;

import java.nio.file.Path;
import java.util.List;

/**
 * PDF 深度解析引擎 SPI（{@code parse.ocrEngine} 的路由目标）。
 * <p>
 * 与「文本抽取 + 扫描件视觉兜底」的默认路径（none）互补：版面引擎把整份 PDF
 * 发给版面解析服务（PP-StructureV3 / MinerU），换回带标题层级与表格结构的
 * markdown 版面块，替代 PDFTextStripper 的错位行序文本——检索命中、引用 [N]
 * 贴引、QA 对生成都建立在它之上。
 * <p>
 * 约定（沿用解析链路既有约定，不新增配置面）：
 * <ul>
 *   <li><b>fail-loud 不降级</b>：服务不可用 / HTTP 错误 / 返回空结果，一律抛异常让整份解析失败，
 *       绝不静默回落文本层——版面解析"悄悄失败"等于内容静默残缺，比失败更糟；</li>
 *   <li>健康检查只做连通性与端点存在性探测（5s 级），不做真实解析探针；</li>
 *   <li>实现类为 Spring {@code @Component}，由 PdfParser 按 id 收集路由。</li>
 * </ul>
 *
 * @author yuanke
 */
public interface OcrEngine {

    /** 引擎标识（与 parse.ocrEngine 配置值一致） */
    String id();

    /**
     * 健康检查：{@code null} = 可用；非 {@code null} = 不可用原因（面向用户的中文描述，含修复指引）。
     * 解析前先调用，不可用直接 fail-loud，不进入真实解析白耗一份上传。
     */
    String checkHealth();

    /**
     * 整份 PDF 解析为版面页 markdown 列表。
     *
     * @param pdf      源文件路径（已持久落盘，解析期间保证存在）
     * @param fileName 原始文件名（仅用于日志与错误信息）
     * @return 非空列表（全空页/空结果的 fail-loud 由调用方统一判）
     */
    List<PageMarkdown> parse(Path pdf, String fileName) throws Exception;

    /**
     * 带进度回调的解析：耗时引擎在等待期上报解析进度（DocumentService 落到
     * c_ai_document.parse_progress/parse_desc，前端文档列表进度条消费）。
     * 默认实现不回调，保持与旧调用兼容。
     *
     * @param progress 进度回调（可为 null 表示不关心进度）
     */
    default List<PageMarkdown> parse(Path pdf, String fileName,
                                     com.wisesoft.ai.parser.DocumentParser.ParseProgress progress) throws Exception {
        return parse(pdf, fileName);
    }
}
