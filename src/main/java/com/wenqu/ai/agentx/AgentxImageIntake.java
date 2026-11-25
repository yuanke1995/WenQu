package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.UserImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 本轮用户图片的落盘与「原图要不要进模型」的分叉（从 AgentscopeChatEngine 抽出）。
 *
 * <p>两条链路的分叉点只有一个——聊天模型能不能读图（与发布游客模型时同一个判据）：
 * 能读走 processDirect（落盘 + 保留原图 dataURL 供本轮直发，不丢细节）；不能读走 processDisplay
 * （只落盘回显，dataURL 不占内存）。无论哪条，落库的一律是 URL：data URL 永远不进库。
 *
 * <p>模型读不了图时必须说出来：登记降级（受 chat.retrievalDebugEnabled 控制）并把说明拼进本轮问题，
 * 模型据此回答「我看不到图，别猜」，而不是对着看不见的图硬编一段。只在真有图片被排除时登记——
 * 本轮没传图就无所谓模型支不支持视觉。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxImageIntake {

    private final ModelRegistryService modelRegistry;
    private final UserImageService userImages;

    /** 一次图片处理的产物。 */
    public record Intake(boolean chatSeesImages, List<UserImageService.UserImage> images,
                         List<String> urls, String imgNote) {
    }

    public Intake process(ChatCommand cmd, String ref, WenquSourcePool pool) {
        boolean chatSeesImages = modelRegistry.visionCapableOf(ref);
        List<String> rawImages = cmd.getUserImages();
        List<UserImageService.UserImage> userImgs = (rawImages == null || rawImages.isEmpty())
                ? List.of()
                : (chatSeesImages ? userImages.processDirect(rawImages) : userImages.processDisplay(rawImages));
        List<String> userImgUrls = userImgs.stream().map(UserImageService.UserImage::url).toList();
        String imgNote = noteOf(cmd, ref, userImgs, chatSeesImages, pool);
        return new Intake(chatSeesImages, userImgs, userImgUrls, imgNote);
    }

    /** 模型读不了图时的说明与降级登记；能读或没传图则返回空串。 */
    private String noteOf(ChatCommand cmd, String ref, List<UserImageService.UserImage> userImgs,
                          boolean chatSeesImages, WenquSourcePool pool) {
        if (userImgs.isEmpty()) return "";
        if (chatSeesImages) {
            log.info("[IMAGE] 聊天模型自带图片理解，图片直发模型: session={} model={} count={}",
                    cmd.getSessionId(), ref, userImgs.size());
            return "";
        }
        pool.registrar().degrade("chatModelNoVision",
                "当前模型「" + ref.substring(ref.indexOf('/') + 1)
                        + "」不支持图片理解，本轮 " + userImgs.size()
                        + " 张图片仅作展示、未参与回答；如需识别图片请更换支持图片理解的模型");
        log.warn("[FAIL-LOUD] 聊天模型不支持图片理解，图片仅展示: session={} model={} count={}",
                cmd.getSessionId(), ref, userImgs.size());
        return "\n\n用户上传了 " + userImgs.size()
                + " 张图片，但当前模型不支持图片理解，图片内容对你不可见。"
                + "请勿猜测图片内容；若图片对回答关键，可建议用户更换支持图片理解的模型后重试。";
    }
}
