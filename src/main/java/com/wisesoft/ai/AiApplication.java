package com.wisesoft.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问渠（WenQu）服务入口 —— AI 文档问答助手
 *
 * @author yuanke
 */
@SpringBootApplication
@MapperScan("com.wisesoft.ai.mapper")
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}