package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.WebPushSubscriptionMapper;
import com.wenqu.ai.model.WebPushSubscription;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web Push：把站内通知（铃铛里那条）再推一份到浏览器的<b>系统级通知</b>。
 *
 * <p>与站内通知的分工：站内通知是「回到平台后看得见」，Web Push 是「人不在页面/锁屏也被告知」。
 * 两者共用同一份产生方与同一份个人静音清单——{@code NotificationService.create} 里已经挡掉的
 * （类型被静音、无归属人、免登录访客），这里<b>不会再推一次</b>；这里只挂在「真的落了一条新通知」
 * 之后，去重合并（hit_count+1）那一路不推：同一件事反复弹系统通知比不弹更糟。
 *
 * <p>密钥：VAPID 密钥对走 env（{@code ai-app.webpush.*}），不进设置页也不进 DB——私钥能在 UI 上
 * 读写就等于任何登录用户都能读走。多副本必须同一对（浏览器存的是公钥，签名必须配对私钥）。
 *
 * <p>投递是<b>旁路</b>：单线程 + 有界队列，满了丢最旧并记 WARN，绝不影响问答与通知落库；
 * 推送服务说这个订阅过期（404/410）就当场摘掉，连续失败到阈值也摘掉——失效订阅不该一直陪跑。
 *
 * <p>浏览器侧要求（都不由本服务决定，界面上必须如实说清）：页面须在<b>安全上下文</b>
 * （https 或 localhost）里，{@code http://IP} 上拿不到 Service Worker 也订不上；
 * iOS/iPadOS 只有<b>装到主屏幕</b>的 PWA 才支持推送，Safari 标签页里不行。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WebPushNotifier {

    private final AppProperties appProperties;
    private final WebPushSubscriptionMapper mapper;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** 解析后的 VapidKeys（懒建）；keysFailed 记「配了但读不懂」，避免每条通知都重试一遍异常 */
    private volatile VapidKeys keys;
    private volatile boolean keysFailed;
    /** 与 keys 对应的配置指纹：env 改了（重启前不会变，但测试期可能热改）就重建 */
    private volatile String keysFingerprint;

    /** 连续失败到几次就摘掉这条订阅 */
    private static final int MAX_FAIL = 5;
    /** 队列上限：宁可丢推送也不堆内存——推送是旁路，积压说明外部服务已经打不通了 */
    private static final int QUEUE_CAP = 200;

    private final java.util.concurrent.ExecutorService executor =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(QUEUE_CAP),
                    r -> {
                        Thread t = new Thread(r, "web-push");
                        t.setDaemon(true);
                        return t;
                    },
                    (task, pool) -> {
                        log.warn("[PUSH] 投递队列已满（{}），丢弃最旧一条——推送是旁路，不参与背压", pool.getQueue().size());
                        if (!pool.isShutdown()) {
                            pool.getQueue().poll();
                            pool.execute(task);
                        }
                    });

    public WebPushNotifier(AppProperties appProperties, WebPushSubscriptionMapper mapper,
                           ConfigService configService, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.mapper = mapper;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== 配置面 ====================

    /** env 里配了一对可用密钥（不看平台开关）：决定设置页上是「未配置」还是「未开启」 */
    public boolean keysReady() {
        return keys() != null;
    }

    /** 平台总闸（DB 配置，设置页里那条开关） */
    public boolean enabled() {
        return configService.getBoolean("webpush.enabled", false);
    }

    /** 给浏览器的 applicationServerKey：65 字节未压缩公钥点的 base64url（<b>不是</b> X509/DER 那一份） */
    public String browserPublicKey() {
        VapidKeys k = keys();
        if (k == null) return null;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k.getApplicationServerKey());
    }

    /** 生成一对 VAPID 密钥（只回显，不落库不落盘）：管理员贴进 .env 用 */
    public Map<String, Object> generateKeys() {
        VapidKeys k = VapidKeys.generate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publicKey", k.getX509PublicKey());
        out.put("privateKey", k.getPkcs8PrivateKey());
        out.put("subject", subject());
        out.put("envHint", "AI_VAPID_PUBLIC_KEY / AI_VAPID_PRIVATE_KEY 各贴一行，多副本必须同一对；"
                + "注意这是 X509/PKCS8 的 base64url，与 npx web-push 给的裸公钥编码不同，不能混用");
        return out;
    }

    /**
     * 懒解析 env 里的密钥对。
     * 读不懂只记一次 WARN（每条通知再试一遍等于把同一个异常刷进日志），并对外一律表现为「未配置」。
     */
    private VapidKeys keys() {
        AppProperties.Webpush cfg = appProperties.getWebpush();
        String pub = trim(cfg.getPublicKey()), priv = trim(cfg.getPrivateKey());
        if (pub.isEmpty() || priv.isEmpty()) return null;
        String fp = pub + '\u0000' + priv;
        VapidKeys cached = keys;
        if (cached != null && fp.equals(keysFingerprint)) return cached;
        if (keysFailed && fp.equals(keysFingerprint)) return null;
        try {
            VapidKeys k = VapidKeys.create(pub, priv);
            keys = k;
            keysFingerprint = fp;
            keysFailed = false;
            log.info("[PUSH] VAPID 密钥已装载（公钥 {} 字符，subject={}）", pub.length(), subject());
            return k;
        } catch (Exception e) {
            keysFailed = true;
            keysFingerprint = fp;
            log.warn("[PUSH] env 里的 VAPID 密钥读不懂，Web Push 按未配置处理（期望 X509/PKCS8 的 base64url，"
                    + "可用 POST /ai/api/ai/webpush/keys 现生成一对）: {}", e.getMessage());
            return null;
        }
    }

    private String subject() {
        String s = trim(appProperties.getWebpush().getSubject());
        // 推送服务硬性要求 mailto: 或 https: 开头；配错就回落到一个合法的占位值，
        // 而不是让每次发送都抛 IllegalArgumentException（那会让整条推送静默全灭）
        if (s.startsWith("mailto:") || s.startsWith("https://")) return s;
        return "mailto:admin@wenqu.local";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ==================== 订阅管理 ====================

    /** 订阅（同一 endpoint 再来一次＝刷新密钥与 UA，不产生第二行） */
    public void subscribe(String uid, String endpoint, String p256dh, String auth, String userAgent) {
        if (uid == null || uid.isBlank() || endpoint == null || endpoint.isBlank()
                || p256dh == null || p256dh.isBlank() || auth == null || auth.isBlank()) {
            throw new IllegalArgumentException("endpoint / p256dh / auth 都不能为空");
        }
        if (endpoint.length() > 1000) throw new IllegalArgumentException("endpoint 过长");
        String hash = md5(endpoint.trim());
        WebPushSubscription row = mapper.selectOne(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getEndpointHash, hash).last("LIMIT 1"));
        if (row == null) {
            row = new WebPushSubscription();
            row.setUid(uid);
            row.setEndpointHash(hash);
            row.setEndpoint(endpoint);
            row.setP256dh(p256dh);
            row.setAuthSecret(auth);
            row.setUserAgent(cut(userAgent, 200));
            row.setFailCount(0);
            row.setCreateTime(LocalDateTime.now());
            mapper.insert(row);
            log.info("[PUSH] 新增订阅 uid={} id={} ua={}", uid, row.getId(), row.getUserAgent());
            return;
        }
        // 换人订阅同一个地址（同一浏览器换账号登录）：归属跟着改，否则前一个人的通知会推到现在这个人身上
        row.setUid(uid);
        row.setP256dh(p256dh);
        row.setAuthSecret(auth);
        row.setUserAgent(cut(userAgent, 200));
        row.setFailCount(0);
        mapper.updateById(row);
        log.info("[PUSH] 刷新订阅 uid={} id={}", uid, row.getId());
    }

    /**
     * 退订：按<b>本机自己持有的 endpoint</b> 删（浏览器本地一直存着这份订阅对象）。
     *
     * <p>刻意不按行 id 删：那要求设置页先拿到「哪一行是我这台设备」，而服务端无从知道；
     * 把 endpoint 回给前端换 id 也没必要——那串地址本身就是能往这台设备发消息的凭证，
     * 列表接口干脆不回它。
     */
    public int unsubscribe(String uid, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return 0;
        return mapper.delete(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getUid, uid).eq(WebPushSubscription::getEndpointHash, md5(endpoint.trim())));
    }

    /** 本人已订阅的设备列表（不回 endpoint——那串地址本身就是能发消息的凭证） */
    public List<Map<String, Object>> listDevices(String uid) {
        List<WebPushSubscription> rows = mapper.selectList(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getUid, uid).orderByDesc(WebPushSubscription::getUpdateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebPushSubscription r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userAgent", r.getUserAgent());
            m.put("failCount", r.getFailCount());
            m.put("createTime", r.getCreateTime());
            m.put("updateTime", r.getUpdateTime());
            out.add(m);
        }
        return out;
    }

    // ==================== 发送 ====================

    /**
     * 一条站内通知落库后推给本人的所有设备（旁路：任何异常都只记日志）。
     *
     * <p>调用点只有一处（{@code NotificationService} 插入成功之后）。这里再判一次总闸与密钥，
     * 让「关掉就等于安静」这件事不依赖调用方的顺序。
     */
    public void push(String uid, String type, String title, String content,
                     String refType, String refId, String refSub, String notifId) {
        if (!enabled() || uid == null || uid.isBlank()) return;
        if (keys() == null) return;
        // 取订阅与发送全放进投递线程：调用点是通知落库之后（问答收尾那一拍也可能在请求线程上），
        // 这里不该再顺手打一次数据库
        try {
            executor.execute(() -> {
                List<WebPushSubscription> rows;
                try {
                    rows = mapper.selectList(new LambdaQueryWrapper<WebPushSubscription>()
                            .eq(WebPushSubscription::getUid, uid));
                } catch (Exception e) {
                    log.warn("[PUSH] 取订阅列表失败（不影响站内通知）uid={} err={}", uid, e.getMessage());
                    return;
                }
                if (rows.isEmpty()) return;
                String payload = payload(type, title, content, refType, refId, refSub, notifId);
                for (WebPushSubscription row : rows) sendOne(row, payload, type);
            });
        } catch (Exception e) {
            log.warn("[PUSH] 投递任务入队失败 uid={} err={}", uid, e.getMessage());
        }
    }

    /**
     * 给本人所有设备发一条测试推送，逐台回报结果（设置页那颗「发一条测试」用）。
     * 同步发送：调用方要点一下就知道成不成，异步的话界面只能显示「已提交」而看不出所以然。
     */
    public List<Map<String, Object>> sendTest(String uid) {
        List<WebPushSubscription> rows = mapper.selectList(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getUid, uid));
        List<Map<String, Object>> out = new ArrayList<>();
        String payload = payload("test", "问渠的测试推送",
                enabled() ? "看到这条，说明这台设备能收到通知了" : "平台开关未开启，这条来自手动测试",
                null, null, null, "test-" + System.currentTimeMillis());
        for (WebPushSubscription row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("userAgent", row.getUserAgent());
            try {
                r.put("result", sendOne(row, payload, "test"));
            } catch (Exception e) {
                r.put("result", "failed");
                r.put("error", e.getMessage());
            }
            out.add(r);
        }
        return out;
    }

    /** 发到一台设备。返回 sent / expired（订阅已摘掉）/ failed */
    private String sendOne(WebPushSubscription row, String payload, String type) {
        try {
            WebPushService svc = new WebPushService(subject(), keys());
            // 走 String 重载：库里自己按 UTF-8 取字节、并把 base64url 的 p256dh/auth 解成字节。
            // （byte[] 那个重载要 byte[] 形式的密钥，String 那个才收浏览器给的原样 base64 串）
            WebPush.SubscriptionState st = svc.send(payload, row.getEndpoint(),
                    row.getP256dh(), row.getAuthSecret(),
                    ttlSeconds(), row.getId() == null ? null : "wq-" + row.getId(),
                    WebPush.Urgency.Normal);
            if (st == WebPush.SubscriptionState.EXPIRED) {
                mapper.deleteById(row.getId());
                log.info("[PUSH] 订阅已被推送服务作废，摘掉 id={} uid={}", row.getId(), row.getUid());
                return "expired";
            }
            if (row.getFailCount() != null && row.getFailCount() != 0) {
                row.setFailCount(0);
                mapper.updateById(row);
            }
            log.info("[PUSH] 已推送 id={} uid={} type={}", row.getId(), row.getUid(), type);
            return "sent";
        } catch (Exception e) {
            // Kotlin 侧没有受检异常，WebPushStatusException（401/403 鉴权不认、5xx 服务不认）到 Java
            // 这里只能按普通 Exception 接——想单独 catch 会被编译器判「该 try 体不会抛出它」。
            // 401/403 多半是多副本配了不同的 VAPID 密钥对；5xx 是推送服务临时不认。
            log.warn("[PUSH] 推送失败 id={} uid={} {} {}", row.getId(), row.getUid(),
                    e.getClass().getSimpleName(), e.getMessage());
            bumpFail(row);
            return "failed";
        }
    }

    /** 连续失败累加；到阈值摘掉这条订阅（浏览器早就清了站点数据、地址永远打不通的那种） */
    private void bumpFail(WebPushSubscription row) {
        try {
            int n = (row.getFailCount() == null ? 0 : row.getFailCount()) + 1;
            if (n >= MAX_FAIL) {
                mapper.deleteById(row.getId());
                log.info("[PUSH] 连续 {} 次失败，摘掉订阅 id={} uid={}", n, row.getId(), row.getUid());
                return;
            }
            row.setFailCount(n);
            mapper.updateById(row);
        } catch (Exception e) {
            log.warn("[PUSH] 失败计数写入失败 id={} err={}", row.getId(), e.getMessage());
        }
    }

    /** 载荷在推送服务上能等多久（秒）：过期就不该再弹——用户看到的「这一轮跑完了」必须是还没凉透的消息 */
    private int ttlSeconds() {
        int v = configService.getInt("webpush.ttlSeconds", 86400);
        return v <= 0 ? 86400 : v;
    }

    /**
     * 通知载荷。字段名与 Service Worker 里的读法一一对应（public/sw.js 的 push 事件）。
     * url 在这里算好：点通知时可能一个页面都没开，SW 没有前端路由可复用，只能自带落点。
     */
    private String payload(String type, String title, String content,
                           String refType, String refId, String refSub, String notifId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", title == null || title.isBlank() ? "问渠" : title);
        p.put("body", content == null ? "" : cut(content, 200));
        // tag=通知 id：同 tag 的新通知会替换旧的而不是叠一条（去重合并那一路本来就不推）
        p.put("tag", notifId == null ? "wq-" + System.currentTimeMillis() : notifId);
        p.put("url", targetUrl(type, refType, refId, refSub));
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            return "{\"title\":\"问渠\",\"body\":\"有一条通知\",\"tag\":\"wq\",\"url\":\"/chat\"}";
        }
    }

    /**
     * 点击通知的落点。与铃铛点开那条的跳转<b>同一套语义</b>（前端两处 openNotif：PC 在 AppLayout、
     * 移动壳在 MobileNotifSheet），差别只在会话走 /chat——窄屏由路由守卫自己转到 /m/chat。
     *
     * <p>为什么在这里再写一遍而不是复用：点通知时可能一个页面都没开，SW 里没有前端路由可用，
     * 落点只能随载荷一起给。审批类必须带二级目标（approval / run），否则跳过去只是看见会话
     * 却点不到那张正等人的卡。
     */
    private String targetUrl(String type, String refType, String refId, String refSub) {
        boolean hasSub = refSub != null && !refSub.isBlank();
        if ("workflow.approval".equals(type) && refId != null && hasSub) {
            return "/agents?tab=workflow&wf=" + enc(refId) + "&run=" + enc(refSub);
        }
        if ("tool.approval".equals(type) && "session".equals(refType) && refId != null && hasSub) {
            return "/chat?sid=" + enc(refId) + "&approval=" + enc(refSub);
        }
        if (refId == null || refId.isBlank()) return "/chat";
        if ("session".equals(refType)) return "/chat?sid=" + enc(refId);
        if ("kb".equals(refType)) return "/knowledge/" + enc(refId) + "/docs";
        if ("workflow".equals(refType)) return "/agents?tab=workflow";
        if ("provider".equals(refType)) return "/agents?tab=providers";
        return "/chat";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
