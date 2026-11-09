package com.wenqu.ai.agent.tool;

/**
 * 工具的副作用等级（权限档位的分类基础，目前只做标注，不参与裁剪）。
 * <p>
 * 为什么现在就要把它写进描述符：审批、沙盒档位、成本归属这三件事都要按「这次调用会不会动到
 * 外部世界」来决定，而当前代码里只有「sensitive 与否」一个布尔，读文件与执行命令被塞进同一个集合
 * （沙盒族的 read_file/ls 现在也要点审批）。等级先如实记下来，收紧档位时才有依据，
 * 也才不至于为了分档再去猜每个工具的性质。
 * <p>
 * 注意与 {@link ToolDescriptor#requiresApproval()} 的区别：等级是**客观性质**，
 * 要不要审批是**当前策略**。目前保持现有策略不变（见每个描述符上的实际取值），
 * 只做「性质可查」这一步。
 *
 * @author yuanke
 */
public enum SideEffect {

    /** 纯计算：不读任何外部状态、不写任何东西（计算器、日期推算、哈希） */
    PURE,
    /** 只读：读平台内数据或沙盒内文件，结果进上下文但不留痕（searchKnowledge、read_file） */
    READ,
    /** 出网：把请求发到平台之外并可能产生外部计费（webSearch、MCP 工具） */
    EXTERNAL,
    /** 变更：在用户环境里留下改变或产出物（execute、write_file、deliver_artifact、presentArtifact） */
    MUTATE
}
