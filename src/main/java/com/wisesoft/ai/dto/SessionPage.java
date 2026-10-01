package com.wisesoft.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 会话列表分页结果（游标式）
 *
 * @author yuanke
 */
@Data
@Schema(description = "会话分页结果")
public class SessionPage {

    @Schema(description = "本页会话（首页含全部置顶会话在前，其余按更新时间倒序；游标页仅非置顶增量）")
    private List<SessionInfo> items;

    @Schema(description = "下一页游标（null=没有更多；取下一页时原样回传）")
    private String nextCursor;

    @Schema(description = "是否还有更多")
    private Boolean hasMore;

    @Schema(description = "各分组总数（键：pinned/today/week/earlier，与前端侧栏分组一一对应；仅统计有消息的会话），供组头展示全量数字")
    private Map<String, Long> groupCounts;

    @Schema(description = "会话总数（仅统计有消息的会话）")
    private Long total;
}
