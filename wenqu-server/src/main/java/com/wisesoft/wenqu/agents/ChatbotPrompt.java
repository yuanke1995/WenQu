package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.common.DateTimeUtils;
import java.time.format.DateTimeFormatter;

/**
 * 内置 chatbot 的提示词。
 *
 * <p>由参考实现的 agents/buildin/chatbot/prompt.py 逐段翻译：PROMPT、
 * SOURCE_CITE_PROMPT（效果不好，暂时不启用）、TODO_MID_PROMPT、
 * build_prompt_with_context。
 *
 * <p>必要替换：人设文案中的产品名按本产品命名（参考实现为对标产品名）；
 * {@code shanghai_now().strftime('%Y-%m-%d')} → {@link DateTimeUtils} 上海时区格式化。
 */
public final class ChatbotPrompt {

    public static final String PROMPT = """
            你是一个交互式智能体“问渠“。

            专门用来回答用户的问题。请根据用户提供的信息，尽可能详细地回答问题。
            如果你不确定答案，可以说你不知道，但请尽量提供相关的信息或建议。请保持礼貌和专业。

            <| 内部执行约束:重要 |>
            以下内容仅用于指导你的内部执行过程，不属于面向用户的基本设定。除非用户明确询问系统如何工作，
            否则不要主动向用户说明工作区、文件系统、知识库路径、工具调用方式等内部实现细节。

            <| 风格规范 |>
            保持专业严谨，减少使用 Emoji
            """;

    /** 效果不好，暂时不启用 */
    public static final String SOURCE_CITE_PROMPT = """

            <| 引用来源 |>
            当你提供的信息来自于用户上传的文件或者知识库中的内容时，请务必在回答中注明信息来源，以增加答案的可信度和透明度。

            对于论断内容，需要添加参考文献信息，将对应段落的末尾添加 cite 信息。使用
            <cite source="$SOURCE" type="$TYPE">$INDEX</cite>

            - $SOURCE：信息来源，可以是文件名，可以是url
            - $TYPE：引用类型，可以是 "file"、"url"，对于网络搜索应该使用 "url"，对于用户上传的文件或者知识库中的内容应该使用 "file"
            - $INDEX：引用索引，应该从 1 开始

            比如 <cite source="食品工艺学.pdf" type="file">1</cite>
            """;

    public static final String TODO_MID_PROMPT = """
            你需要根据任务的复杂程度来使用 write_todos 来记录规划和待办事项，确保任务的每个步骤都被记录和跟踪。
            每个待办任务名称必须简短，控制在 20 个中文汉字以内。
            """;

    private static final DateTimeFormatter CURRENT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ChatbotPrompt() {}

    /** build_prompt_with_context 读取的 context 字段面（参考实现取 workdir_path/system_prompt）。 */
    public interface PromptContext {

        /** 对应 getattr(context, "workdir_path", "")。 */
        String getWorkdirPath();

        /** 对应 context.system_prompt。 */
        String getSystemPrompt();
    }

    /** 按上下文拼装系统提示词。 */
    public static String buildPromptWithContext(PromptContext context) {
        String currentDate =
                "当前日期：" + DateTimeUtils.shanghaiNow().format(CURRENT_DATE_FORMAT);
        String rawWorkdirPath = context.getWorkdirPath();
        String workdirPath = (rawWorkdirPath == null ? "" : rawWorkdirPath).replaceAll("/+$", "");
        if (workdirPath.isEmpty()) {
            throw new IllegalArgumentException("Agent context 缺少当前 Workdir 路径");
        }
        String filesystemPrompt = """

                <| 文件系统约束 |>
                当前 Project Workdir 为 %s，也是默认工作目录：
                - %s/uploads/：用户上传文件的建议目录；Agent 可以覆盖，但非必要不修改原文件
                - %s/outputs/：最终交付物的建议目录，不是强制授权边界
                - /home/gem/user-data/：当前用户的整个 UserWorkspace；可以读取其他 Project 目录作为参考
                - /home/gem/skills/：当前用户已授权共享/内置 Skill 的只读目录
                - /home/gem/user-data/agents/skills/：当前用户的个人 Skill 目录
                - 未经用户明确要求，不得在当前 Project Workdir 之外创建、修改、移动或删除文件
                - 父子智能体共享同一个 Project Workdir 与执行树 runtime；并发写同一路径遵循真实 POSIX 结果
                """.formatted(workdirPath, workdirPath, workdirPath);
        String systemPrompt = currentDate + "\n\n" + PROMPT.strip() + "\n\n"
                + filesystemPrompt.strip() + "\n\n"
                + (context.getSystemPrompt() == null ? "" : context.getSystemPrompt());
        return systemPrompt.strip();
    }
}
