package com.wenqu.ai;

import java.util.TimeZone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问渠（WenQu）服务入口 —— AI 智能体工作台
 *
 * @author yuanke
 */
@SpringBootApplication
@MapperScan("com.wenqu.ai.mapper")
public class WenQuApplication {

    /**
     * 全进程统一时区：macOS 开发机是 Asia/Shanghai，eclipse-temurin 容器默认 UTC，
     * 同一份 DATETIME 在两地展示会差 8 小时。入口处一次性固定，业务代码不再依赖宿主默认时区。
     * 需要别的时区时显式设 WENQU_TIMEZONE（K8s 也可只设 TZ，两者都读）。
     */
    static {
        String zone = System.getenv("WENQU_TIMEZONE");
        if (zone == null || zone.isBlank()) zone = System.getenv("TZ");
        if (zone == null || zone.isBlank()) zone = "Asia/Shanghai";
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        System.setProperty("user.timezone", zone);
    }

    public static void main(String[] args) {
        SpringApplication.run(WenQuApplication.class, args);
    }
}