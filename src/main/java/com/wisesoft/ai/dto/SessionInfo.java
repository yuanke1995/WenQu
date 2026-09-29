package com.wisesoft.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项 DTO
 *
 * @author yuanke
 */
@Data
@Schema(description = "会话信息")
public class SessionInfo {

    @Schema(description = "会话 ID")
    private String id;
    @Schema(description = "会话标题")
    private String title;
    @Schema(description = "消息数量")
    private Integer messageCount;
    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "是否置顶: 0=否,1=是")
    private Integer isPinned;
    @Schema(description = "是否收藏: 0=否,1=是")
    private Integer isFavorite;
    @Schema(description = "会话级绑定的智能体ID（未绑定不发字段；空串=已决定不绑定智能体）")
    private String agentId;
    @Schema(description = "绑定时的智能体名称快照")
    private String agentName;
}
