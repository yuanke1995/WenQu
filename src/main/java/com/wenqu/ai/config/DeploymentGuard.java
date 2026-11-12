package com.wenqu.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 部署形态闸：当前版本只支持单副本。
 *
 * <p>轮级互斥、会话事件日志与框架会话日志仍带单副本语义（事件日志在本地盘、轮状态未全部外置）。
 * 与其在多副本下出现「两个实例同时跑同一会话」的静默错位，不如启动即拒绝。
 * 真多副本要等 S2-2 状态外置完成后再放开。
 */
@Slf4j
@Component
public class DeploymentGuard {

    private final String mode;
    private final String profile;

    public DeploymentGuard(@Value("${wenqu.deployment.mode:single}") String mode,
                           @Value("${wenqu.deployment.profile:standard}") String profile) {
        this.mode = mode;
        this.profile = (profile == null || profile.isBlank()) ? "standard" : profile.trim().toLowerCase();
    }

    /** 当前部署档位：standard=个人/小团队（企业面隐藏），enterprise=多租户/SSO/审计/配额/成本全开。 */
    public String profile() {
        return profile;
    }

    @PostConstruct
    public void check() {
        if (!"single".equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            throw new IllegalStateException(
                    "当前版本只支持单副本部署（wenqu.deployment.mode=single）：轮级互斥与会话日志仍依赖单副本语义；"
                            + "多副本需先完成状态外置（S2-2）。");
        }
        if (!"standard".equals(profile) && !"enterprise".equals(profile)) {
            throw new IllegalStateException(
                    "未知部署档位 wenqu.deployment.profile=" + profile + "（只支持 standard / enterprise）");
        }
        log.warn("[DEPLOY] 单副本模式 profile={}：{}",
                profile, "standard".equals(profile) ? "企业面隐藏" : "企业面全开");
    }
}
