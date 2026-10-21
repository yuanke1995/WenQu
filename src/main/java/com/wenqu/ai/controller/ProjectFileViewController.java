package com.wenqu.ai.controller;

import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.ProjectFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 工作区文件的<b>预览地址</b>（浏览器顶层导航用，没有登录态）。
 *
 * <p>刻意挂在 {@code /project-files/**} 而不是 {@code /api/**}：{@code UserContextInterceptor} 与
 * {@code AccessControlInterceptor} 只挂在 {@code /api/**} 上，而「开一个新标签页」那一跳本来就加不上
 * {@code Authorization} 头。鉴权改由地址自己携带——{@link ImageUrlSigner} 的 HMAC 签名，
 * 基准是整段请求 URI（含 token 与 expire），与 {@code /ai/artifacts/**} 同一套做法。
 *
 * <p><b>与产物那条路的两点刻意差别</b>：
 * <ol>
 *   <li>这里<b>不看</b> {@code images.authEnabled} 开关，没有有效签名一律 401。工作区目录里躺着的是
 *       沙盒的全量用户数据，不能因为一个为图片设的开关关掉就变成可按路径试探的公开读文件口子；</li>
 *   <li>token 装的是「工作区 id + 相对路径」而不是磁盘布局，解析仍走 {@link ProjectFileService}
 *       那一份（目录结构换了，签发与读取两边一起换）。</li>
 * </ol>
 *
 * <p>响应的 {@code Content-Security-Policy: sandbox} 与 {@code X-Content-Type-Options} 由
 * {@code ArtifactSandboxInterceptor} 打（挂载路径见 {@code ImageWebConfig}）：HTML 页面真的会渲染出来，
 * 但脚本落进不透明源，碰不到本域的登录令牌与存储。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/project-files")
@RequiredArgsConstructor
@Tag(name = "工作区文件预览", description = "签名地址：把工作区目录里的一个文件开成浏览器标签页")
public class ProjectFileViewController {

    private final ProjectFileService projectFileService;
    private final ImageUrlSigner imageUrlSigner;

    @Operation(summary = "按签名 token 打开工作区文件",
            description = "地址必须带 ?expire=&sig=（由 /api/ai/project/{id}/files 随列表签发，默认 1 小时有效）。"
                    + "文本按 text/plain、图片按原类型、html 按 text/html 内联返回；签名无效或过期 401")
    @GetMapping("/{token}")
    public ResponseEntity<byte[]> view(@PathVariable("token") String token,
                                       HttpServletRequest request,
                                       @RequestParam(value = "expire", required = false) String expireStr,
                                       @RequestParam(value = "sig", required = false) String sig) {
        long expire = expireStr != null && expireStr.matches("\\d+") ? Long.parseLong(expireStr) : 0L;
        // token 是 base64url（纯 ASCII），浏览器不会再对它做百分号编码，所以不像中文文件名那条路
        // 需要「编码/不编码各验一遍」——这里原始 URI 与签发基准必然逐字节相同。
        if (!imageUrlSigner.verify(request.getRequestURI(), expire, sig)) {
            return text(401, "预览链接无效或已过期");
        }
        try {
            ProjectFileService.ViewFile f = projectFileService.openView(token);
            String encoded = URLEncoder.encode(f.name(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header("Content-Type", f.contentType())
                    .header("Content-Disposition", "inline; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                    .header("Cache-Control", "no-store")
                    .body(f.bytes());
        } catch (IllegalArgumentException e) {
            return text(400, e.getMessage());
        } catch (IllegalStateException e) {
            return text(503, e.getMessage());
        }
    }

    /** 出错时回一句人话：这一页是给用户看的（新标签页里），吐 JSON 或留白页都读不懂 */
    private static ResponseEntity<byte[]> text(int status, String msg) {
        String body = msg == null || msg.isBlank() ? "预览失败" : msg;
        return ResponseEntity.status(status)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("Cache-Control", "no-store")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
