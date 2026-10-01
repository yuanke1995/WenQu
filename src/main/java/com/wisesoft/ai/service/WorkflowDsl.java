package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作流 DSL（唯一真源）：节点/边/变量引用/画布坐标全在这份 JSON 里，
 * 画布只是编辑器、执行引擎只认它（架构决策见排班计划 2026-09-29）。
 * <p>
 * 变量引用语法：<code>{{nodeId.输出键}}</code>（<code>start</code> 为固定的开始节点 id 语义，
 * 引用开始节点入参写 <code>{{start.输入键}}</code>）。
 * <p>
 * 解析用 fastjson2，未知字段忽略——DSL 演进（新增节点配置项）对旧解析器向前兼容。
 *
 * @author yuanke
 */
@Data
public class WorkflowDsl {

    /** DSL 版本（结构演进用，当前恒为 1） */
    private int version = 1;

    private List<Node> nodes = new ArrayList<>();

    private List<Edge> edges = new ArrayList<>();

    /** 语法解析：JSON 非法时抛 fastjson2 异常（消息含 offset，服务层包装成用户可读提示） */
    public static WorkflowDsl parse(String text) {
        return JSON.parseObject(text, WorkflowDsl.class);
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    @Data
    public static class Node {
        /** 节点 id（图内唯一，变量引用用它寻址） */
        private String id;
        /** 节点类型（注册表见 {@link WorkflowValidator#KNOWN_TYPES}） */
        private String type;
        /** 画布坐标 {x,y}（纯展示元数据，引擎不读） */
        private Map<String, Object> position;
        /** 类型自有配置（start.inputs / end.outputs / llm.modelRef+prompt / retrieval.query+topK / condition.branches ...） */
        private Map<String, Object> config;

        /** 配置写入便捷方法（map 惰性初始化）——模板构造与编排转换用，免去各处判 null */
        public void configPut(String key, Object value) {
            if (config == null) config = new java.util.LinkedHashMap<>();
            config.put(key, value);
        }
    }

    @Data
    public static class Edge {
        /** 起点节点 id */
        private String from;
        /** 终点节点 id */
        private String to;
        /** 条件出边的分支键：仅 condition 节点的出边允许且必须携带，且必须在该节点 config.branches 里声明过 */
        private String branch;
    }
}
