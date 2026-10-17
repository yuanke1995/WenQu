package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存量归属回填（立项卡 §三 第 3 步：管理员触发的一次性端点）。
 *
 * <p><b>为什么不自动跑</b>：回填会改动历史行的归属，属于"可回退但影响面大"的动作，
 * 由管理员显式触发、并在返回里给出逐项数量，比启动时静默改库更容易核对。
 *
 * <p><b>为什么不是"猜"</b>：只处理"没有任何归属"（`project_id IS NULL`）的历史行，
 * 把它们归到**各自的兜底容器**。这不是按时间/文件名推断某个具体工作区，
 * 而是承认"这些数据产生于工作区概念出现之前，它们本来就属于该用户的默认容器"。
 * 有明确归属的行一行都不动。
 *
 * <p><b>幂等</b>：回填后再跑一次，命中 0 行；可安全重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBackfillService {

    private final ProjectService projectService;
    private final SessionMapper sessionMapper;
    private final ArtifactMapper artifactMapper;

    /**
     * 把每个用户「无归属」的历史会话与产物，归到该用户自己的兜底容器。
     *
     * @return 统计：涉及用户数 / 会话回填数 / 产物回填数
     */
    public Map<String, Object> backfillLegacyOwnership() {
        Set<String> uids = new LinkedHashSet<>();
        List<Session> sessionOwners = sessionMapper.selectList(new QueryWrapper<Session>()
                .select("DISTINCT user_id").isNull("project_id"));
        for (Session s : sessionOwners) {
            if (s.getUserId() != null && !s.getUserId().isBlank()) uids.add(s.getUserId());
        }
        List<Artifact> artifactOwners = artifactMapper.selectList(new QueryWrapper<Artifact>()
                .select("DISTINCT uid").isNull("project_id"));
        for (Artifact a : artifactOwners) {
            if (a.getUid() != null && !a.getUid().isBlank()) uids.add(a.getUid());
        }

        int sessions = 0;
        int artifacts = 0;
        for (String uid : uids) {
            Project implicit = projectService.ensureImplicit(uid);
            if (implicit == null || implicit.getId() == null) {
                log.warn("[PROJECT] 回填跳过 uid={}（兜底容器建不出来）", uid);
                continue;
            }
            sessions += sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getUserId, uid)
                    .isNull(Session::getProjectId)
                    .set(Session::getProjectId, implicit.getId()));
            artifacts += artifactMapper.update(null, new LambdaUpdateWrapper<Artifact>()
                    .eq(Artifact::getUid, uid)
                    .isNull(Artifact::getProjectId)
                    .set(Artifact::getProjectId, implicit.getId()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", uids.size());
        out.put("sessions", sessions);
        out.put("artifacts", artifacts);
        log.info("[PROJECT] 存量归属回填完成：用户 {} 个 / 会话 {} 条 / 产物 {} 条", uids.size(), sessions, artifacts);
        return out;
    }
}
