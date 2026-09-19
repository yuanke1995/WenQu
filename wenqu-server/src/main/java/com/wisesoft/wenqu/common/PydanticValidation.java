package com.wisesoft.wenqu.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求体的 pydantic 契约在控制器层的落实现。
 *
 * <p>参考实现的端点形参是 pydantic 模型，<b>校验全部发生在进入函数体之前</b>；本工程用
 * {@code @RequestBody(required = false) Map<String, Object>} 接原始请求体，因此必须在控制器里
 * 逐条补齐这些约束。不补的后果一律是<b>静默放行</b>，且方向与参考实现相反：
 * <ul>
 *   <li>缺失必填字段 → Jackson 给 {@code null}，业务层把它当成"没填"而不是 422；</li>
 *   <li>类型不符（如 {@code thread_id} 传数字）→ 原样带进业务层，行为不可预期；</li>
 *   <li>越界（如 {@code thread_id} 超 64 字符）→ 一路落到数据库写入才报错，状态码与时机都不同。</li>
 * </ul>
 *
 * <p>错误体逐字对齐 FastAPI：{@code {"detail": [{"loc": [...], "msg": ..., "type": ...}]}}，
 * 其中 {@code loc} 由本类的 {@link #bodyLoc} 拼装（首段恒为 {@code "body"}，子模型字段带中间段）。
 *
 * <p><b>extra 行为</b>：pydantic 默认 {@code extra="ignore"}（未知键静默丢弃），只有显式声明
 * {@code extra="forbid"} 的模型才 422。本类不提供默认的"拒绝未知键"；需要时由调用方显式实现
 * （参见 {@code ScheduledAgentController} 的 {@code rejectUnknownFields}）。
 *
 * <p><b>已知重复（显式标注，待收敛）</b>：{@code ScheduledAgentController} 内有一份等价的私有实现
 * （其 {@code requireString} 把显式 {@code null} 归为 {@code missing}，与 pydantic 的
 * {@code string_type} 略有出入；本类按 pydantic 语义区分）。两者尚未合并，收敛属独立改动。
 *
 * @author yuanke
 */
public final class PydanticValidation {

    /** 无长度上限（pydantic 未声明 {@code max_length} 时的语义）。 */
    public static final Integer NO_LENGTH_LIMIT = null;

    /** pydantic v2 {@code bool} 解析接受的 true 字面量（大小写不敏感）。 */
    private static final List<String> TRUE_LITERALS = List.of("true", "1", "yes", "on");

    /** pydantic v2 {@code bool} 解析接受的 false 字面量（大小写不敏感）。 */
    private static final List<String> FALSE_LITERALS = List.of("false", "0", "no", "off");

    private PydanticValidation() {
    }

    /** 拼装 FastAPI 风格的 {@code loc}：{@code ["body", ...path]}。 */
    public static List<String> bodyLoc(String... path) {
        List<String> loc = new ArrayList<>(path.length + 1);
        loc.add("body");
        for (String segment : path) {
            loc.add(segment);
        }
        return loc;
    }

    /** 构造 422 校验错误（响应体与 FastAPI 的 {@code RequestValidationError} 同形）。 */
    public static ApiHttpException validationError(List<String> loc, String message, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("loc", loc);
        item.put("msg", message);
        item.put("type", type);
        return ApiHttpException.objectDetail(422, "请求参数校验失败", List.of(item));
    }

    /**
     * 请求体整体缺失。
     *
     * <p>参考实现这些端点的请求体形参是必填 pydantic 模型，不传请求体时 FastAPI 返回 422
     * {@code Field required}（{@code loc=["body"]}）；本工程用 {@code required = false} 接住再显式判空，
     * 避免落到 Spring 的 {@code HttpMessageNotReadableException}（500 或 400，与参考不符）。
     */
    public static void requireBody(Map<String, Object> body) {
        if (body == null) {
            throw validationError(bodyLoc(), "Field required", "missing");
        }
    }

    /** 必填 {@code str} 字段（{@code Field(...)}）：缺键 → {@code missing}，显式 null / 类型不符 → {@code string_type}。 */
    public static String requireString(Map<String, Object> body, String field, Integer maxLength) {
        if (!body.containsKey(field)) {
            throw validationError(bodyLoc(field), "Field required", "missing");
        }
        return checkString(body.get(field), field, maxLength);
    }

    /** 可选 {@code str | None} 字段：缺键或显式 null → {@code null}。 */
    public static String optionalString(Map<String, Object> body, String field, Integer maxLength) {
        if (!body.containsKey(field) || body.get(field) == null) {
            return null;
        }
        return checkString(body.get(field), field, maxLength);
    }

    /** {@code str} 类型 + 长度上限校验（pydantic {@code string_type} / {@code string_too_long}）。 */
    public static String checkString(Object value, String field, Integer maxLength) {
        return checkStringAt(value, bodyLoc(field), maxLength);
    }

    /** 同 {@link #checkString}，但 {@code loc} 由调用方指定（用于子模型字段）。 */
    public static String checkStringAt(Object value, List<String> loc, Integer maxLength) {
        if (!(value instanceof String text)) {
            throw validationError(loc, "Input should be a valid string", "string_type");
        }
        if (maxLength != null && text.length() > maxLength) {
            throw validationError(
                    loc, "String should have at most " + maxLength + " characters", "string_too_long");
        }
        return text;
    }

    /**
     * 对应 pydantic 的 {@code bool} 字段（含其宽松解析）；键缺失时返回 {@code defaultValue}。
     *
     * <p>宽松字面量集合照搬 pydantic v2（true/false、1/0、yes/no、on/off，大小写不敏感），
     * 其余字面量报 {@code bool_parsing}——不能简化成"非空即真"，否则 {@code stream: "no"}
     * 会被静默当成开启流式（参考实现是 422）。
     */
    public static Object optionalBoolean(Map<String, Object> body, String field, Boolean defaultValue) {
        if (!body.containsKey(field)) {
            return defaultValue;
        }
        Object value = body.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            int intValue = number.intValue();
            if (intValue == 1) {
                return Boolean.TRUE;
            }
            if (intValue == 0) {
                return Boolean.FALSE;
            }
        } else if (value instanceof String text) {
            String normalized = text.strip().toLowerCase();
            if (TRUE_LITERALS.contains(normalized)) {
                return Boolean.TRUE;
            }
            if (FALSE_LITERALS.contains(normalized)) {
                return Boolean.FALSE;
            }
        }
        throw validationError(bodyLoc(field), "Input should be a valid boolean", "bool_parsing");
    }


    /** 必填 {@code list[dict[str, Any]]} 字段：缺键 → {@code missing}，非列表 → {@code list_type}，元素非对象 → {@code dict_type}。 */
    public static List<Map<String, Object>> requireListOfDict(Map<String, Object> body, String field) {
        if (!body.containsKey(field)) {
            throw validationError(bodyLoc(field), "Field required", "missing");
        }
        if (!(body.get(field) instanceof List<?> raw)) {
            throw validationError(bodyLoc(field), "Input should be a valid list", "list_type");
        }
        List<Map<String, Object>> items = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Object item = raw.get(index);
            if (!(item instanceof Map)) {
                throw validationError(
                        bodyLoc(field, String.valueOf(index)), "Input should be a valid dictionary", "dict_type");
            }
            items.add(JsonValues.asMap(item));
        }
        return items;
    }

    /**
     * {@code dict[str, Any] = Field(default_factory=dict)} 字段：缺键 → 空表，显式 null / 非对象 → {@code dict_type}。
     *
     * <p>注意与{@link #optionalModel} 的区别：裸 {@code dict} 与子模型在 pydantic 里的错误码不同
     * （{@code dict_type} vs {@code model_type}），不可合并。
     */
    public static Map<String, Object> optionalDict(Map<String, Object> body, String field) {
        if (!body.containsKey(field)) {
            return new LinkedHashMap<>();
        }
        Object value = body.get(field);
        if (!(value instanceof Map)) {
            throw validationError(bodyLoc(field), "Input should be a valid dictionary", "dict_type");
        }
        return JsonValues.asMap(value);
    }

    /** 子模型字段（{@code Model = Field(default_factory=Model)}）：缺键 → 空表，显式 null / 非对象 → {@code model_type}。 */
    public static Map<String, Object> optionalModel(Map<String, Object> body, String field, String modelName) {
        if (!body.containsKey(field)) {
            return new LinkedHashMap<>();
        }
        Object value = body.get(field);
        if (!(value instanceof Map)) {
            throw validationError(
                    bodyLoc(field),
                    "Input should be a valid dictionary or instance of " + modelName,
                    "model_type");
        }
        return JsonValues.asMap(value);
    }
}
