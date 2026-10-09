package com.wenqu.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐步任务清单（todo）：多步任务把"要做哪几件事、做到第几件了"写成一张可见的清单。
 *
 * <p>为什么要有它：一轮长任务里模型调了七八次工具，用户看得见每张工具卡，却看不见
 * "它总共打算做几件事、还剩几件"，跑着跑着就像在原地打转；计划模式那份计划是**一段自由文本**，
 * 批准后整块注入，不维护逐步状态。清单把同一件事变成可勾选的条目。
 *
 * <p>存储口径（刻意）：整表覆盖、只存会话上的一份（{@code c_ai_session.todos}）。清单表达的是
 * "现在还剩几步"，不是历史；历次写入由会话事件账本留痕。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    /** 工具上下文键：本轮清单写入器（闭包持有会话与流，落库 + SSE 都在 RagService 那一侧） */
    public static final String CTX_TODO = "todoWrite";

    /** 单张清单最多条目数：超过就看不出"还剩几步"，也占上下文 */
    static final int MAX_ITEMS = 12;
    /** 单条内容上限（字数）：清单条目是一句话动作，写成段落就说明它不是条目了 */
    static final int MAX_ITEM_CHARS = 200;

    private final SessionService sessionService;

    /** 一条待办：content=要做的事；status=pending|doing|done */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TodoItem(
            @ToolParam(description = "这一项要做的事：一句话、可验收的动作（如「核对共享范围的三种取值」），不要写成段落")
            String content,
            @ToolParam(description = "状态：pending=还没开始，doing=正在做（同时只允许一项），done=已完成")
            String status) {
    }

    /** 清单写入器签名：整表 → 回给模型的文本（含被归一/拒绝的说明） */
    @FunctionalInterface
    public interface WriteFn {
        String write(List<TodoItem> items);
    }

    /**
     * 写入/更新本会话的任务清单（整表替换）。
     */
    @Tool(description = "维护本会话的任务清单（整表替换：每次都传完整的清单，不是只传变化的那几项）。"
            + "只在任务确实需要 3 步以上、且用户会想知道「做到第几步了」时调用；"
            + "一次性问答、两步以内的小事不要调用，清单会变成噪声。"
            + "用法：接到任务先写一份 pending 清单（≤" + MAX_ITEMS + " 项，每项一句话、可验收的动作）；"
            + "每开始一项就把它的 status 改成 doing、上一项改成 done——同时只允许一项 doing；"
            + "全部做完后再写一次，把每一项都标成 done（清单不会自动消失，靠你收尾）。"
            + "中途改了计划也要用本工具把清单更新成新计划。")
    public String writeTodo(
            @ToolParam(description = "完整清单（整表替换）；每项含 content 与 status") List<TodoItem> todos,
            ToolContext toolContext) {
        Object sid = toolContext == null ? null : toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
        Object fn = toolContext == null ? null : toolContext.getContext().get(CTX_TODO);
        if (!(sid instanceof String sessionId) || sessionId.isBlank()) return "错误：无法定位当前会话";
        if (!(fn instanceof WriteFn write)) return "错误：本轮未启用任务清单";
        if (todos == null || todos.isEmpty()) return "错误：清单为空。要收尾请把每一项显式标为 done 后整表传回。";
        return write.write(todos);
    }

    /**
     * 校验并归一模型传来的清单（不抛异常：工具报错会让模型反复重试，这里能救的都救）。
     *
     * @return 归一后的条目 + 一句给用户看的说明（被截掉/被改掉的项）
     */
    public Normalized normalize(List<TodoItem> raw) {
        List<TodoItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean doingTaken = false;
        for (TodoItem it : raw) {
            if (it == null) continue;
            String content = it.content() == null ? "" : it.content().strip();
            if (content.isEmpty()) continue;
            if (content.length() > MAX_ITEM_CHARS) {
                content = content.substring(0, MAX_ITEM_CHARS) + "…";
                notes.add("有条目过长，已截断（清单条目请写一句话）");
            }
            String status = it.status() == null ? "pending" : it.status().strip().toLowerCase();
            if (!status.equals("pending") && !status.equals("doing") && !status.equals("done")) {
                notes.add("状态「" + it.status() + "」不认识，按 pending 处理");
                status = "pending";
            }
            if (status.equals("doing")) {
                if (doingTaken) {
                    notes.add("同时有多项标成进行中，已把靠后的降为待办（一次只做一件事）");
                    status = "pending";
                } else {
                    doingTaken = true;
                }
            }
            if (items.size() >= MAX_ITEMS) {
                notes.add("清单最多 " + MAX_ITEMS + " 项，多出的已丢弃（请合并步骤或分轮进行）");
                break;
            }
            items.add(new TodoItem(content, status));
        }
        if (items.isEmpty()) notes.add("清单为空，未更新");
        return new Normalized(items, notes);
    }

    /** 归一结果 */
    public record Normalized(List<TodoItem> items, List<String> notes) {
        public boolean ok() {
            return !items.isEmpty();
        }
    }

    /** 组装清单文档（同一份 JSON 既落库、也作 SSE 载荷与账本载荷，避免三处各拼一遍对不上号） */
    public String docJson(String turnId, List<TodoItem> items) {
        // 手工组装而不是直接序列化 record：fastjson 1.x 认不出 record 的访问式取值，
        // 整表会被序列化成 [{},{},{}]（实测：清单落库了但条目全空，右栏渲染成一片空白）
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (TodoItem i : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", i.content());
            row.put("status", i.status());
            rows.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("turnId", turnId);
        doc.put("items", rows);
        doc.put("updatedAt", LocalDateTime.now().withNano(0).toString());
        return com.alibaba.fastjson.JSON.toJSONString(doc);
    }

    /** 整表覆盖落库 */
    public void save(String sessionId, String doc) {
        sessionService.saveTodos(sessionId, doc);
    }

    /** 回给模型的文本：清单快照 + 被归一/截断的说明 + 下一步该怎么维护 */
    public String describe(Normalized n) {
        long done = n.items().stream().filter(i -> i.status().equals("done")).count();
        long doing = n.items().stream().filter(i -> i.status().equals("doing")).count();
        StringBuilder sb = new StringBuilder();
        sb.append("任务清单已更新（共 ").append(n.items().size()).append(" 项：完成 ")
                .append(done).append("、进行中 ").append(doing).append("、待办 ")
                .append(n.items().size() - done - doing).append("）。\n");
        for (TodoItem i : n.items()) {
            sb.append(i.status().equals("done") ? "- [x] " : i.status().equals("doing") ? "- [>] " : "- [ ] ")
                    .append(i.content()).append('\n');
        }
        if (!n.notes().isEmpty()) {
            sb.append("注意：").append(String.join("；", n.notes())).append('\n');
        }
        sb.append("请继续推进；开始下一项时把它改成 doing、上一项改成 done，全部完成后把每项都标成 done。");
        return sb.toString();
    }
}
