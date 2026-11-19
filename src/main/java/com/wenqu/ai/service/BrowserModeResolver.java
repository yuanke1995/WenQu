package com.wenqu.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 「浏览器操作」执行位置的统一解析口径。
 *
 * <p>历史遗留：本机浏览器族（localBrowser*）与平台浏览器 MCP（browser_*）各自判断该不该挂，
 * 两处读同一个个人配置，一旦判据漂移就会出现「两套都挂」或「两套都没有」。这里把判定收口，
 * 并且把 headed/headless 也纳入同一份结论——工具挂载侧只需问一次「本轮走哪一套」。
 *
 * <p>配置键：
 * <ul>
 *   <li>{@code browser.location}：空 / local = 本机的系统浏览器（历史默认）；builtin = 平台内置云端浏览器；</li>
 *   <li>{@code browser.headless}：true = 本机浏览器后台运行（无窗口、无法人工接管），
 *       仅当执行位置是本机时生效。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
@RequiredArgsConstructor
public class BrowserModeResolver {

    public static final String LOCATION_KEY = "browser.location";
    public static final String HEADLESS_KEY = "browser.headless";
    public static final String LOCATION_BUILTIN = "builtin";

    /** 本轮浏览器能力的执行位置。 */
    public enum Mode {
        /** 本机执行器驱动的有头浏览器：用户看得见、可接管、能访问 localhost/内网 */
        LOCAL_HEADED,
        /** 本机执行器驱动的无头浏览器：能访问 localhost/内网，但用户看不到 */
        LOCAL_HEADLESS,
        /** 平台内置的无头浏览器（云端 MCP）：只看公网，不碰用户电脑 */
        CLOUD,
        /** 用户要求本机但执行器不在线：明确失败，不回落云端 */
        OFFLINE
    }

    private final LocalBrowserTools localBrowserTools;
    private final UserConfigService userConfigService;

    /**
     * 按当前用户解析本轮模式。纯函数式：同一 uid 的两次调用结论一致（在线状态除外）。
     */
    public Mode resolve(String uid) {
        boolean builtin = LOCATION_BUILTIN.equalsIgnoreCase(value(uid, LOCATION_KEY));
        if (builtin) return Mode.CLOUD;
        boolean online = localBrowserTools != null && localBrowserTools.onlineExecutor(uid) != null;
        if (!online) {
            // 历史承诺：执行器离线、用户没显式要求本机时，回落到平台云端（只看公网）。
            // 只有显式选了「系统浏览器」（local）却离线时才是 OFFLINE——那种情况用户明确要本机能力，
            // 静默换云端会给出与意图相反的结论，必须 fail-loud。
            return "local".equalsIgnoreCase(value(uid, LOCATION_KEY))
                    ? Mode.OFFLINE : Mode.CLOUD;
        }
        boolean headless = Boolean.parseBoolean(value(uid, HEADLESS_KEY));
        return headless ? Mode.LOCAL_HEADLESS : Mode.LOCAL_HEADED;
    }

    /** 是否走云端（供 MCP 侧判定平台浏览器要不要让位）。 */
    public boolean cloud(String uid) {
        return resolve(uid) == Mode.CLOUD;
    }

    /** 是否走本机（供本机浏览器族判定挂不挂）。 */
    public boolean local(String uid) {
        Mode m = resolve(uid);
        return m == Mode.LOCAL_HEADED || m == Mode.LOCAL_HEADLESS;
    }

    /** 该用户本机浏览器是否要求无头（执行器据此加 --headless）。 */
    public boolean headless(String uid) {
        return resolve(uid) == Mode.LOCAL_HEADLESS;
    }

    /** 兼容旧调用点：builtin 等价于云端（空 uid 恒 false，不随默认回落变化）。 */
    public boolean builtin(String uid) {
        return LOCATION_BUILTIN.equalsIgnoreCase(value(uid, LOCATION_KEY));
    }

    private String value(String uid, String key) {
        if (uid == null || uid.isBlank()) return "";
        String v = userConfigService.personalValue(uid, key);
        return v == null ? "" : v;
    }
}
