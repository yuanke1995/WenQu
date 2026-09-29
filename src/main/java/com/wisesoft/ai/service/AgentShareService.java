package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.AgentShareMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.AgentShare;
import com.wisesoft.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 智能体公开分享服务（/s/{token} 免登录对话）
 * <p>
 * 发布（publish）：一个智能体一条配置，token 生成后不变（停用=enabled=0，撤销=删行）；
 * 游客解析（resolveGuest）：token → 分享配置 + 智能体 + 发布者档案三件套，
 * 供 ShareController 组装游客会话与受限对话（RagService 游客模式）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentShareService {

    private final AgentShareMapper shareMapper;
    private final AgentService agentService;
    private final UserMapper userMapper;

    /** token 字符集（无易混淆字符的 base32 子集，32 位；URL 安全） */
    private static final String TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_LENGTH = 32;

    /** 游客会话归属 uid 前缀（非真实用户，SessionService 按 owner 精确匹配天然隔离） */
    public static final String VISITOR_UID_PREFIX = "share-visitor:";

    /** 游客上下文：分享配置 + 被分享智能体 + 发布者档案（检索可见性/默认模型按发布者执行） */
    public record GuestContext(AgentShare share, Agent agent, User owner) {
    }

    /** 按智能体取分享配置（未发布返回 null） */
    public AgentShare getByAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        return shareMapper.selectOne(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getAgentId, agentId)
                .last("limit 1"));
    }

    /**
     * 发布/更新分享配置：首次生成 token（此后不变）；modelRef 空串=清空（回退发布者个人默认模型）。
     * mcpEnabled 控制同一个 token 是否同时作为 MCP 端点（/ai/mcp/{token}）对外提供。
     * 由 AgentController 在归属校验（canManage）通过后调用。
     */
    public AgentShare publish(String agentId, boolean enabled, boolean mcpEnabled, String modelRef, String uid) {
        AgentShare share = getByAgent(agentId);
        if (share == null) {
            share = new AgentShare();
            share.setAgentId(agentId);
            share.setToken(generateToken());
            share.setCreatedBy(uid);
        }
        share.setEnabled(enabled ? 1 : 0);
        share.setMcpEnabled(mcpEnabled ? 1 : 0);
        share.setModelRef(modelRef == null || modelRef.isBlank() ? null : modelRef.trim());
        if (share.getId() == null) {
            shareMapper.insert(share);
        } else {
            shareMapper.updateById(share);
        }
        log.info("[AUDIT] 智能体分享发布 operator={} agent={} enabled={} mcp={} token={}***",
                uid, agentId, enabled, mcpEnabled, share.getToken().substring(0, 6));
        return share;
    }

    /** 撤销分享（删行；链接立即失效） */
    public void revoke(String agentId) {
        shareMapper.delete(new LambdaQueryWrapper<AgentShare>().eq(AgentShare::getAgentId, agentId));
    }

    /**
     * 游客解析：token → 可用的分享上下文。逐项 fail-loud：
     * 配置不存在/已停用 → 404（不泄露存在性）；智能体已删除 → 404；发布者档案缺失 → 404。
     */
    public GuestContext resolveGuest(String token) {
        if (token == null || token.isBlank()) throw new BizException("分享链接无效");
        AgentShare share = shareMapper.selectOne(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getToken, token.trim())
                .last("limit 1"));
        if (share == null || share.getEnabled() == null || share.getEnabled() != 1) {
            throw new BizException("分享不存在或已停止访问");
        }
        Agent agent = agentService.get(share.getAgentId());
        if (agent == null) throw new BizException("分享不存在或已停止访问");
        User owner = share.getCreatedBy() == null ? null : userMapper.selectById(share.getCreatedBy());
        if (owner == null) throw new BizException("分享不存在或已停止访问");
        return new GuestContext(share, agent, owner);
    }

    /**
     * 游客 uid：客户端生成的随机 ID 加固定前缀（不与真实 uid 冲突；
     * SessionService.assertOwned 按会话 owner 精确匹配，游客只能读写自己的会话）。
     * 格式校验防注入：仅 [a-zA-Z0-9-]，8~64 位。
     */
    public static String visitorUid(String visitorId) {
        if (visitorId == null || !visitorId.matches("[a-zA-Z0-9-]{8,64}")) {
            throw new BizException("访客标识不合法");
        }
        return VISITOR_UID_PREFIX + visitorId;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (byte b : bytes) {
            sb.append(TOKEN_ALPHABET.charAt((b & 0xFF) % TOKEN_ALPHABET.length()));
        }
        return sb.toString();
    }

    /**
     * 发布访问统计：每次游客成功发起对话 +1（best-effort，失败仅日志，绝不影响对话主链路）。
     * 用 COALESCE 兜底未补列的老实例（SchemaMigrator 补列前 visit_count 为 NULL）。
     */
    public void incrementVisit(String shareId) {
        if (shareId == null || shareId.isBlank()) return;
        try {
            shareMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentShare>()
                    .eq(AgentShare::getId, shareId)
                    .setSql("visit_count = COALESCE(visit_count, 0) + 1")
                    .set(AgentShare::getLastVisitAt, java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[Share] 访问统计 +1 失败 share={}: {}", shareId, e.getMessage());
        }
    }
}
