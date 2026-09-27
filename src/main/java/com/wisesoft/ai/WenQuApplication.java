package com.wisesoft.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问渠（WenQu）服务入口 —— AI 智能体工作台
 *
 * @author yuanke
 */
@SpringBootApplication
@MapperScan("com.wisesoft.ai.mapper")
public class WenQuApplication {

    public static void main(String[] args) {
        SpringApplication.run(WenQuApplication.class, args);
    }
}