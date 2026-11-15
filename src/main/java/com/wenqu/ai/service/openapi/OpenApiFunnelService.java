package com.wenqu.ai.service.openapi;

import com.wenqu.ai.mapper.OpenApiCallStatMapper;
import com.wenqu.ai.openapi.OpenApiCallAttr;
import com.wenqu.ai.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入漏斗（B5）：回答「签发的 Key 有没有被真正用起来、卡在哪一步」。
 *
 * <p><b>口径（都在这里，别在前端另算）</b>：
 * <ul>
 *   <li><b>签发量</b>：窗口内新建的 Key 数（{@code c_ai_api_key.create_time}）；</li>
 *   <li><b>首调率</b>：窗口内签发的 Key 中，至少有过一次成功调用（2xx）的占比——
 *       「签发完就躺在列表里」是接入体验最真实的失败信号；</li>
 *   <li><b>7 日留存</b>：签发满 7 天的 Key 中，签发后第 7–13 天窗口内仍有成功调用的占比。
 *       分母只算「够得着 7 天」的 Key（签发不足 7 天的先别计入，否则留存率被新 Key 稀释成假低）；</li>
 *   <li><b>成功率</b>：窗口内 2xx 占比；失败原因按现场归因拆分（缺 scope 与端点未登记不会混成一个 403）。</li>
 * </ul>
 *
 * <p><b>可见范围</b>：只统计当前用户能看见的 Key（Key 是个人资产，谁建归谁）。未通过认证的调用
 * （{@code api_key_id} 为空串）一并计入维度统计——它统计的正是「有人拿不对的 Key 在调」，
 * 但不出现在按 Key 的分表里，因为它不属于任何一把 Key。</p>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiFunnelService {

    /** 未认证调用的 api_key_id 占位（与 V23 表口径一致） */
    private static final String ANONYMOUS = "";
    private static final int MAX_DAYS = 90;
    /** 留存观察窗：签发后第 7 天起、到第 14 天前 */
    private static final int RETENTION_FROM_DAY = 7;
    private static final int RETENTION_TO_DAY = 14;

    private final OpenApiCallStatMapper mapper;
    private final ApiKeyService apiKeyService;

    /** 漏斗看板数据（days 天窗口，1–90 天） */
    public Map<String, Object> insights(int days) {
        int d = Math.max(1, Math.min(days <= 0 ? 30 : days, MAX_DAYS));
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(d - 1L);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("since", since.toString());
        out.put("until", today.toString());

        List<Map<String, Object>> keys = apiKeyService.list();
        List<String> keyIds = new ArrayList<>();
        List<String> scopeIds = new ArrayList<>();
        for (Map<String, Object> k : keys) {
            Object id = k.get("id");
            if (id != null) {
                keyIds.add(String.valueOf(id));
                scopeIds.add(String.valueOf(id));
            }
        }
        scopeIds.add(ANONYMOUS);

        if (keyIds.isEmpty()) {
            // 一把 Key 都没有：不查库，直接给空结构（前端渲染空态引导）。
            // 未认证调用也不展示——没有 Key 的人看不到「别人拿错 Key」的计数
            out.put("keySummary", keySummary(keys, since, today));
            out.put("calls", emptyCalls());
            out.put("daily", List.of());
            out.put("reasons", List.of());
            out.put("statuses", List.of());
            out.put("endpoints", List.of());
            out.put("perKey", Map.of());
            return out;
        }

        List<Map<String, Object>> daily = mapper.daily(scopeIds, since);
        List<Map<String, Object>> keyDaily = mapper.keyDaily(keyIds, since);
        // 首调 / 留存的判定信号先挂到 Key 行上，再算汇总：两个比率的分母是「窗口内签发的 Key」，
        // 分子来自按日明细，顺序反了就会永远算出 0
        attachKeySignals(keys, keyDaily);
        out.put("keySummary", keySummary(keys, since, today));

        long calls = 0;
        long ok = 0;
        long unauthorized = 0;
        long forbidden = 0;
        long rateLimited = 0;
        long serverError = 0;
        for (Map<String, Object> row : mapper.statuses(scopeIds, since)) {
            int s = (int) num(row.get("s"));
            long c = num(row.get("calls"));
            calls += c;
            if (s >= 200 && s < 300) ok += c;
            else if (s == 401) unauthorized += c;
            else if (s == 403) forbidden += c;
            else if (s == 429) rateLimited += c;
            else if (s >= 500) serverError += c;
        }

        Map<String, Object> callsOut = new LinkedHashMap<>();
        callsOut.put("total", calls);
        callsOut.put("ok", ok);
        callsOut.put("failed", calls - ok);
        callsOut.put("successRate", calls == 0 ? null : round4(ok, calls));
        callsOut.put("unauthorized", unauthorized);
        callsOut.put("forbidden", forbidden);
        callsOut.put("rateLimited", rateLimited);
        callsOut.put("serverError", serverError);
        out.put("calls", callsOut);

        List<Map<String, Object>> dailyOut = new ArrayList<>();
        // 逐日补零：调用台账只写「有调用的那一天」，但趋势图要的是完整时间轴——
        // 少画空白日，3 天的数据会被摊成整月，看不出「从哪天开始才真的接上」
        Map<LocalDate, long[]> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : daily) {
            long c = num(row.get("calls"));
            long o = num(row.get("ok"));
            byDate.put(dateOf(row.get("d")), new long[]{c, o});
        }
        for (LocalDate day = since; !day.isAfter(today); day = day.plusDays(1)) {
            long[] v = byDate.getOrDefault(day, new long[]{0, 0});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("calls", v[0]);
            m.put("ok", v[1]);
            m.put("failed", v[0] - v[1]);
            dailyOut.add(m);
        }
        out.put("daily", dailyOut);

        List<Map<String, Object>> reasonsOut = new ArrayList<>();
        for (Map<String, Object> row : mapper.reasons(scopeIds, since, true)) {
            String reason = str(row.get("r"));
            long c = num(row.get("calls"));
            Map<String, Object> m = explain(reason);
            m.put("reason", reason);
            m.put("calls", c);
            m.put("share", calls == 0 ? null : round4(c / (double) calls));
            reasonsOut.add(m);
        }
        out.put("reasons", reasonsOut);

        List<Map<String, Object>> statusOut = new ArrayList<>();
        for (Map<String, Object> row : mapper.statuses(scopeIds, since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", (int) num(row.get("s")));
            m.put("calls", num(row.get("calls")));
            statusOut.add(m);
        }
        out.put("statuses", statusOut);

        List<Map<String, Object>> endpointsOut = new ArrayList<>();
        for (Map<String, Object> row : mapper.endpoints(scopeIds, since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", str(row.get("e")));
            m.put("calls", num(row.get("calls")));
            m.put("ok", num(row.get("ok")));
            endpointsOut.add(m);
        }
        out.put("endpoints", endpointsOut);

        out.put("perKey", perKey(keyDaily));
        return out;
    }

    // ==================== 分块计算 ====================

    /** 签发 / 首调 / 留存的分子分母（Java 侧对齐 Key 的签发日，口径写清楚才好核对） */
    private Map<String, Object> keySummary(List<Map<String, Object>> keys, LocalDate since, LocalDate today) {
        int issued = 0;
        int firstCall = 0;
        int eligible = 0;
        int retained = 0;
        for (Map<String, Object> k : keys) {
            LocalDate created = dateOfNullable(k.get("createTime"));
            if (created == null || created.isBefore(since)) continue;
            issued++;
            // 首调：签发之后有过成功调用。窗口内签发的 Key 的调用必然也在窗口内，不必再查历史
            if (num(k.get("_okCalls")) > 0) firstCall++;
            // 留存：只把「已经活过完整观察窗」的 Key 放进来，否则新 Key 会稀释成假低
            if (!created.isAfter(today.minusDays(RETENTION_FROM_DAY))) {
                eligible++;
                if (num(k.get("_retained")) > 0) retained++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", keys.size());
        out.put("issued", issued);
        out.put("firstCall", firstCall);
        out.put("firstCallRate", issued == 0 ? null : round4(firstCall / (double) issued));
        out.put("retentionEligible", eligible);
        out.put("retention", retained);
        out.put("retentionRate", eligible == 0 ? null : round4(retained / (double) eligible));
        return out;
    }

    /** 每个 Key 的窗口内调用数 / 成功数（表格行内小计） */
    private Map<String, Object> perKey(List<Map<String, Object>> keyDaily) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : keyDaily) {
            String k = str(row.get("k"));
            long[] v = acc.computeIfAbsent(k, x -> new long[2]);
            v[0] += num(row.get("calls"));
            v[1] += num(row.get("ok"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", v[0]);
            m.put("ok", v[1]);
            m.put("failed", v[0] - v[1]);
            out.put(k, m);
        });
        return out;
    }

    /**
     * 把 Key 维度的按日明细反挂到 Key 行上（首调 / 留存判定要用，但不进返回体）。
     * <p>一次遍历同时算两件事：签发后有没有成功调用；签发后第 7–13 天窗口内有没有成功调用。
     */
    void attachKeySignals(List<Map<String, Object>> keys, List<Map<String, Object>> keyDaily) {
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : keyDaily) {
            byKey.computeIfAbsent(str(row.get("k")), x -> new ArrayList<>()).add(row);
        }
        for (Map<String, Object> k : keys) {
            LocalDate created = dateOfNullable(k.get("createTime"));
            long okCalls = 0;
            long retained = 0;
            if (created != null) {
                for (Map<String, Object> row : byKey.getOrDefault(str(k.get("id")), List.of())) {
                    long o = num(row.get("ok"));
                    if (o <= 0) continue;
                    LocalDate day = dateOf(row.get("d"));
                    if (!day.isBefore(created)) okCalls += o;
                    if (!day.isBefore(created.plusDays(RETENTION_FROM_DAY))
                            && day.isBefore(created.plusDays(RETENTION_TO_DAY))) retained += o;
                }
            }
            k.put("_okCalls", okCalls);
            k.put("_retained", retained);
        }
    }

    /** 归因的人话与下一步（后端出，前端不另编一份文案，否则两边迟早对不上） */
    private static Map<String, Object> explain(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (reason == null) reason = "";
        if (reason.startsWith(OpenApiCallAttr.SCOPE_DENIED + ":")) {
            String scope = reason.substring((OpenApiCallAttr.SCOPE_DENIED + ":").length());
            m.put("label", "缺能力范围：" + scope);
            m.put("action", "在「编辑 Key → 能力范围」勾上「" + scope + "」，保存后下一次调用即生效");
            m.put("kind", "scope");
            return m;
        }
        switch (reason) {
            case OpenApiCallAttr.NO_CREDENTIAL -> {
                m.put("label", "没带凭据");
                m.put("action", "请求头加 X-Api-Key: sk-…，或用 Authorization: Bearer sk-…");
                m.put("kind", "auth");
            }
            case OpenApiCallAttr.KEY_INVALID -> {
                m.put("label", "Key 无效");
                m.put("action", "核对 Key 是否复制完整（或已被删除/轮换）；可在列表点「眼睛」复制当前明文");
                m.put("kind", "auth");
            }
            case OpenApiCallAttr.KEY_DISABLED -> {
                m.put("label", "Key 已停用");
                m.put("action", "在列表把该 Key 的「启用」打开，或重新签发一把");
                m.put("kind", "auth");
            }
            case OpenApiCallAttr.KEY_EXPIRED -> {
                m.put("label", "Key 已过期");
                m.put("action", "延长有效期（编辑）或重新签发");
                m.put("kind", "auth");
            }
            case OpenApiCallAttr.ENDPOINT_NOT_REGISTERED -> {
                m.put("label", "端点不在开放契约内");
                m.put("action", "对照「对接文档 → API 参考」的端点清单；未登记的端点默认拒绝，不是 Key 的权限问题");
                m.put("kind", "contract");
            }
            case OpenApiCallAttr.KEY_DENIED -> {
                m.put("label", "该端点不对 Key 开放");
                m.put("action", "账号自服务与能力配置面只能用登录态；Key 只走问答/检索/工作流/run 这类能力面");
                m.put("kind", "contract");
            }
            case OpenApiCallAttr.RATE_LIMITED -> {
                m.put("label", "被限流（429）");
                m.put("action", "降低并发或加退避重试；持续 429 要看模型侧配额，不是 Key 的问题");
                m.put("kind", "limit");
            }
            default -> {
                if (reason.startsWith("error_5")) {
                    m.put("label", "服务端错误（" + reason.substring(6) + "）");
                    m.put("action", "拿响应头的 X-Wenqu-Request-Id 查应用日志定位");
                    m.put("kind", "server");
                } else if (reason.startsWith("error_")) {
                    m.put("label", "调用失败（" + reason.substring(6) + "）");
                    m.put("action", "看响应体的 message 与 X-Wenqu-Request-Id");
                    m.put("kind", "other");
                } else {
                    m.put("label", reason.isBlank() ? "未知" : reason);
                    m.put("action", "");
                    m.put("kind", "other");
                }
            }
        }
        return m;
    }

    private static Map<String, Object> emptyCalls() {
        // 字段类型与有数据时一致（都是 long）：同一接口两种数字类型会让前端/第三方解析器
        // 在「空数据」和「有数据」两种返回上表现不一致
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", 0L);
        m.put("ok", 0L);
        m.put("failed", 0L);
        m.put("successRate", null);
        m.put("unauthorized", 0L);
        m.put("forbidden", 0L);
        m.put("rateLimited", 0L);
        m.put("serverError", 0L);
        return m;
    }

    // ==================== 类型收敛 ====================

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static LocalDate dateOf(Object o) {
        LocalDate d = dateOfNullable(o);
        return d == null ? LocalDate.EPOCH : d;
    }

    private static LocalDate dateOfNullable(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.util.Date ud) return ud.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        String s = String.valueOf(o);
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 比率保留 4 位（前端按百分比展示；不在这里乘 100，免得整数/小数口径两头算） */
    private static double round4(long numerator, long denominator) {
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
