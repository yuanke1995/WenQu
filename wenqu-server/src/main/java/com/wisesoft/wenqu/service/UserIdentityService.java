package com.wisesoft.wenqu.service;

import net.sourceforge.pinyin4j.PinyinHelper;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户名/ uid/ 手机号 校验与生成工具（services/user_identity_service.py 全量移植）。
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>{@code pypinyin.lazy_pinyin(style=NORMAL)} → pinyin4j {@link PinyinHelper#toHanyuPinyinStringArray}，
 *       取首个读音并去掉声调数字（NORMAL 风格等价）。多音字默认读音可能与 pypinyin 不同，属能力差异。</li>
 *   <li>Python 内置 {@code hash(username)}（进程盐值随机）→ {@code String.hashCode()}（确定性），
 *       仅用于 uid 兜底填充，不影响主路径。</li>
 *   <li>{@code int(time.time())} → {@code System.currentTimeMillis() / 1000}，语义等价。</li>
 * </ul>
 */
@Service
public class UserIdentityService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9_]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** 把文本转为无声调拼音串（非汉字原样保留），对应参考实现 to_pinyin。 */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c);
            if (pinyin != null && pinyin.length > 0) {
                sb.append(stripTone(pinyin[0]));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 校验用户名合法性（对应参考实现 validate_username），返回 (是否合法, 错误信息)。 */
    public static UsernameValidation validateUsername(String username) {
        if (username == null || username.isEmpty()) {
            return new UsernameValidation(false, "用户名不能为空");
        }
        if (username.length() < 2) {
            return new UsernameValidation(false, "用户名长度不能少于2个字符");
        }
        if (username.length() > 20) {
            return new UsernameValidation(false, "用户名长度不能超过20个字符");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return new UsernameValidation(false, "用户名只能包含中文、英文、数字和下划线");
        }
        return new UsernameValidation(true, "");
    }

    /** 由用户名生成 uid（对应参考实现 generate_uid）。 */
    public static String generateUid(String username) {
        String src = username == null ? "" : username;
        String pinyin = toPinyin(src.strip());
        String uid = pinyin.replaceAll("[^a-zA-Z0-9_]", "");
        if (!uid.isEmpty() && Character.isDigit(uid.charAt(0))) {
            uid = "u" + uid;
        }
        if (uid.length() < 2) {
            uid = "user" + String.format("%04d", Math.floorMod(src.hashCode(), 10000));
        }
        return (uid.length() > 20 ? uid.substring(0, 20) : uid).toLowerCase();
    }

    /** 在已有 uid 集合中生成唯一 uid（对应参考实现 generate_unique_uid）。 */
    public static String generateUniqueUid(String username, Set<String> existingUids) {
        String baseUid = generateUid(username);
        if (!existingUids.contains(baseUid)) {
            return baseUid;
        }
        for (int counter = 1; counter <= 9999; counter++) {
            String candidate = baseUid + counter;
            if (!existingUids.contains(candidate)) {
                return candidate;
            }
        }
        return baseUid + (System.currentTimeMillis() / 1000 % 10000);
    }

    /** 校验手机号合法性（对应参考实现 is_valid_phone_number）。 */
    public static boolean isValidPhoneNumber(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        return PHONE_PATTERN.matcher(cleaned).matches();
    }

    /** validate_username 的结果载体（对应参考实现返回的 tuple[bool, str]）。 */
    public record UsernameValidation(boolean valid, String message) {
    }

    private static String stripTone(String pinyin) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pinyin.length(); i++) {
            char c = pinyin.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
