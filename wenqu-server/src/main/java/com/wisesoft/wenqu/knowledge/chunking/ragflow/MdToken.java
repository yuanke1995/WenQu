package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Markdown token 抽象（对应 markdown-it 的 token 对象）。
 * <p>
 * 能力差异：参考实现使用 markdown-it 的 token 流（含 {@code type} / {@code content} /
 * {@code tag} / {@code map} 等属性）；Java 侧不引入 markdown-it，由
 * {@link RagflowMdTokenizer} 以纯扫描方式产出等价的 token 序列。
 * <p>
 * 字段语义对齐 markdown-it：
 * <ul>
 *   <li>{@code type} —— token 类型（heading_open/paragraph_open/inline/fence/table_open/...）</li>
 *   <li>{@code content} —— 内联内容（inline/fence/math_block/html_block 等持有）</li>
 *   <li>{@code tag} —— 标签（heading_open 为 "h1".."h6"）</li>
 *   <li>{@code map} —— {@code [起始行号, 结束行号]}，解析异常时可为空</li>
 *   <li>{@code children} —— 内联子 token（列表项内的 inline 以扁平的 paragraph_open/inline/paragraph_close 三连表示）</li>
 * </ul>
 */
public final class MdToken {

    private final String type;
    private String content;
    private String tag;
    private int[] map;
    private List<MdToken> children = new ArrayList<>();

    public MdToken(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public String content() {
        return content;
    }

    public MdToken content(String content) {
        this.content = content;
        return this;
    }

    public String tag() {
        return tag;
    }

    public MdToken tag(String tag) {
        this.tag = tag;
        return this;
    }

    public int[] map() {
        return map;
    }

    public MdToken map(int[] map) {
        this.map = map;
        return this;
    }

    public List<MdToken> children() {
        return children;
    }
}
