package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI function calling 映射契约。
 * <p>只验证本仓自己的转换与过滤口径：done payload 的 toolCalls → OpenAI tool_calls、
 * 未声明/副作用工具不出现。真实模型调用链由端点 E2E 覆盖（本机环境模型/检索可用性不稳定，不在此断言）。
 */
class OpenAiCompatToolCallsTest {

    private static List<Map<String, Object>> declared(JSONObject done, Set<String> names) throws Exception {
        OpenAiCompatController c = new OpenAiCompatController(null, null, null, null);
        Method m = OpenAiCompatController.class.getDeclaredMethod("declaredToolCalls", JSONObject.class, Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) m.invoke(c, done, names);
        return out;
    }

    private static boolean sideEffect(String name) throws Exception {
        Method m = OpenAiCompatController.class.getDeclaredMethod("sideEffectTool", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, name);
    }

    @Test
    void onlyDeclaredReadOnlyCallsAreReturned() throws Exception {
        JSONObject done = new JSONObject();
        JSONArray calls = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("name", "search_knowledge");
        a.put("args", "{\"query\":\"问渠定位\"}");
        calls.add(a);
        JSONObject b = new JSONObject();
        b.put("name", "run_code");
        b.put("args", "{\"code\":\"x\"}");
        calls.add(b);
        done.put("toolCalls", calls);

        Set<String> declared = new LinkedHashSet<>(List.of("search_knowledge"));
        List<Map<String, Object>> out = declared(done, declared);
        assertEquals(1, out.size(), "副作用 run_code 即使被模型调用也不能回给客户端");
        Map<String, Object> fn = (Map<String, Object>) out.get(0).get("function");
        assertEquals("search_knowledge", fn.get("name"));
        assertEquals("{\"query\":\"问渠定位\"}", fn.get("arguments"));
        assertEquals("function", out.get(0).get("type"));
        assertTrue(String.valueOf(out.get(0).get("id")).startsWith("call_"));
    }

    @Test
    void callsNotDeclaredAreIgnored() throws Exception {
        JSONObject done = JSON.parseObject("{\"toolCalls\":[{\"name\":\"session_list\",\"args\":\"{}\"}]}");
        assertEquals(0, declared(done, Set.of("search_knowledge")).size());
        assertEquals(0, declared(done, Set.of()).size());
        assertEquals(0, declared(new JSONObject(), Set.of("search_knowledge")).size());
    }

    @Test
    void argumentsFallbackToEmptyObject() throws Exception {
        JSONObject done = JSON.parseObject("{\"toolCalls\":[{\"name\":\"session_list\"}]}");
        List<Map<String, Object>> out = declared(done, Set.of("session_list"));
        assertEquals(1, out.size());
        assertEquals("{}", ((Map<String, Object>) out.get(0).get("function")).get("arguments"));
    }

    @Test
    void sideEffectToolsAreBlocked() throws Exception {
        for (String n : List.of("run_code", "run_code_script", "write_file", "edit_file", "presentArtifact",
                "writeTodo", "askUser", "goal_submit", "plan_enter", "agent_spawn", "task_list",
                "wen_qu_search", "wf_weekly_report", "localBrowser_open")) {
            assertTrue(sideEffect(n), n + " 必须判为副作用工具");
        }
        for (String n : List.of("search_knowledge", "read_file", "list_files", "grep_files",
                "session_list", "memory_search", "web_search")) {
            assertFalse(sideEffect(n), n + " 应判为只读工具");
        }
    }
}
