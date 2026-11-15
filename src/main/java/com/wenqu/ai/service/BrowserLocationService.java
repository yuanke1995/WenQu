package com.wenqu.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 个人设置「浏览器执行位置」的唯一读取口径。
 * <p>
 * 两个消费方必须读到同一个结论，否则会互相顶掉：{@code LocalBrowserToolProvider} 决定本机浏览器族
 * 挂不挂，{@code McpClientService} 决定云端平台浏览器要不要给本机让位。各自读一次配置迟早漂移，
 * 统一收在这里。
 *
 * @author yuanke
 */
@Service
@RequiredArgsConstructor
public class BrowserLocationService {

    /** 个人设置键：空=系统浏览器优先（历史行为） */
    public static final String LOCATION_KEY = "browser.location";
    public static final String LOCATION_BUILTIN = "builtin";

    private final UserConfigService userConfigService;

    /** 用户是否选择了「内置浏览器」：本机浏览器族不挂、云端平台浏览器不让位 */
    public boolean builtin(String uid) {
        if (uid == null || uid.isBlank()) return false;
        return LOCATION_BUILTIN.equalsIgnoreCase(userConfigService.personalValue(uid, LOCATION_KEY));
    }
}
