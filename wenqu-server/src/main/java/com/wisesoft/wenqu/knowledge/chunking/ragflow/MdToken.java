package com.wisesoft.wenqu.knowledge.chunking.ragflow;

/**
 * 轻量 Markdown token 抽象（对应 markdown-it 的 token 对象）。
 * <p>
 * 能力差异：参考实现使用 markdown-it 的 token 流（含 {@code type} / {@code map} 等属性）；
 * Java 侧不引入 markdown-it，由语义解析器（后续批次移植）以纯扫描方式产出等价的 token 序列，
 * 此处仅保留 {@code extract_table_block} 所需的 {@code type} 与 {@code map} 两个字段。
 * <p>
 * {@code map} 语义同 markdown-it：{@code [起始行号, 结束行号]}，解析异常时可为 {@code null}。
 */
public record MdToken(String type, int[] map) {
}
