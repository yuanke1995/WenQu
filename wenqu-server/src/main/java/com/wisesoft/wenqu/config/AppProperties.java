package com.wisesoft.wenqu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务端配置绑定（前缀 {@code wenqu}）。
 * <p>
 * 只放**启动期需要**的固定配置（密钥、令牌参数、口令策略）。业务可变配置（模型地址、
 * 检索参数等）不在这里，它们以数据库为唯一事实源、由配置服务统一读取，避免出现两份默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wenqu")
public class AppProperties {

    private Auth auth = new Auth();

    @Data
    public static class Auth {
        /** JWT 签名密钥（环境变量 WENQU_JWT_SECRET）。留空则启动时随机生成并告警，重启后原令牌失效 */
        private String jwtSecret = "";
        /** 令牌有效期（小时，默认 168 = 7 天） */
        private int tokenTtlHours = 168;
        /** 是否要求登录：true（默认）＝除公开端点外必须持有效令牌 */
        private boolean requireLogin = true;
        /** 令牌签发者 */
        private String issuer = "wenqu";
        /** 令牌受众 */
        private String audience = "wenqu-api";
        /** 连续登录失败达该次数后锁定（0 = 不锁定） */
        private int maxLoginFailures = 5;
        /** 锁定时长（分钟） */
        private int lockMinutes = 15;
        /** 初始化管理员时要求的最小口令长度 */
        private int minPasswordLength = 6;
    }
}
