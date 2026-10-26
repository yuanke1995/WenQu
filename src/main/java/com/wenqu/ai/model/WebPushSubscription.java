package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条 Web Push 订阅 ＝ 一台浏览器（同一浏览器的同一站点作用域一行）。
 *
 * <p>订阅是<b>设备级</b>的：同一个人可以在手机、平板、公司电脑上各订一份，推送逐条发到每个地址。
 * 三个字段（endpoint / p256dh / auth）全部由浏览器在 {@code PushManager.subscribe()} 时给出，
 * 平台只是保管者——浏览器清掉站点数据、或推送服务判定订阅失效，这一行就该摘掉。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_webpush_subscription")
public class WebPushSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** endpoint 的 MD5（唯一键；地址本身可达数百字符，不适合直接建索引） */
    private String endpointHash;

    /** 推送服务给的回调地址：载荷 POST 给它才算投递 */
    private String endpoint;

    /** 浏览器订阅的 ECDH 公钥（base64url） */
    private String p256dh;

    /** 浏览器订阅的鉴权秘密（base64url）。列名 auth_secret，避开 auth 这个潜在保留字 */
    private String authSecret;

    /** 订阅时的 UA：只用于在个人设置里认出「这是哪台设备」 */
    private String userAgent;

    /** 连续发送失败次数（成功归零；到阈值自动摘掉——订阅失效的浏览器不该一直陪跑） */
    private Integer failCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
