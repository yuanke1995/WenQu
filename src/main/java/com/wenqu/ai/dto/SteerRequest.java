package com.wenqu.ai.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 运行中插话的请求体：一句话 + 可选图片（与正常提问同一套 data URL 口径）。 */
@Getter
@Setter
public class SteerRequest {

    private String sessionId;
    private String text;
    /** 图片 data URL（data:image/…）；与正常提问同一套数量/体积校验。 */
    private List<String> images;
}
