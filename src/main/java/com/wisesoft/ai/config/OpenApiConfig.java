package com.wisesoft.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI (Swagger UI) 配置
 *
 * @author yuanke
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiDocOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("问渠 WenQu API")
                        .description("问渠（WenQu）—— AI 文档问答助手服务，基于 RAG 实现知识库问答。" +
                                "支持文档解析、混合检索、流式回答、引用溯源、数据看板与知识缺口闭环。")
                        .version("1.0.1")
                        .contact(new Contact()
                                .name("yuanke")
                                .email("yuanke@wisesoft.com"))
                        .license(new License()
                                .name("Internal Use Only")))
                .servers(List.of(
                        new Server()
                                .url("/ai")
                                .description("本地开发（context-path /ai）")))
                .tags(List.of(
                        new Tag().name("智能问答").description("SSE 流式问答、会话管理、知识块详情"),
                        new Tag().name("文档管理").description("文档上传/解析、列表、启停用、重解析、批量操作"),
                        new Tag().name("知识库").description("无命中问题查询、知识块预览、手动创建知识块"),
                        new Tag().name("反馈与看板").description("回答反馈、数据统计看板"),
                        new Tag().name("系统配置").description("模型参数配置（保存即生效）"),
                        new Tag().name("检索调试").description("检索链路分步调试，排查召回问题")));
    }
}