package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.executor.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 本机浏览器调用：把裸 {@code browser_*} 调用经「本机执行器」下发到用户电脑上的浏览器。
 *
 * <p>工具面已经收口到 {@link BrowserTools} 那一套统一 {@code browser_*} 工具；本类现在只是
 * 「本机执行位置」的传输层：按 uid 找在线执行器，发 {@code browser} op。headless 与否由
 * 执行器按个人配置（{@code browser.headless}）自己决定，不在这里传参。
 *
 * <p>仅当有在线执行器时可用（由 {@link BrowserModeResolver} 判定挂不挂）；身份经
 * {@link ToolContext} 透传（跨线程读不到 RequestUser）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class LocalBrowserTools {

    /** 单次浏览器调用等待上限：导航/渲染可能慢，给足；执行器侧另有 deadline 兜底 */
    private static final long CALL_TIMEOUT_MS = 120_000;

    private final ExecutorService executorService;
    private final ExecutorRegistry executorRegistry;

    public LocalBrowserTools(ExecutorService executorService, ExecutorRegistry executorRegistry) {
        this.executorService = executorService;
        this.executorRegistry = executorRegistry;
    }

    /** 该用户当前在线的执行器 id（没有= null），供 BrowserModeResolver 判定用。 */
    public String onlineExecutor(String uid) {
        if (uid == null || uid.isBlank()) return null;
        for (Map<String, Object> row : executorService.list(uid)) {
            String id = String.valueOf(row.get("id"));
            if (executorRegistry.online(id)) return id;
        }
        return null;
    }

    /** 经本机执行器执行一条裸 browser_* 工具，返回工具正文。 */
    public String call(ToolContext ctx, String tool, Map<String, Object> args, boolean headless) {
        Object uidV = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_USER_ID);
        String uid = uidV == null ? null : String.valueOf(uidV);
        String executorId = onlineExecutor(uid);
        if (executorId == null) {
            return "本机执行器不在线：请在那台电脑上启动执行器（npm run dev 或 wenqu-agent）后再试。";
        }
        ExecutorRegistry.Result r = executorRegistry.call(executorId, "browser",
                Map.of("tool", tool, "args", args == null ? Map.of() : args, "headless", headless),
                CALL_TIMEOUT_MS);
        if (!r.ok()) return "本机浏览器执行失败[" + r.code() + "]：" + r.message();
        Object text = r.data() == null ? null : r.data().get("text");
        return text == null ? "" : String.valueOf(text);
    }

    /** 尽力而为地收掉该用户的本机浏览器：没开、执行器离线、上游不认这一调用都当成功，不抛。 */
    public void closeQuietly(String uid) {
        if (uid == null || uid.isBlank()) return;
        String executorId = onlineExecutor(uid);
        if (executorId == null) return;
        try {
            executorRegistry.call(executorId, "browser",
                    Map.of("tool", "browser_close", "args", Map.of()), 15_000L);
        } catch (RuntimeException e) {
            log.debug("[LOCAL_BROWSER] 收尾关闭浏览器失败（忽略） uid={}: {}", uid, e.toString());
        }
    }
}
