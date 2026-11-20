package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.OfficeTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档处理族：officeConvert / officeRecalculate / officeRender。
 * <p>刻意<b>不新增平台开关</b>：三把都只在工作区/沙盒内读写文件，收口靠现成的三层——
 * {@code tool.enabled} 总闸、权限档位（{@link SideEffect#MUTATE} 在只读档不进工具表）、
 * 以及平台/智能体的按名停用名单。审批「禁用」档整族不挂（族上已标）。
 *
 * @author yuanke
 */
@Component
public class OfficeToolProvider implements ToolFamilyProvider {

    private final OfficeTools tools;

    public OfficeToolProvider(OfficeTools tools) {
        this.tools = tools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.OFFICE;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("officeConvert", ToolFamily.OFFICE,
                        "文档格式转换", "转换文档格式",
                        "把 docx/xlsx/pptx/pdf 转成 txt/csv/html/pdf",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("officeRecalculate", ToolFamily.OFFICE,
                        "Excel 公式重算", "重算表格公式",
                        "重算 xlsx 里的公式并把结果写回",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("officeRender", ToolFamily.OFFICE,
                        "文档渲染预览", "渲染文档页面",
                        "把文档渲染成 PDF 或 PNG 图片给用户看版面",
                        SideEffect.MUTATE, null, false, false));
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn()) return List.of();
        if (!mc.approvalPolicyAllows(family())) return List.of();   // 审批「禁用」档整族不挂
        return mountFiltered(mc, d -> true);
    }
}
