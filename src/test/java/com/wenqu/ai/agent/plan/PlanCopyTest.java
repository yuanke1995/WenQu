package com.wenqu.ai.agent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划正文的用户口径账。
 * <p>真机症状（2026-10-15 21:41／21:55／21:57 三条计划卡，本机库 {@code c_ai_tool_approval}
 * 里 {@code tool_name='planApproval'} 的 {@code request_args}）：用户要批准的那份计划写着
 * <b>「单文件 {@code /home/gem/user-data/index.html}」</b>——沙盒里的工作目录被原样印到了界面上。
 * <p>根因是退役手写推理循环时，那条「计划只写用户关心的结果，不写工具名与内部做法」的指令
 * 一起没了，而框架这一路反而<b>主动</b>把路径喂给模型（PLAN banner 的 {@code Plan file: <路径>}、
 * 沙盒工具参数说明里的 {@code /home/gem/user-data/report.md} 示例）。
 * <p>本测试钉住兜底那一半：<b>模型照抄时用户也看不到内部路径</b>，同时不许误伤网址、接口路径与比例。
 */
@DisplayName("计划正文口径：内部路径收成文件名，网址与接口路径不动")
class PlanCopyTest {

    private static final List<String> EXTRA_ROOTS = List.of("/home/gem/user-data", "/sandbox/work");

    private static String strip(String text) {
        return PlanCopy.stripInternalPaths(text, EXTRA_ROOTS);
    }

    @Test
    @DisplayName("真机那条：沙盒目录下的交付文件只留文件名")
    void 沙盒路径收成文件名() {
        String 真机原文 = "产出一个可直接双击打开的官网首页：**单文件 `/home/gem/user-data/index.html`**，"
                + "内联 CSS/JS、零外部依赖。";
        String out = strip(真机原文);
        assertFalse(out.contains("/home/gem"), "内部路径必须不见：\n" + out);
        assertTrue(out.contains("`index.html`"), "文件名要留着——用户认交付物靠的就是它：\n" + out);
        assertTrue(out.contains("可直接双击打开"), "除路径之外的正文一个字不许丢");
    }

    @Test
    @DisplayName("目录本身换成「沙盒工作区」，不裸留 user-data")
    void 目录本身给用户一个能懂的说法() {
        assertTrue(strip("报告放在 `/home/gem/user-data` 里").contains("沙盒工作区"));
        assertTrue(strip("先看看 `/sandbox/work`").contains("沙盒工作区"));
    }

    @Test
    @DisplayName("框架那份计划文件（plans/PLAN.md）不算交付物")
    void 计划文件换成说法() {
        String out = strip("详细步骤见 /Users/yuki/IdeaProjects/wenqu/user-data/agentscope/admin/plans/PLAN.md");
        assertFalse(out.contains("PLAN.md"), "框架内部稿不该出现在计划里：\n" + out);
        assertFalse(out.contains("wenqu/user-data"), "运行目录同样不许出现：\n" + out);
        assertTrue(out.contains("本轮计划"));
    }

    @Test
    @DisplayName("名单外的绝对路径：带扩展名的收成文件名，接口路径与比例原样放过")
    void 不误伤() {
        assertEquals("取 app.py", strip("取 /srv/deploy/current/app.py"));
        assertEquals("接口 /api/chat 与 /v1/chat/completions", strip("接口 /api/chat 与 /v1/chat/completions"));
        assertEquals("断点 768/1023 与 1024/767", strip("断点 768/1023 与 1024/767"));
        assertEquals("素材见 https://example.com/a.png", strip("素材见 https://example.com/a.png"));
        assertEquals("脚本走 //cdn.example.com/lib.js", strip("脚本走 //cdn.example.com/lib.js"));
        assertEquals("闭合看 `</style>` 这一段", strip("闭合看 `</style>` 这一段"));
    }

    @Test
    @DisplayName("没有斜杠的正文原样返回（多数计划本来就没有路径，兜底不许动它们）")
    void 干净正文不动() {
        String clean = "# 首页计划\n\n1. 首屏讲清产品定位\n2. 三张能力卡\n";
        assertEquals(clean, strip(clean));
        assertEquals(null, strip(null));
    }

    @Test
    @DisplayName("提示词那一半：说要紧的两条禁令，且是给系统提示词尾部追加的一整块")
    void 提示词有禁令() {
        String g = PlanCopy.guidance();
        assertTrue(g.startsWith("\n") && g.endsWith("\n"), "系统提示词是拼接的，前后各留一个换行才不粘上一句");
        assertTrue(g.contains("路径") && g.contains("工具名"), "禁令要点名两类内部做法");
        assertTrue(g.contains("计划"), "计划正文是这次真机出事的那一块");
    }
}
