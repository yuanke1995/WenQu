package com.wisesoft.ai.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 内置高频工具（Function Calling / Tool Calling）。
 * <p>
 * 补齐「模型手算不准 / 不知道今天几号」这类硬伤：知识库问答里出现金额合计、百分比、
 * 天数推算时，交给工具算比让模型口算可靠得多。默认关闭（tool.builtin.enabled）。
 * <p>
 * 安全设计：表达式求值**自实现递归下降解析**，不引入任何脚本/表达式引擎
 * （可执行任意代码，不能把用户输入直接喂进去）。仅支持数字与 + - * / % ^ 括号，
 * 无变量、无函数、无反射、无类加载能力。
 *
 * @author yuanke
 */
@Component
public class BuiltinTools {

    /** 表达式最大长度（防超长输入把工具调用变成 DoS） */
    private static final int MAX_EXPR_LEN = 200;

    /**
     * 计算算术表达式（+ - * / % ^ 与括号，支持小数与负数）。
     *
     * @param expression 算术表达式，如 "(1280 + 360) * 0.85"
     * @return 计算结果（整数不带小数；小数最多 6 位）或错误原因
     */
    @Tool(description = "计算算术表达式（支持 + - * / % ^ 与括号、小数、负数）。"
            + "涉及金额合计、百分比、增长率、平均值、单位换算等需要精确数值时调用本工具，不要自己口算。")
    public String calculate(@ToolParam(description = "算术表达式，如 (1280 + 360) * 0.85") String expression) {
        if (expression == null || expression.isBlank()) return "错误：表达式为空";
        String expr = expression.trim();
        if (expr.length() > MAX_EXPR_LEN) return "错误：表达式过长（最多 " + MAX_EXPR_LEN + " 字符）";
        try {
            double v = new Parser(expr).parse();
            if (Double.isNaN(v) || Double.isInfinite(v)) return "错误：结果不是有限数（可能除零或溢出）";
            return fmt(v);
        } catch (ArithmeticException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "错误：无法解析表达式「" + expr + "」（仅支持数字与 + - * / % ^ 括号）";
        }
    }

    /**
     * 获取当前日期时间（可指定时区）。
     *
     * @param zone 时区（如 Asia/Shanghai），留空用系统默认
     * @return 当前日期时间与星期
     */
    @Tool(description = "获取当前日期时间（可指定时区）。回答涉及今天/本周/本月/截止日期/有效期推算时，"
            + "必须先调用本工具确认当前时间，不要凭空猜测日期。")
    public String currentDateTime(@ToolParam(description = "时区，如 Asia/Shanghai；留空用系统默认", required = false) String zone) {
        try {
            ZoneId zid = (zone == null || zone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
            LocalDateTime now = LocalDateTime.now(zid);
            String week = now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA);
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " 星期" + week
                    + "（时区 " + zid.getId() + "）";
        } catch (java.time.zone.ZoneRulesException e) {
            return "错误：无法识别时区「" + zone + "」，请使用如 Asia/Shanghai 的时区 ID";
        } catch (Exception e) {
            return "错误：获取当前时间失败：" + e.getMessage();
        }
    }

    /**
     * 计算两个日期相差天数（date1 - date2）。
     *
     * @param date1 被减日期（yyyy-MM-dd）
     * @param date2 减去的日期（yyyy-MM-dd）
     * @return 相差天数（正=date1 在 date2 之后）或错误原因
     */
    @Tool(description = "计算两个日期相差的天数（格式 yyyy-MM-dd，返回 date1 - date2）。"
            + "用于工期、有效期、账期、剩余天数等推算，不要手算日历。")
    public String daysBetween(@ToolParam(description = "被减日期，如 2026-09-30") String date1,
                              @ToolParam(description = "减去的日期，如 2026-09-01") String date2) {
        try {
            LocalDate d1 = LocalDate.parse(date1.trim());
            LocalDate d2 = LocalDate.parse(date2.trim());
            return String.valueOf(d1.toEpochDay() - d2.toEpochDay());
        } catch (Exception e) {
            return "错误：日期格式应为 yyyy-MM-dd（如 2026-09-30）";
        }
    }

    /** 整数不带 .0；小数最多 6 位并去掉尾随 0 */
    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.6f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** 递归下降求值：expr → term → power → unary → number，仅数字与运算符 */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        double parse() {
            double v = expr();
            skip();
            if (i < s.length()) throw new IllegalArgumentException("多余字符");
            return v;
        }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        double expr() {
            double v = term();
            for (; ; ) {
                skip();
                if (i >= s.length()) return v;
                char c = s.charAt(i);
                if (c == '+') { i++; v += term(); } else if (c == '-') { i++; v -= term(); } else return v;
            }
        }

        double term() {
            double v = power();
            for (; ; ) {
                skip();
                if (i >= s.length()) return v;
                char c = s.charAt(i);
                if (c == '*') { i++; v *= power(); } else if (c == '/') {
                    i++;
                    double d = power();
                    if (d == 0) throw new ArithmeticException("除数不能为 0");
                    v /= d;
                } else if (c == '%') {
                    i++;
                    double d = power();
                    if (d == 0) throw new ArithmeticException("取模的除数不能为 0");
                    v %= d;
                } else return v;
            }
        }

        double power() {
            double v = unary();
            skip();
            if (i < s.length() && s.charAt(i) == '^') { i++; v = Math.pow(v, power()); }
            return v;
        }

        double unary() {
            skip();
            if (i >= s.length()) throw new IllegalArgumentException("表达式不完整");
            char c = s.charAt(i);
            if (c == '-') { i++; return -unary(); }
            if (c == '+') { i++; return unary(); }
            if (c == '(') {
                i++;
                double v = expr();
                skip();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException("括号未闭合");
                i++;
                return v;
            }
            return number();
        }

        double number() {
            skip();
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalArgumentException("期望数字");
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
