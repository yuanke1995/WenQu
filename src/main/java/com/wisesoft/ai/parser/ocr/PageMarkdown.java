package com.wisesoft.ai.parser.ocr;

/**
 * 版面 OCR 引擎的单页/单段输出（markdown 形态）。
 * <p>
 * 表格以 HTML/markdown 表格语法原样保留在正文里（不拆行），图片引用是否回填由各引擎说明。
 * MinerU 输出整份 markdown 无页界，约定 page=1（跨块切分后标题沿用起始页语义，见 PdfParser）。
 *
 * @param page     页号（从 1 计；整份输出的引擎恒为 1）
 * @param markdown 该页/该段的 markdown 文本（空白段由引擎层过滤，不进入本列表）
 * @author yuanke
 */
public record PageMarkdown(int page, String markdown) {
}
