package com.wisesoft.wenqu;

import com.wisesoft.wenqu.config.QualifiedMapperBeanNameGenerator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问渠服务端入口。
 * <p>
 * 分层约定（与平台化形态一致）：
 * <ul>
 *   <li>{@code controller} — HTTP 边界，只做参数校验与响应装配，不写业务</li>
 *   <li>{@code service} — 用例编排（检索链路、问答链路、知识库管理等）</li>
 *   <li>{@code repository} — 持久化边界（MyBatis-Plus Mapper），SQL 只在这层出现</li>
 *   <li>{@code model} / {@code dto} — 实体与传输对象</li>
 *   <li>{@code config} — 基础设施装配（模型、存储、鉴权、schema 演进）</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan(
        basePackages = "com.wisesoft.wenqu.repository",
        nameGenerator = QualifiedMapperBeanNameGenerator.class)
public class WenquServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WenquServerApplication.class, args);
    }
}
