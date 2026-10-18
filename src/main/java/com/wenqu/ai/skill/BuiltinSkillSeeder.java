package com.wenqu.ai.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 内置技能落盘：启动时把 {@code classpath:skills/**} 物化到受管目录，供
 * {@link WenquSkillRepository} 按目录读取。
 * <p><b>为什么必须物化而不是直接读 classpath</b>：框架的
 * {@code FileSystemSkillRepository} 只认磁盘目录，而技能正文随 jar 分发——容器里
 * 工作目录是 {@code /app} 且只有 {@code app.jar}，按源码树路径去找
 * {@code src/main/resources/skills} 必然落空，表现为<b>内置技能一条都不注入且不报错</b>。
 * 本机从仓库根启动时那个路径恰好存在，所以这个坑只在部署形态暴露。
 * <p>幂等：逐文件比字节，内容相同不动、不同才覆盖（随版本分发的东西必须跟着版本走）。
 * 失败只告警不阻塞启动——技能缺失影响的是作答质量，不是可用性。
 */
@Slf4j
@Component
// 排在 SchemaMigrator(@Order(0)) 之后：本类不依赖表结构，但启动任务的阅读顺序应保持「先schema后种子」。
@Order(5)
public class BuiltinSkillSeeder implements ApplicationRunner {

    /** classpath 里的内置技能根（与 {@code src/main/resources/skills} 对应）。 */
    private static final String CLASSPATH_ROOT = "skills";

    private final String configuredBuiltinDir;

    public BuiltinSkillSeeder(
            @org.springframework.beans.factory.annotation.Value("${wenqu.skills.builtin-dir:}")
            String configuredBuiltinDir) {
        this.configuredBuiltinDir = configuredBuiltinDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path target = WenquSkillRepository.resolveBuiltinRoot(configuredBuiltinDir);
        try {
            int written = syncTo(target);
            if (written > 0) {
                log.info("[SKILL] 内置技能已落盘 dir={} 新增/更新 {} 个文件", target, written);
            }
        } catch (Exception e) {
            // 不静默：内置技能为空会被读成「技能没装」，而真实原因是落盘失败
            log.warn("[FAIL-LOUD] 内置技能落盘失败，本轮起内置技能不可用 dir={}: {}", target, e.toString());
        }
    }

    /** 把 classpath 下的每个技能文件按相对路径写入目标目录，返回实际写入的文件数。 */
    private int syncTo(Path target) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + CLASSPATH_ROOT + "/**");
        int written = 0;
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            String url = r.getURL().toString();
            // 目录条目（file: 与 jar: 都以斜杠结尾）没有内容可写
            if (url.endsWith("/")) continue;
            String relative = relativeOf(url);
            if (relative == null || relative.isBlank()) continue;
            Path out = target.resolve(relative);
            // 目录穿越防线：技能名来自打包内容，但仍不该有东西能写到目标目录之外
            if (!out.normalize().startsWith(target.normalize())) {
                log.warn("[SKILL] 跳过越界内置技能条目 {}", relative);
                continue;
            }
            byte[] content;
            try (InputStream in = r.getInputStream()) {
                content = in.readAllBytes();
            }
            if (Files.exists(out) && Files.size(out) == content.length
                    && java.util.Arrays.equals(Files.readAllBytes(out), content)) {
                continue;
            }
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp)) {
                os.write(content);
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            written++;
        }
        if (written == 0 && !Files.isDirectory(target)) {
            log.warn("[FAIL-LOUD] classpath 里没有任何内置技能（打包缺 skills/ 目录？）target={}", target);
        }
        return written;
    }

    /** 从资源 URL 里截出 {@code skills/} 之后的相对路径；截不到说明它不属于技能根。 */
    private static String relativeOf(String url) {
        int idx = url.indexOf('/' + CLASSPATH_ROOT + '/');
        if (idx < 0) return null;
        return url.substring(idx + CLASSPATH_ROOT.length() + 2);
    }
}
