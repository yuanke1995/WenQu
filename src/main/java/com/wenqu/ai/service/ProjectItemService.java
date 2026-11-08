package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.ProjectItemMapper;
import com.wenqu.ai.model.ProjectItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 工作区事项清单（C2）：记下 → 去聊 → 勾掉的最短闭环。
 * <p>只做归属与状态，不做权限模型；工作区不是权限边界（与 {@link ProjectService} 同口径）。
 * 状态不接受自动推断，调用方只能在用户动作后显式改。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectItemService {

    private static final int MAX_TITLE = 200;
    private static final int MAX_NOTE = 4000;
    private static final Set<String> STATUSES = Set.of(
            ProjectItem.STATUS_OPEN, ProjectItem.STATUS_DOING, ProjectItem.STATUS_DONE);

    private final ProjectItemMapper itemMapper;
    private final ProjectService projectService;

    public List<ProjectItem> list(String uid, String projectId) {
        ownedProject(uid, projectId);
        List<ProjectItem> list = itemMapper.selectList(new LambdaQueryWrapper<ProjectItem>()
                .eq(ProjectItem::getProjectId, projectId)
                .eq(ProjectItem::getUid, uid)
                .orderByAsc(ProjectItem::getSortNo)
                .orderByAsc(ProjectItem::getCreateTime));
        return list == null ? List.of() : list;
    }

    public ProjectItem create(String uid, String projectId, String title, String note, String sessionId) {
        ownedProject(uid, projectId);
        String t = trimTitle(title);
        ProjectItem item = new ProjectItem();
        item.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        item.setUid(uid);
        item.setProjectId(projectId);
        item.setTitle(t);
        item.setNote(trimNote(note));
        item.setStatus(ProjectItem.STATUS_OPEN);
        item.setSessionId(blankToNull(sessionId));
        item.setSortNo(nextSort(uid, projectId));
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        itemMapper.insert(item);
        return item;
    }

    /**
     * 更新事项：传才改。状态只接受 open/doing/done，非法值报可读错误而不是静默丢弃。
     */
    public ProjectItem update(String uid, String projectId, String itemId,
                              String title, String note, String status, String sessionId) {
        ownedProject(uid, projectId);
        ProjectItem item = ownedItem(uid, projectId, itemId);
        LambdaUpdateWrapper<ProjectItem> w = new LambdaUpdateWrapper<ProjectItem>()
                .eq(ProjectItem::getId, item.getId())
                .eq(ProjectItem::getProjectId, projectId)
                .eq(ProjectItem::getUid, uid);
        if (title != null) w.set(ProjectItem::getTitle, trimTitle(title));
        if (note != null) w.set(ProjectItem::getNote, trimNote(note));
        if (status != null) {
            String s = status.trim().toLowerCase();
            if (!STATUSES.contains(s)) throw new IllegalArgumentException("状态只能是 open / doing / done");
            w.set(ProjectItem::getStatus, s);
        }
        if (sessionId != null) w.set(ProjectItem::getSessionId, blankToNull(sessionId));
        w.set(ProjectItem::getUpdateTime, LocalDateTime.now());
        itemMapper.update(null, w);
        return ownedItem(uid, projectId, itemId);
    }

    public void delete(String uid, String projectId, String itemId) {
        ownedProject(uid, projectId);
        ProjectItem item = ownedItem(uid, projectId, itemId);
        itemMapper.deleteById(item.getId());
    }

    /**
     * 模板复制（D）：把源工作区的事项清单抄到新工作区，状态一律回到 open——
     * 模板是「开始一个新场子」，不该把上一场的完成态带过来。
     */
    public int copyItems(String uid, String fromProjectId, String toProjectId) {
        List<ProjectItem> source = list(uid, fromProjectId);
        int n = 0;
        for (ProjectItem s : source) {
            ProjectItem item = new ProjectItem();
            item.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
            item.setUid(uid);
            item.setProjectId(toProjectId);
            item.setTitle(s.getTitle());
            item.setNote(s.getNote());
            item.setStatus(ProjectItem.STATUS_OPEN);
            item.setSessionId(null);
            item.setSortNo(s.getSortNo() == null ? n : s.getSortNo());
            item.setCreateTime(LocalDateTime.now());
            item.setUpdateTime(LocalDateTime.now());
            itemMapper.insert(item);
            n++;
        }
        log.info("[PROJECT-ITEM] 模板复制事项 uid={} from={} to={} 数量={}", uid, fromProjectId, toProjectId, n);
        return n;
    }

    private int nextSort(String uid, String projectId) {
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<ProjectItem>()
                .eq(ProjectItem::getProjectId, projectId)
                .eq(ProjectItem::getUid, uid));
        return count == null ? 0 : count.intValue();
    }

    private void ownedProject(String uid, String projectId) {
        if (projectService.ownedOrNull(uid, projectId) == null) {
            throw new IllegalArgumentException("工作区不存在或不属于你：" + projectId);
        }
    }

    private ProjectItem ownedItem(String uid, String projectId, String itemId) {
        ProjectItem item = itemId == null || itemId.isBlank() ? null : itemMapper.selectById(itemId);
        if (item == null || !projectId.equals(item.getProjectId())
                || !uid.equals(item.getUid())) {
            throw new IllegalArgumentException("事项不存在或不属于这个工作区");
        }
        return item;
    }

    private static String trimTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("事项标题不能为空");
        if (t.length() > MAX_TITLE) throw new IllegalArgumentException("事项标题过长（最多 " + MAX_TITLE + " 字）");
        return t;
    }

    private static String trimNote(String note) {
        if (note == null) return null;
        String n = note.trim();
        if (n.isEmpty()) return null;
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("事项说明过长（最多 " + MAX_NOTE + " 字）");
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isBlank() ? null : s.trim();
    }
}
