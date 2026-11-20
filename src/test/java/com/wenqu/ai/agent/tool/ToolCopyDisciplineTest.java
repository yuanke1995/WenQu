package com.wenqu.ai.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具说明的机械护栏（口径见 {@code .zcode/skills/wenqu-dev/references/tool-copy.md}）。
 *
 * <p>查三件机器能查的事：<b>必须有说明</b>、<b>不能太长</b>、<b>不许写「必须调用本工具／别凭记忆」</b>这类
 * 元指令——它们会把只读工具写成「本机能力探针」。
 *
 * <p>为什么扫编译产物而不是源码：源码里的说明是多段字符串拼接，正则拼回来等于再实现一遍 Java 词法；
 * 类文件上是 {@link Tool} 注解的最终值，扫它才是「模型真收到的样子」。
 *
 * <p>上限 {@value #MAX_CHARS} 是护栏不是目标：当前最长 348 字（任务清单这类规则本身多的工具），
 * 留余量给正常修订，防止悄悄长回去。
 */
class ToolCopyDisciplineTest {

    /** 单条工具说明的字数上限。 */
    private static final int MAX_CHARS = 400;

    /**
     * 明令不许出现在工具说明里的元指令片段。
     * <p>口径要窄：禁的是「逼模型调工具代替思考/回忆」这类元指令，不是所有提到记忆的句子——
     * 「不要凭记忆编造搜索结果」（联网搜索）是正常的防编造约束，留着。
     */
    private static final List<String> BANNED = List.of(
            "必须调用本工具", "务必调用", "凭记忆回答", "不要凭记忆或知识库回答");

    @Test
    void everyToolDescriptionIsPresentBoundedAndFreeOfMetaInstructions() throws Exception {
        List<Method> tools = toolMethods();

        // 扫不到就说明扫描本身坏了（路径/类加载变了），这条会让它当场暴露，而不是静默 0 命中
        assertThat(tools).as("扫描到的 @Tool 方法数").hasSizeGreaterThanOrEqualTo(30);

        for (Method m : tools) {
            Tool tool = m.getAnnotation(Tool.class);
            String who = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
            String desc = tool.description();

            assertThat(desc).as("%s 缺工具说明", who).isNotBlank();
            assertThat(desc.length()).as("%s 说明过长（%d 字）", who, desc.length())
                    .isLessThanOrEqualTo(MAX_CHARS);
            for (String banned : BANNED) {
                assertThat(desc).as("%s 说明里出现元指令「%s」", who, banned).doesNotContain(banned);
            }
        }
    }

    /** 扫 target/classes 下全部类，取带 {@link Tool} 注解的方法（类不初始化，加载不了就跳过）。 */
    private static List<Method> toolMethods() throws Exception {
        Path classes = Path.of("target", "classes");
        assertThat(Files.isDirectory(classes)).as("先编译再跑：%s 不存在", classes).isTrue();

        List<Method> out = new ArrayList<>();
        ClassLoader cl = ToolCopyDisciplineTest.class.getClassLoader();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String rel = classes.relativize(f).toString();
                String name = rel.substring(0, rel.length() - ".class".length())
                        .replace('/', '.').replace('\\', '.');
                Class<?> c;
                try {
                    c = Class.forName(name, false, cl);
                } catch (Throwable ignored) {
                    continue;   // 桥接类/过期类：与工具说明无关
                }
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.isAnnotationPresent(Tool.class)) out.add(m);
                    }
                } catch (Throwable ignored) {
                    // 注解解析依赖的类缺失时跳过这个类，不让一条脏类挡住整张工具表的护栏
                }
            }
        }
        return out;
    }
}
