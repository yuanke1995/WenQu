package com.wenqu.ai.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 内置高频工具（Function Calling / Tool Calling）。
 * <p>
 * 补齐「模型手算不准 / 不知道今天几号」这类硬伤：知识库问答里出现金额合计、百分比、
 * 天数推算时，交给工具算比让模型口算可靠得多。默认关闭（tool.builtin.enabled）。
 * <p>
 * **工具准入原则（2026-10-01 与用户对齐）**：本类只收「纯计算、无副作用、无外部依赖」的工具——
 * 外部世界的通用能力一律走 MCP 通道（用户在 MCP 面板自接，平台零代码）；人人都要且需平台
 * 统一管 key/配额的（搜索/天气/汇率）走 Provider 壳（见 WebSearchService）。不要往本类加
 * 发外部请求的工具：内置工具的结果会进引用体系（sources），外部数据不走溯源链路会破坏
 * 可溯源性；沙盒/产物/技能是业务耦合同理不在此列。
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
    /** 批量生成上限（随机数 / UUID）：防一次调用刷出海量内容挤占上下文 */
    private static final int MAX_BATCH = 50;
    /** 文本统计的最大输入长度：统计不需要全文回显，超长输入按 DoS 拦 */
    private static final int MAX_TEXT_LEN = 20_000;

    /** 随机源用 SecureRandom：随机数常被用户用于抽奖/抽样，用 ThreadLocalRandom 会显得"有规律" */
    private static final SecureRandom RANDOM = new SecureRandom();

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

    // ==================== 常用工具扩充（2026-10-01）：随机 / UUID / 日期推算 / 单位换算 / 文本统计 / Base64 / 哈希 ====================
    // 扩充原则与原有三件一致：只做「模型自己做不可靠或做不了」的纯计算——随机数模型编的是假随机、
    // UUID 模型编的格式对但不是真随机、日历推算（3 周后是几号）模型常错、单位换算（华氏度/英里/亩）易错、
    // 数文字数模型数不准、Base64/哈希模型根本算不了。全部无副作用、无外部依赖、不执行任何输入内容。

    /**
     * 生成随机整数（密码学强度随机源）。
     *
     * @param min   最小值（含）
     * @param max   最大值（含）
     * @param count 生成个数（1~50，默认 1）
     * @return 随机整数列表
     */
    @Tool(description = "生成随机整数（真随机，不是模型自己编的）。涉及抽奖、抽样、随机分配、掷骰子、"
            + "生成测试数据等需要随机性的场景时调用本工具，不要自己「想」一个随机数。")
    public String randomNumber(
            @ToolParam(description = "最小值（含），如 1") Integer min,
            @ToolParam(description = "最大值（含），如 100") Integer max,
            @ToolParam(description = "生成个数（1~50，默认 1）", required = false) Integer count) {
        if (min == null || max == null) return "错误：min 和 max 不能为空";
        if (min > max) return "错误：min 不能大于 max";
        int n = count == null ? 1 : count;
        if (n < 1 || n > MAX_BATCH) return "错误：count 应在 1~" + MAX_BATCH + " 之间";
        // 范围宽度（max-min+1）在两端都接近 int 边界时会溢出：显式拦截而不是让异常栈回给模型
        long range = (long) max - (long) min + 1;
        if (range > Integer.MAX_VALUE) return "错误：范围 [" + min + ", " + max + "] 过大（跨度不能超过 " + Integer.MAX_VALUE + "）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("、");
            sb.append(RANDOM.nextInt((int) range) + min);
        }
        return sb + (n > 1 ? "（共 " + n + " 个，范围 [" + min + ", " + max + "]）" : "");
    }

    /**
     * 生成 UUID（v4，密码学强度随机源）。
     *
     * @param count 生成个数（1~50，默认 1）
     * @return UUID 列表（每行一个）
     */
    @Tool(description = "生成 UUID（v4 随机版）。需要唯一标识符（订单号占位、测试数据主键、去重标记等）时调用，"
            + "不要自己编 UUID——编出来的虽然格式对但不是真随机，可能重复。")
    public String uuid(@ToolParam(description = "生成个数（1~50，默认 1）", required = false) Integer count) {
        int n = count == null ? 1 : count;
        if (n < 1 || n > MAX_BATCH) return "错误：count 应在 1~" + MAX_BATCH + " 之间";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(UUID.randomUUID()).append('\n');
        return sb.toString().trim();
    }

    /**
     * 日期推算：某日期加/减 N 天。
     *
     * @param date 起始日期（yyyy-MM-dd，「今天」可用 currentDateTime 先取）
     * @param days 偏移天数，正=往后，负=往前
     * @return 结果日期与星期
     */
    @Tool(description = "日期推算：把某日期加/减 N 天，返回结果日期与星期。涉及「3 周后是几号」「30 个工作日大约到哪天」"
            + "「有效期到哪天」等日历推算时调用，不要自己数日历——跨月/闰年很容易错。")
    public String addDays(
            @ToolParam(description = "起始日期，格式 yyyy-MM-dd，如 2026-10-01") String date,
            @ToolParam(description = "偏移天数，正=往后，负=往前，如 21 或 -7") Integer days) {
        try {
            if (days == null) return "错误：days 不能为空";
            LocalDate d = LocalDate.parse(date.trim()).plusDays(days);
            String week = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CHINA);
            return date.trim() + (days >= 0 ? " + " : " - ") + Math.abs(days) + " 天 = "
                    + d.format(DateTimeFormatter.ISO_LOCAL_DATE) + " 星期" + week;
        } catch (Exception e) {
            return "错误：日期格式应为 yyyy-MM-dd（如 2026-10-01），days 为整数";
        }
    }

    /** 单位因子表：单位（含中文别名）→（类别, 到基准单位的因子）。LinkedHashMap 保持注册顺序仅为可读 */
    private static final Map<String, Object[]> UNIT_TABLE = new LinkedHashMap<>();
    /** 温度单位集合：走独立公式（有偏移，不能乘因子） */
    private static final java.util.Set<String> TEMP_UNITS = java.util.Set.of("c", "f", "k",
            "摄氏度", "℃", "华氏度", "℉", "开尔文");

    static {
        // 长度（基准 m）
        put("mm", "长度", 0.001); put("厘米", "长度", 0.01); put("m", "长度", 1); put("km", "长度", 1000);
        put("inch", "长度", 0.0254); put("ft", "长度", 0.3048); put("yd", "长度", 0.9144); put("mile", "长度", 1609.344);
        put("毫米", "长度", 0.001); put("cm", "长度", 0.01); put("米", "长度", 1); put("千米", "长度", 1000); put("公里", "长度", 1000);
        put("英寸", "长度", 0.0254); put("英尺", "长度", 0.3048); put("码", "长度", 0.9144); put("英里", "长度", 1609.344);
        // 重量（基准 kg）
        put("mg", "重量", 1e-6); put("g", "重量", 0.001); put("kg", "重量", 1); put("t", "重量", 1000);
        put("oz", "重量", 0.0283495); put("lb", "重量", 0.453592); put("斤", "重量", 0.5);
        put("毫克", "重量", 1e-6); put("克", "重量", 0.001); put("千克", "重量", 1); put("公斤", "重量", 1);
        put("吨", "重量", 1000); put("盎司", "重量", 0.0283495); put("磅", "重量", 0.453592);
        // 数据大小（基准 B，二进制口径 1024；与操作系统文件大小口径一致）
        put("b", "数据大小", 1); put("kb", "数据大小", 1024L); put("mb", "数据大小", 1024L * 1024);
        put("gb", "数据大小", 1024L * 1024 * 1024); put("tb", "数据大小", 1024L * 1024 * 1024 * 1024);
        // 面积（基准 m2）
        put("m2", "面积", 1); put("km2", "面积", 1e6); put("亩", "面积", 2000.0 / 3); put("ha", "面积", 10000);
        put("平方米", "面积", 1); put("平方公里", "面积", 1e6); put("公顷", "面积", 10000);
        // 体积（基准 l）
        put("ml", "体积", 0.001); put("l", "体积", 1); put("m3", "体积", 1000);
        put("毫升", "体积", 0.001); put("升", "体积", 1); put("立方米", "体积", 1000);
        // 速度（基准 m/s）
        put("m/s", "速度", 1); put("km/h", "速度", 1.0 / 3.6); put("mph", "速度", 0.44704); put("knot", "速度", 0.514444);
        put("米每秒", "速度", 1); put("千米每小时", "速度", 1.0 / 3.6); put("公里每小时", "速度", 1.0 / 3.6);
    }

    private static void put(String unit, String category, double factor) {
        UNIT_TABLE.put(unit.toLowerCase(Locale.ROOT), new Object[]{category, factor});
    }

    /**
     * 单位换算（长度/重量/温度/面积/体积/速度/数据大小，支持中文单位名）。
     *
     * @param value 数值
     * @param from  原单位（如 c / cm / kg / 亩 / MB / 摄氏度）
     * @param to    目标单位
     * @return 换算结果
     */
    @Tool(description = "单位换算：长度、重量、温度、面积、体积、速度、数据大小（KB/MB/GB），支持中文单位名"
            + "（如 厘米/公斤/亩/摄氏度）。涉及英寸转厘米、华氏度转摄氏度、磅转公斤、英亩/亩换算、"
            + "英里转公里等场景时调用，不要自己心算换算率。数据大小按二进制口径（1MB=1024KB）。")
    public String unitConvert(
            @ToolParam(description = "数值，如 98.6") Double value,
            @ToolParam(description = "原单位，如 f / inch / lb / 亩 / mb", required = false) String from,
            @ToolParam(description = "目标单位，如 c / cm / kg / 平方米 / gb") String to) {
        if (value == null || to == null || to.isBlank()) return "错误：value 和 to 不能为空";
        if (from == null || from.isBlank()) return "错误：from 不能为空（如 f / cm / kg）";
        String f = from.trim().toLowerCase(Locale.ROOT);
        String t = to.trim().toLowerCase(Locale.ROOT);
        // 温度：有偏移，单独走公式（先统一转摄氏度，再转目标）
        if (TEMP_UNITS.contains(f) && TEMP_UNITS.contains(t)) {
            double c = switch (f) {
                case "f", "华氏度", "℉" -> (value - 32) / 1.8;
                case "k", "开尔文" -> value - 273.15;
                default -> value;
            };
            double r = switch (t) {
                case "f", "华氏度", "℉" -> c * 1.8 + 32;
                case "k", "开尔文" -> c + 273.15;
                default -> c;
            };
            return value + " " + from + " = " + fmt(r) + " " + to;
        }
        Object[] fu = UNIT_TABLE.get(f);
        Object[] tu = UNIT_TABLE.get(t);
        if (fu == null) return "错误：不认识单位「" + from + "」（支持：长度/重量/温度/面积/体积/速度/数据大小的常用单位，如 cm/kg/亩/MB/摄氏度）";
        if (tu == null) return "错误：不认识单位「" + to + "」";
        if (!fu[0].equals(tu[0])) {
            return "错误：「" + from + "」是" + fu[0] + "单位、「" + to + "」是" + tu[0] + "单位，不能互转";
        }
        double r = value * (Double) fu[1] / (Double) tu[1];
        return value + " " + from + " = " + fmt(r) + " " + to + "（" + fu[0] + "）";
    }

    /**
     * 文本统计：字数 / 字符数 / 行数 / 段落数。
     *
     * @param text 要统计的文本
     * @return 统计结果
     */
    @Tool(description = "统计文本的字数/字符数/行数/段落数/中英文字数。用户问「这段文字多少字」「有没有超过 N 字」"
            + "时调用，不要自己数——模型数字符不可靠。")
    public String textStats(@ToolParam(description = "要统计的文本") String text) {
        if (text == null) return "错误：text 不能为空";
        if (text.length() > MAX_TEXT_LEN) return "错误：文本过长（最多 " + MAX_TEXT_LEN + " 字符）";
        int chars = text.length();
        int noSpace = text.replaceAll("\\s", "").length();
        int cjk = 0;
        for (int i = 0; i < chars; i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) cjk++;
        }
        // 英文单词数：连续字母/数字串（中文按字计，不重复计）
        String[] tokens = text.replaceAll("[\\p{Punct}\\s]+", " ").trim().split("\\s+");
        int words = 0;
        for (String tk : tokens) {
            if (tk.matches(".*[a-zA-Z0-9].*")) words++;
        }
        String[] lines = text.split("\n", -1);
        String[] paras = text.strip().split("\n\\s*\n");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return "总字符数（含空格换行）：" + chars
                + "\n不含空白字符数：" + noSpace
                + "\n中文字符数：" + cjk
                + "\n英文/数字词数：" + words
                + "\n行数：" + lines.length
                + "\n段落数（空行分隔）：" + paras.length
                + "\nUTF-8 字节数：" + bytes;
    }

    /**
     * Base64 编码 / 解码（UTF-8）。
     *
     * @param action encode=编码，decode=解码
     * @param text   内容（decode 时传 Base64 串）
     * @return 结果
     */
    @Tool(description = "Base64 编码或解码（UTF-8）。需要把文本转成 Base64、或解开一段 Base64 内容时调用，"
            + "不要自己手编——Base64 手工转换几乎必错。")
    public String base64(
            @ToolParam(description = "encode=编码，decode=解码") String action,
            @ToolParam(description = "要处理的内容（decode 时传 Base64 字符串）") String text) {
        if (text == null || text.isBlank()) return "错误：text 不能为空";
        if (text.length() > MAX_TEXT_LEN) return "错误：内容过长（最多 " + MAX_TEXT_LEN + " 字符）";
        try {
            if ("encode".equalsIgnoreCase(action)) {
                return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            }
            if ("decode".equalsIgnoreCase(action)) {
                return new String(Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8);
            }
            return "错误：action 只能是 encode 或 decode";
        } catch (IllegalArgumentException e) {
            return "错误：不是合法的 Base64 字符串（" + e.getMessage() + "）";
        }
    }

    /**
     * 计算哈希值（MD5 / SHA-1 / SHA-256 / SHA-512）。
     *
     * @param algorithm 算法（md5 / sha1 / sha256 / sha512）
     * @param text      内容
     * @return 十六进制哈希值
     */
    @Tool(description = "计算文本的哈希值（MD5 / SHA-1 / SHA-256 / SHA-512，十六进制输出）。"
            + "需要校验串一致性、生成内容指纹时调用——哈希模型完全算不了，必须用本工具。"
            + "注意：MD5/SHA-1 仅用于数据校验等非安全场景。")
    public String hash(
            @ToolParam(description = "算法：md5 / sha1 / sha256 / sha512") String algorithm,
            @ToolParam(description = "要计算的内容") String text) {
        if (text == null) return "错误：text 不能为空";
        if (text.length() > MAX_TEXT_LEN) return "错误：内容过长（最多 " + MAX_TEXT_LEN + " 字符）";
        String alg = algorithm == null ? "" : algorithm.trim().toUpperCase(Locale.ROOT).replace("-", "");
        String jca = switch (alg) {
            case "MD5" -> "MD5";
            case "SHA1" -> "SHA-1";
            case "SHA256" -> "SHA-256";
            case "SHA512" -> "SHA-512";
            default -> null;
        };
        if (jca == null) return "错误：algorithm 只支持 md5 / sha1 / sha256 / sha512";
        try {
            byte[] digest = MessageDigest.getInstance(jca).digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return jca + "：" + sb;
        } catch (Exception e) {
            return "错误：计算哈希失败：" + e.getMessage();
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
