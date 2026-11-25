package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 一轮里产生了哪些文件改动、每个文件改了什么。
 * <p>从 ChatRunController 拆出：它读的是「这一轮跑完留下了什么」（按需现算差异），
 * 与「这一轮怎么开、怎么停、怎么插话」是两件事，放在一起会让两个方向各背一半依赖。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "本轮文件改动", description = "逐文件改动差异按需取")
public class ChatTurnChangesController {

    /** 每轮文件改动：逐文件差异按需取（内容由本机执行器现算，服务端不留缓存） */
    private final com.wenqu.ai.service.ProjectTurnChangesService turnChanges;

    @Operation(summary = "取一轮里某个文件的改动差异",
            description = "按消息与文件下标取该文件的 unified diff；内容由本机执行器现算，服务端不留缓存。"
                    + "仅归属人可读。")
    @GetMapping("/message/{messageId}/changes/{index}/diff")
    public ResultJson turnChangesDiff(
            @Parameter(description = "消息 ID") @PathVariable("messageId") String messageId,
            @Parameter(description = "该轮文件改动的下标") @PathVariable("index") int index) {
        return ResultJson.ok(turnChanges.fileDiff(RequestUser.uid(), messageId, index));
    }
}
