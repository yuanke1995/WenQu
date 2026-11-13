package com.wenqu.ai.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 运行中插话的请求体：一句话 + 可选图片/附件（与正常提问同一套校验与注入口径）。 */
@Getter
@Setter
public class SteerRequest {

    private String sessionId;
    private String text;
    /** 图片 data URL（data:image/…）；与正常提问同一套数量/体积校验。 */
    private List<String> images;
    /**
     * 文档附件：先经 /chat/attachment 上传换 fileId，这里只带引用（与正常提问同一形状）。
     * <p>插话的上下文是本轮请求构建期定型的，但附件正文不是检索结果——按 fileId 现读现抽，
     * 在下一次模型调用前随这句话一起写进上下文，所以附件能赶上正在跑的这一轮。
     */
    private List<ChatRequest.Attachment> attachments;
}
