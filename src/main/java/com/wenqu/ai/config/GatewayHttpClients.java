package com.wenqu.ai.config;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型网关共享 HTTP 客户端工厂：聊天流式、向量嵌入、派遣/委派路由等一切 OpenAI 兼容调用的
 * 统一出口（{@link com.wenqu.ai.service.DynamicOpenAiChatModel} /
 * {@link com.wenqu.ai.service.DynamicEmbeddingModel} 构建客户端时注入）。
 *
 * <h3>为什么显式构建 JDK 客户端</h3>
 * 类路径上没有 reactor-netty / jetty-reactive / httpcomponents5，Spring 的 WebClient 降级链
 * 本来就落在 {@code JdkClientHttpConnector}（JDK HttpClient）上——流式与实体调用共用一个
 * <b>无任何超时</b>的默认实例：网关挂起时连接阶段无限等待、派遣/嵌入线程无限读。
 * 显式构建补上连接超时；不追加依赖换连接池实现（JDK HttpClient 无按主机连接上限，
 * 百级并发长流不构成连接瓶颈，反而无需调优）。
 *
 * <h3>为什么流式路径不设响应/读超时</h3>
 * 流式回答的 HTTP 响应与生成等长（分钟级），连接器级超时会误杀正常长流——整轮截断由
 * 存活看门狗按「机器耗时」判定（RagService.startTurnWatchdog，chat.sseTimeoutMs，人工等待不计），
 * 挂起的网关最多拖满一个看门狗预算。实体路径则无看门狗覆盖，必须显式给读超时。
 *
 * @author yuanke
 */
public final class GatewayHttpClients {

    /** 连接建立超时：网关不可达时快速失败，不占着调用线程干等 */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 实体调用读超时：对齐看门狗机器预算（chat.sseTimeoutMs 默认 300s）+ 30s 余量——
     *  路由派遣（8s future 超时）、批量嵌入等一次性请求的挂起上界 */
    private static final int READ_TIMEOUT_SECONDS = 330;

    private GatewayHttpClients() {
    }

    /** 流式路径（WebClient，OpenAiApi 的 chatCompletionStream 走这里）：连接超时 10s，无读超时（见类注释） */
    public static org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        return org.springframework.web.reactive.function.client.WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(client));
    }

    /** 实体调用路径（RestClient，chatCompletionEntity/embeddings 走这里）：连接 10s + 读 330s */
    public static org.springframework.web.client.RestClient.Builder restClientBuilder() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));
        return org.springframework.web.client.RestClient.builder().requestFactory(factory);
    }
}
