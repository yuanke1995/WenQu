package com.wenqu.ai.startup;

import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SandboxService;
import com.wenqu.ai.service.SessionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 沙盒热池预热：启动后把最近活跃用户的「用户级沙盒」提前建好。
 *
 * <p>为什么是<b>每用户</b>而不是跨用户通用池：沙盒按 uid 挂载用户数据卷，跨用户共用容器会让
 * 别人的文件/进程可见，隔离直接破掉。当前挂载模型下安全的通用池只能是「每个活跃用户一个预创建容器」。
 * 依赖 {@code sandbox.userScope=true}（作用域=uid），否则预热出来的会话级容器不会被后续会话复用。
 *
 * <p>有界且旁路：默认 {@code sandbox.poolWarmUsers=0} 关闭；开启后最多取 N 个最近用户，
 * 每个走 SandboxService 的单线程有界 provisionPool，失败只记日志，不阻塞启动、不影响问答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxPoolWarmer {

    private final ConfigService configService;
    private final SessionService sessionService;
    private final SandboxService sandboxService;

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        try {
            if (!configService.getBoolean("sandbox.userScope", false)) {
                return;
            }
            int maxUsers = configService.getInt("sandbox.poolWarmUsers", 0);
            if (maxUsers <= 0) {
                return;
            }
            List<String> uids = sessionService.recentUserIds(Math.min(maxUsers, 5));
            for (String uid : uids) {
                sandboxService.prewarmUser(uid);
            }
            log.info("[SANDBOX] 热池预热已提交：{} 个最近用户（上限 {}）", uids.size(), maxUsers);
        } catch (Exception e) {
            log.warn("[SANDBOX] 热池预热启动失败（不影响问答）: {}", e.toString());
        }
    }

    /**
     * 保存即生效：改 {@code sandbox.poolWarmUsers} 后按新上限再预热一次，效果等同于重启。
     * warm() 自带开关与上限判断（0=不预热），prewarmUser 走在飞集合去重，重复提交不会建出第二个容器。
     */
    @jakarta.annotation.PostConstruct
    void registerWarmHotReload() {
        configService.addChangeListener(changed -> {
            if (changed != null && changed.contains("sandbox.poolWarmUsers")) warm();
        });
    }
}
