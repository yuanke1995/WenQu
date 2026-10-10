package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.service.PresentArtifactTool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 产物交付族（模型把回答整理成可下载文件）。
 * <p>
 * sideEffect 记为 MUTATE 但 requiresApproval=false：它写的是平台内的产物库、归属当前用户，
 * 不动用户外部世界，迁移前也从未进过「有副作用工具」名单（那里只有沙盒与 MCP）。
 * 这两个字段分开记是有意的——将来 P2 按档位收紧时，「变更类工具」该不该被只读档禁掉，
 * 需要能看见性质而不只看一个布尔。
 */
@Component
public class ArtifactToolProvider implements ToolFamilyProvider {

    private final PresentArtifactTool tool;

    public ArtifactToolProvider(PresentArtifactTool tool) {
        this.tool = tool;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.ARTIFACT;
    }

    @Override
    public Object holder() {
        return tool;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor("presentArtifact", ToolFamily.ARTIFACT,
                "生成文件产物", "生成文件", "把内容整理成可下载的文件",
                SideEffect.MUTATE, "tool.artifact.enabled", false, false, null));
    }
}
