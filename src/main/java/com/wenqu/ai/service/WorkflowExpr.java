package com.wenqu.ai.service;

import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 条件分支表达式求值（M1）：<b>SpEL 子集 + SimpleEvaluationContext</b>。
 * <p>
 * 为什么锁 SimpleEvaluationContext：表达式由工作流创建者编写、随 DSL 存储与导入导出，
 * StandardEvaluationContext 支持 {@code T()} / 构造器 / Bean 引用——等于把任意代码执行
 * 开放给所有能建工作流的人。Simple 上下文只放行属性访问 / 集合索引 / 被导航对象上的实例方法，
 * 类型引用与构造器在编译期即被拒绝（验证脚本有断言）。
 * <p>
 * 语法：变量引用写 {@code {{nodeId.key}}}（与提示词模板同语法），求值前替换为 SpEL 变量
 * {@code #refs['nodeId.key']}——引用值作为整体进变量表，<b>不做字符串内插</b>，
 * LLM 回答里的引号/换行不影响表达式解析。常用写法：
 * <pre>
 *   {{llm.answer}} 的长度判断        {{llm.answer}}.length() &gt; 0      → #refs['llm.answer'].length() &gt; 0
 *   等值                             {{llm.answer}} == 'yes'
 *   包含                             {{llm.answer}}.contains('成功')
 *   空判断                           {{retrieval.text}}.isEmpty()
 *   组合                             A and B / A or B / not A / &gt; &lt; &gt;= &lt;= !=</pre>
 * 注意 SpEL 优先级：not/! 高于比较——取反比较要加括号（{@code not ({{x}} > 1)}）。
 * 求值结果非 Boolean 时按「非空即真」裁决（null/空串/空集合为假）——容错口径与画布表单的直觉一致。
 *
 * @author yuanke
 */
final class WorkflowExpr {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    /** 编译缓存：同一 DSL 快照重复运行（调试期高频）不必反复解析同一表达式 */
    private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

    private WorkflowExpr() {
    }

    /**
     * 求值条件表达式。
     *
     * @param expr 条件表达式（可含 {{nodeId.key}} 引用；空表达式一律 false，路由交给 else 分支）
     * @param refs 引用值表（键为 "nodeId.key"，值为渲染后的原始对象，非字符串）
     * @return 命中与否
     * @throws com.wenqu.ai.common.BizException 语法错误 / 引用了未提供的值 / 结果无法裁决（fail-loud，错误含表达式原文）
     */
    static boolean eval(String expr, Map<String, Object> refs) {
        if (expr == null || expr.isBlank()) return false;
        String spel = toSpel(expr.trim());
        Expression compiled = CACHE.computeIfAbsent(spel, s -> {
            try {
                return PARSER.parseExpression(s);
            } catch (Exception e) {
                throw new com.wenqu.ai.common.BizException("条件表达式无法解析：" + e.getMessage()
                        + "（表达式：" + com.wenqu.ai.service.WorkflowRunCtx.abbreviate(expr, 200) + "）");
            }
        });
        try {
            // withInstanceMethods：Builder 默认不带任何方法解析器（.length()/.contains() 会 EL1004E）；
            // 显式启用的是 DataBindingMethodResolver——仅放行实例方法、禁静态方法，T() 依旧无路可走
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
            ctx.setVariable("refs", refs);
            Object v = compiled.getValue(ctx);
            return truthy(v);
        } catch (SpelEvaluationException e) {
            throw new com.wenqu.ai.common.BizException("条件表达式求值失败：" + e.getMessage()
                    + "（表达式：" + com.wenqu.ai.service.WorkflowRunCtx.abbreviate(expr, 200) + "）");
        }
    }

    /** {@code {{nodeId.key}}} → {@code #refs['nodeId.key']}（与引擎 VAR_REF 同一正则口径） */
    private static String toSpel(String expr) {
        java.util.regex.Matcher m = WorkflowEngine.VAR_REF.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("#refs['" + m.group(1) + "." + m.group(2) + "']"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 非 Boolean 结果的裁决：null / 空串 / 空集合为假，其余为真 */
    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof java.util.Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        return true;
    }
}
