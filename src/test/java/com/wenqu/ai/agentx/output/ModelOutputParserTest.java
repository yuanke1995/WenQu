package com.wenqu.ai.agentx.output;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ModelOutputParser 的孤儿闭标签闸与既有行为的回归。本目录约定不入库。 */
class ModelOutputParserTest {

    /** 只有第一块带开标签，后面把闭标签当每条建议的收尾符。 */
    @Test
    void orphanClosesAreSwallowedAndFirstBlockStillChips() {
        String raw = "结论一句。\n\n<related>把「申请试用」按钮接到真实表单或邮箱，我直接改好"
                + "</related>|补一段公司介绍和两三个客户案例，我加进页面</related>"
                + "|出一版深色主题为主的视觉方案对比看看</related>";
        ModelOutputParser p = new ModelOutputParser();
        // 按 3 字符一块流式喂入，模拟真实增量
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 3) {
            body.append(p.feed(raw.substring(i, Math.min(raw.length(), i + 3))).text());
        }
        body.append(p.finish().text());
        assertEquals("结论一句。\n\n|补一段公司介绍和两三个客户案例，我加进页面"
                        + "|出一版深色主题为主的视觉方案对比看看",
                body.toString(), "正文里不能出现任何标签原文");
        assertEquals(List.of("把「申请试用」按钮接到真实表单或邮箱，我直接改好"),
                p.chips(80, 5), "第一块照常成芯片");
    }

    /** 整段只有孤儿闭标签、一个开标签都没有：标签照样吞，内容全留正文。 */
    @Test
    void allOrphanClosesWithoutAnyOpen() {
        ModelOutputParser p = new ModelOutputParser();
        StringBuilder body = new StringBuilder();
        for (String part : new String[]{"建议A</related>", "建议B</related>"}) {
            body.append(p.feed(part).text());
        }
        body.append(p.finish().text());
        assertEquals("建议A建议B", body.toString());
    }

    /** 前缀扣留：跨增量拆开的闭标签不能漏进正文，也不能吞掉长得像前缀的普通文本。 */
    @Test
    void closeTagSplitAcrossDeltasAndLookalikeText() {
        ModelOutputParser p = new ModelOutputParser();
        StringBuilder body = new StringBuilder();
        for (String part : new String[]{"对比 </r", "elated> 写法说明", "与 </b> 标签"}) {
            body.append(p.feed(part).text());
        }
        body.append(p.finish().text());
        // "对比 </related>" 的闭标签被吞；"</b>" 不是闭标签前缀，原样保留
        assertEquals("对比  写法说明与 </b> 标签", body.toString());
    }

    /** 正常两块 + 未闭合块（流截断）的口径不回归。 */
    @Test
    void normalBlocksAndUnclosedStillWork() {
        ModelOutputParser p = new ModelOutputParser();
        String raw = "A<related>问1|问2</related>B<related>问3";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 5) {
            body.append(p.feed(raw.substring(i, Math.min(raw.length(), i + 5))).text());
        }
        body.append(p.finish().text());
        assertEquals("AB", body.toString(), "未闭合块整段归 related，不进正文");
        assertEquals(List.of("问1", "问2", "问3"), p.chips(80, 5));
        assertTrue(p.hasRelated());
    }
}
