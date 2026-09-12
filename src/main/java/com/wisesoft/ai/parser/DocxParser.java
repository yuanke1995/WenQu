package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.VisionService;
import com.wisesoft.ai.util.ImageCompressor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word(docx) 解析器
 * 段落/表格/标题层级分块 + 图片提取（压缩保存 + 视觉并发描述）
 * 从原 DocumentService 迁移，保持行为一致
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DocxParser implements DocumentParser {

    /** 允许提取的图片类型（浏览器与视觉模型可识别），EMF/WMF/PICT 等矢量图跳过 */
    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final AiAppProperties properties;
    private final VisionService visionService;
    private final ConfigService configService;
    private final ExecutorService visionExecutor;
    /** 图片描述并发限流（容量随配置动态调整，保存即生效） */
    private volatile Semaphore visionSemaphore;
    /** 上次生效的图片描述并发数（判断配置是否变化；不能用 availablePermits——运行期许可被占会误判触发重建） */
    private volatile int visionConcurrency;

    /** 单次解析统计（fail-loud：截断/跳过/失败计数，供解析终态 desc 展示；docId -> [图片截断, 类型跳过, 落盘失败]） */
    private final ConcurrentMap<String, int[]> parseStats = new ConcurrentHashMap<>();

    /** 解析统计通道：计数数组 [0]=maxImages截断张数 [1]=类型跳过张数 [2]=落盘失败张数 */
    private int[] statsFor(String docId) {
        return parseStats.computeIfAbsent(docId, k -> new int[3]);
    }

    /** 解析结束后取统计（无则返回空统计），调用方展示完应 clearStats */
    public Map<String, Integer> statsOf(String docId) {
        int[] s = parseStats.get(docId);
        if (s == null) return Map.of("truncated", 0, "typeSkipped", 0, "persistFailed", 0);
        return Map.of("truncated", s[0], "typeSkipped", s[1], "persistFailed", s[2]);
    }

    /** 清理解析统计（解析结束后调用，防 docId 泄漏） */
    public void clearStats(String docId) {
        parseStats.remove(docId);
    }


    public DocxParser(AiAppProperties properties, VisionService visionService, ConfigService configService) {
        this.properties = properties;
        this.visionService = visionService;
        this.configService = configService;
        int initC = Math.max(1, properties.getVision().getConcurrency());
        this.visionConcurrency = initC;
        this.visionSemaphore = new Semaphore(initC);
        // 有界线程池（替代 cached：图片多时 cached 会为每张图建阻塞线程，线程数随图片数膨胀）
        // CallerRunsPolicy：队列满时由提交线程（doc-parse）直接执行 → 天然背压，任务不丢、不降级无描述；
        // 并发仍由信号量限流（c 个许可），不会无界堆积内存
        this.visionExecutor = new ThreadPoolExecutor(initC, initC, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(4, initC * 2)), r -> {
            Thread t = new Thread(r, "vision-desc");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 动态并发信号量：配置变更时重建（保存即生效）；以配置值判断而非 availablePermits，避免运行期误判 */
    private Semaphore visionSemaphore() {
        int want = Math.max(1, configService.getInt("vision.concurrency"));
        if (want != visionConcurrency) {
            synchronized (this) {
                if (want != visionConcurrency) {
                    log.info("[DocxParser] 图片描述并发调整: {} -> {}", visionConcurrency, want);
                    visionSemaphore = new Semaphore(want);
                    visionConcurrency = want;
                }
            }
        }
        return visionSemaphore;
    }

    @PreDestroy
    public void shutdown() {
        visionExecutor.shutdown();
    }

    @Override
    public boolean supports(String ext) {
        return "docx".equalsIgnoreCase(ext);
    }

    @Override
    public java.util.Set<String> supportedExts() {
        return java.util.Set.of("docx");
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws IOException {
        return parse(file, fileName, docId, null);
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId, ParseProgress progress) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = properties.getChunk().getMaxSize();
        // 结构感知切分（chunk.structural 可配，默认开）：标题栈 → 章节路径；边界优先阈值 = maxSize × ratio
        boolean structural = configService.getBoolean("chunk.structural");
        double ratio = Math.min(1.0, Math.max(0.5, configService.getDouble("chunk.structuralRatio", 0.8)));
        int boundaryThreshold = (int) (maxSize * ratio);
        String title = "概述";
        StringBuilder content = new StringBuilder();
        // 标题栈（结构感知：章节路径注入用；Deque 末尾为当前章节）
        ArrayDeque<String> titleStack = new ArrayDeque<>();
        String titlePath = "";
        // 当前分块待处理图片（描述异步并发生成，flush 时统一 join）
        List<SavedImage> currentImages = new ArrayList<>();
        // checksum -> 已保存图片（文档内去重，复用 URL 与描述）
        Map<Long, SavedImage> imageCache = new HashMap<>();
        int[] imageCount = {0};
        // 图片识别进度：预扫描总数，每张描述完成上报（10→30 区间由调用方映射）
        ImageProgress imageProgress = null;
        if (progress != null) {
            int total = 0;
            try (XWPFDocument scan = new XWPFDocument(java.nio.file.Files.newInputStream(file))) {
                total = countImages(scan);
            } catch (Exception e) {
                log.warn("图片预扫描失败，跳过图片进度上报: {}", e.getMessage());
            }
            imageProgress = new ImageProgress(total, progress);
        }

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(file))) {
            // 样式表 → 标题层级映射（WPS/Word 数字样式 ID 的标题正确识别 + 目录样式跳过）
            Map<String, Integer> styleLevels = buildStyleLevels(file);
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText().trim();

                    int level = headingLevel(p, styleLevels);
                    if (level < 0) continue; // 目录段落（toc 样式，含页码）：跳过不进知识块
                    if (level > 0 && level <= 3) {
                        // 遇标题 flush 当前块（结构模式：按层级维护标题栈生成章节路径）
                        flushChunk(title, content, currentImages, chunks, titlePath);
                        if (structural) {
                            while (!titleStack.isEmpty() && titleStack.size() >= level) titleStack.pollLast();
                            titleStack.addLast(text);
                            title = text;
                            titlePath = titleStack.size() >= 2 ? String.join(" > ", titleStack) : "";
                        } else {
                            title = text;
                        }
                        continue;
                    }

                    // 正文文本（主 run 文本 + 文本框旁注；文本框内容 WPS/Word 常见，POI getText 读不到）
                    if (!text.isEmpty()) {
                        if (content.length() > 0) content.append("\n");
                        content.append(text);
                    }
                    String textboxExtra = textboxExtraText(p);
                    if (!textboxExtra.isEmpty()) {
                        if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
                            content.append("\n");
                        }
                        content.append(textboxExtra);
                    }

                    // 段落内嵌图片（图片独立成段时 text 为空，也要进块）
                    // 占位符 [图片] 立即写入原文位置，flush 时按序替换为 [图片：描述]
                    for (XWPFRun run : p.getRuns()) {
                        for (XWPFPicture pic : run.getEmbeddedPictures()) {
                            handlePicture(pic.getPictureData(), docId, currentImages, imageCache, imageCount, imageProgress);
                            if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
                                content.append("\n");
                            }
                            content.append("[图片]");
                        }
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    // 表格：解析为 Markdown 表格（保留行/列结构，LLM 可理解、前端可渲染）
                    XWPFTable table = (XWPFTable) element;
                    StringBuilder tableText = buildMarkdownTable(table);
                    if (tableText.length() > 0) {
                        if (structural) {
                            // 结构模式：表格独立成块（不与其他段落混杂，保持结构语义）；超长表格按行拆（重复表头/重开围栏，保持语法有效）
                            flushChunk(title, content, currentImages, chunks, titlePath);
                            addTableChunks(title, tableText.toString(), titlePath, chunks, maxSize);
                        } else {
                            if (content.length() > 0) content.append("\n");
                            content.append(tableText);
                        }
                    }
                }

                // 超长 flush（图片描述按平均 40 字估算计入长度）
                int estimated = content.length() + currentImages.size() * 40;
                if (structural) {
                    // 结构模式：≥maxSize 硬切（按段落/句边界拆多块，单块 ≤maxSize）；[阈值,maxSize) 在段落边界断块
                    if (estimated >= maxSize) {
                        flushStructural(title, content, currentImages, chunks, titlePath, maxSize);
                    } else if (estimated >= boundaryThreshold) {
                        flushChunk(title, content, currentImages, chunks, titlePath);
                    }
                } else if (estimated > maxSize) {
                    flushChunk(title, content, currentImages, chunks, titlePath);
                }
            }
            flushChunk(title, content, currentImages, chunks, titlePath);
        }
        return chunks;
    }

    /**
     * 将 Word 表格解析为 Markdown：
     * - 单列表格（作者常用表格排版代码/命令/模板）→ 输出为代码块，保留原始换行
     * - 多列表格 → Markdown 表格（保留行/列结构），单元格内多段改用 &lt;br&gt; 保留换行
     * - 单元格内竖线转义（防止破坏表格语法）
     */
    private StringBuilder buildMarkdownTable(XWPFTable table) {
        List<List<String>> grid = new ArrayList<>();
        // 垂直合并（vMerge=continue）标记：POI 把续格的文本留空，导致该行丢失所属分类。
        // 例："数字组件"下连排的"货币模式/精确小数/大小限制"，续格的组件名是空的，
        // 切块后这些行变成无归属行（向量与关键词都召不回），必须按上一行同列回填。
        List<List<Boolean>> vMergeContinue = new ArrayList<>();
        int colCount = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            List<Boolean> conts = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                conts.add(isVMergeContinue(cell));
                // 逐段落提取，保留单元格内换行（getTextRecursively 会丢失段落分隔，导致多行命令粘连）
                StringBuilder ct = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    String t = p.getText().trim();
                    if (!t.isEmpty()) {
                        if (ct.length() > 0) ct.append('\n');
                        ct.append(t);
                    }
                    String textboxExtra = textboxExtraText(p);
                    if (!textboxExtra.isEmpty()) {
                        if (ct.length() > 0) ct.append('\n');
                        ct.append(textboxExtra);
                    }
                }
                cells.add(ct.toString());
            }
            colCount = Math.max(colCount, cells.size());
            grid.add(cells);
            vMergeContinue.add(conts);
        }
        if (grid.isEmpty()) return new StringBuilder();
        forwardFillMerged(grid, vMergeContinue, colCount);

        // 单列表格：极可能是"代码/命令/模板容器"，输出为代码块（LLM 识别为代码、前端渲染代码样式）
        if (colCount == 1) {
            StringBuilder code = new StringBuilder("```\n");
            for (List<String> row : grid) {
                for (String c : row) {
                    if (c.isEmpty()) continue;
                    code.append(c).append('\n');
                }
            }
            code.append("```");
            return code;
        }

        StringBuilder sb = new StringBuilder();
        // 补齐每行到统一列数（合并单元格/不规则表格），并输出
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            while (row.size() < colCount) row.add("");
            // 单元格内换行用 <br>（Markdown 表格标准写法），竖线转义防破坏语法
            List<String> escaped = new ArrayList<>();
            for (String c : row) {
                escaped.add(c.replace("|", "\\|").replace("\n", "<br>"));
            }
            // 先输出当前行，再在首行（表头）之后紧跟分隔行（Markdown 语法：| 表头 | → | --- | → | 数据 |）
            sb.append("| ").append(String.join(" | ", escaped)).append(" |\n");
            if (r == 0 && grid.size() > 1) {
                sb.append("| ").append(String.join(" | ", java.util.Collections.nCopies(colCount, "---"))).append(" |\n");
            }
        }
        return sb;
    }

    /**
     * 垂直合并单元格的前向回填：让每行都带上所属分类。
     * 典型场景——"数字组件"下连排的"货币模式/精确小数/大小限制"，续格的组件名在 POI 里是空串，
     * 切块后这些行变成无归属行，向量与关键词都召不回。
     * <p>优先按 vMerge=continue 精确判定；取不到该属性（poi-ooxml-lite 未打包 schema 类）时，
     * 退回列空值率启发式（空值率 20%~80% 视为存在合并单元格的列）。
     */
    private static void forwardFillMerged(List<List<String>> grid, List<List<Boolean>> flags, int colCount) {
        boolean vMergeAvailable = false;
        for (List<Boolean> row : flags) {
            for (Boolean b : row) {
                if (b != null) { vMergeAvailable = true; break; }
            }
            if (vMergeAvailable) break;
        }
        for (int c = 0; c < colCount; c++) {
            if (!vMergeAvailable && !looksMergedColumn(grid, c)) continue;
            String carried = null;
            for (int r = 0; r < grid.size(); r++) {
                List<String> row = grid.get(r);
                if (c >= row.size()) continue;
                String v = row.get(c);
                boolean isContinue = !vMergeAvailable
                        || (c < flags.get(r).size() && Boolean.TRUE.equals(flags.get(r).get(c)));
                if (isContinue && (v == null || v.isEmpty()) && carried != null) {
                    row.set(c, carried);
                } else if (v != null && !v.isEmpty()) {
                    carried = v;
                }
            }
        }
    }

    /** 列空值率启发式：介于 20%~80% 之间视为含合并单元格的分类列（过低=本无合并，过高=整列空） */
    private static boolean looksMergedColumn(List<List<String>> grid, int c) {
        int total = 0, empty = 0;
        for (List<String> row : grid) {
            if (c >= row.size()) continue;
            total++;
            String v = row.get(c);
            if (v == null || v.isEmpty()) empty++;
        }
        // 至少 4 行、且非空值 ≥2，避免小表格误判
        if (total < 4 || total - empty < 2) return false;
        double ratio = (double) empty / total;
        return ratio > 0.2 && ratio < 0.8;
    }

    /** vMerge=continue 判定；反射失败返回 null（表示不可判定，交给启发式） */
    private static Boolean isVMergeContinue(XWPFTableCell cell) {
        try {
            java.lang.reflect.Method m = XWPFTableCell.class.getMethod("getVerticalMerge");
            Object v = m.invoke(cell);
            return v == null ? Boolean.FALSE : Boolean.valueOf("CONTINUE".equals(String.valueOf(v)));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 处理单张图片：类型过滤 → 数量上限 → 去重 → 压缩保存 → 异步并发生成描述
     */
    private void handlePicture(XWPFPictureData data, String docId,
                               List<SavedImage> currentImages,
                               Map<Long, SavedImage> imageCache, int[] imageCount,
                               ImageProgress imageProgress) {
        String ext = data.suggestFileExtension().toLowerCase();
        if (!ALLOWED_EXTS.contains(ext)) {
            // fail-loud：跳过的图不静默（计数入终态 desc；EMF/WMF/PICT 矢量图不支持，属设计内但需可见）
            statsFor(docId)[1]++;
            log.debug("跳过不支持的图片类型: {}", ext);
            return;
        }

        // 数量上限截断（防图片爆炸导致视觉描述数小时；配置保存即生效）
        int maxImages = configService.getInt("chunk.maxImages");
        if (maxImages > 0 && imageCount[0] >= maxImages) {
            if (imageCount[0] == maxImages) {
                log.warn("[{}] 图片数量达到上限 {}，后续图片不再提取描述", docId, maxImages);
                imageCount[0]++;  // 只记一次 warn
            }
            statsFor(docId)[0]++;  // 截断计数（终态 desc 展示"图片截断N张"）
            return;
        }

        long checksum = data.getChecksum();
        SavedImage saved = imageCache.get(checksum);
        if (saved == null) {
            try {
                // 双图策略：压缩图只进内存供视觉模型识别（不落盘）；原图落盘用于回答/知识库展示（保持清晰）
                ImageCompressor.CompressedImage ci = ImageCompressor.compress(data.getData(), ext,
                        properties.getImages().getMaxWidth(), properties.getImages().getQuality());
                // 内容寻址文件名（sha256 原图字节）：同图跨重解析 URL 稳定 → 块 images 列表稳定 → 块级 diff 可复用；变更图生成新文件
                String url = persistImage(data.getData(), ext, docId, sha256Hex(data.getData()));
                // 并发限流（动态信号量，保存即生效）
                Semaphore sem = visionSemaphore();
                sem.acquire();
                CompletableFuture<String> descFuture;
                try {
                    descFuture = CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return visionService.describe(ci.bytes(), ci.ext());
                                } finally {
                                    sem.release();
                                }
                            }, visionExecutor);
                } catch (RejectedExecutionException e) {
                    // 兜底（正常不会触发：executor 用 CallerRunsPolicy 背压，队列满由提交线程自己执行）；
                    // 极端情况（如线程池已 shutdown）才走这里：释放许可并降级为无描述（图片仍落盘展示，不阻断解析）
                    sem.release();
                    log.warn("[{}] 图片描述任务被拒绝，该图降级为无描述", docId);
                    descFuture = CompletableFuture.completedFuture(null);
                }
                // 图片识别进度：每张描述完成（成功/失败均计数）上报一次
                if (imageProgress != null) {
                    final ImageProgress p = imageProgress;
                    descFuture.whenComplete((d, ex) -> {
                        p.done();
                        p.report();
                    });
                }
                saved = new SavedImage(url, descFuture);
                imageCache.put(checksum, saved);
                imageCount[0]++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("图片描述并发等待被中断: {}", e.getMessage());
                return;
            } catch (IOException e) {
                // fail-loud：图片落盘失败 = 该图连展示都没有（比无描述更严重），计数入终态 desc
                statsFor(docId)[2]++;
                log.warn("[{}] 图片保存失败: {}", docId, e.getMessage());
                return;
            }
        }
        currentImages.add(saved);
    }

    /**
     * 图片识别进度计数器（每张描述完成上报一次；跨线程安全）
     * 进度映射 10→30 区间，desc="识别图片 k/total"
     */
    private static final class ImageProgress {
        private final int total;
        private final ParseProgress callback;
        private final AtomicInteger done = new AtomicInteger(0);

        ImageProgress(int total, ParseProgress callback) {
            this.total = Math.max(1, total);
            this.callback = callback;
        }

        void done() { done.incrementAndGet(); }

        void report() {
            if (callback == null) return;
            int k = Math.min(done.get(), total);
            callback.onProgress(10 + 20 * k / total, "识别图片 " + k + "/" + total);
        }
    }

    /**
     * 预扫描文档段落内嵌图片总数（与 handlePicture 同构过滤：类型→去重→maxImages 截断），供进度上报
     */
    private int countImages(XWPFDocument document) {
        int total = 0;
        int maxImages = configService.getInt("chunk.maxImages");
        Set<Long> seen = new HashSet<>();
        outer:
        for (IBodyElement element : document.getBodyElements()) {
            if (element.getElementType() != BodyElementType.PARAGRAPH) continue;
            for (XWPFRun run : ((XWPFParagraph) element).getRuns()) {
                for (XWPFPicture pic : run.getEmbeddedPictures()) {
                    XWPFPictureData data = pic.getPictureData();
                    String ext = data.suggestFileExtension().toLowerCase();
                    if (!ALLOWED_EXTS.contains(ext)) continue;
                    if (!seen.add(data.getChecksum())) continue;
                    total++;
                    if (maxImages > 0 && total >= maxImages) break outer;
                }
            }
        }
        return total;
    }

    /**
     * 按图片 URL 补齐描述（解析后补描述回写知识块用）：
     * URL → 落盘原图 → 压缩（与解析时同规则）→ 视觉模型描述。
     * 图片缺失/压缩失败/视觉失败返回空串（调用方保留裸占位，下次再补）。
     */
    public String describeImageUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String prefix = properties.getImages().getUrlPrefix();
            String rel = url.startsWith(prefix) ? url.substring(prefix.length()) : url;
            Path p = Paths.get(properties.getImages().getDir(), "images", rel);
            if (!Files.exists(p)) {
                log.warn("[ImageDescBackfill] 图片文件不存在，跳过: {}", p);
                return "";
            }
            byte[] bytes = Files.readAllBytes(p);
            String ext = p.getFileName().toString().contains(".")
                    ? p.getFileName().toString().substring(p.getFileName().toString().lastIndexOf('.') + 1).toLowerCase()
                    : "png";
            ImageCompressor.CompressedImage ci = ImageCompressor.compress(bytes, ext,
                    properties.getImages().getMaxWidth(), properties.getImages().getQuality());
            return visionService.describe(ci.bytes(), ci.ext());
        } catch (Exception e) {
            log.warn("[ImageDescBackfill] 图片描述补齐失败 url={}: {}", url, e.getMessage());
            return "";
        }
    }

    /**
     * flush 当前分块：join 并发描述结果，并将 content 中 [图片] 占位按序替换为 [图片：描述]
     * （占位在解析时写入原文位置，因此图文天然交错）
     */
    private void flushChunk(String title, StringBuilder content, List<SavedImage> currentImages, List<Chunk> chunks, String titlePath) {
        if (content.length() == 0 && currentImages.isEmpty()) return;
        chunks.add(buildChunk(title, resolveImagePlaceholders(content.toString(), currentImages),
                imageUrls(currentImages), titlePath));
        content.setLength(0);
        currentImages.clear();
    }

    /**
     * 结构模式超长块硬切：内容 ≤maxSize 单块；超长按段落/句边界拆成多块（单块 ≤maxSize，预留路径前缀空间），
     * 图片 URL 按各段 [图片] 占位出现顺序切片对应
     */
    private void flushStructural(String title, StringBuilder content, List<SavedImage> currentImages,
                                 List<Chunk> chunks, String titlePath, int maxSize) {
        if (content.length() == 0 && currentImages.isEmpty()) return;
        String resolved = resolveImagePlaceholders(content.toString(), currentImages);
        if (resolved.length() <= maxSize) {
            chunks.add(new Chunk(title, resolved, imageUrls(currentImages), titlePath));
        } else {
            // 路径不入正文，切分预算无需再为前缀留空间
            int splitMax = Math.max(200, maxSize);
            List<String> urls = imageUrls(currentImages);
            int imgIdx = 0;
            for (String seg : splitByBoundaries(resolved, splitMax)) {
                List<String> segUrls = new ArrayList<>();
                for (int i = 0; i < countOccurrences(seg, "[图片") && imgIdx < urls.size(); i++) {
                    segUrls.add(urls.get(imgIdx++));
                }
                chunks.add(new Chunk(title, seg, segUrls, titlePath));
            }
        }
        content.setLength(0);
        currentImages.clear();
    }

    /** 结构模式表格入块：≤maxSize 单块；超长按行拆（代码块表格重开/闭合围栏，Markdown 表格重复表头） */
    private void addTableChunks(String title, String tableText, String titlePath, List<Chunk> chunks, int maxSize) {
        if (tableText.length() <= maxSize) {
            chunks.add(new Chunk(title, tableText, List.of(), titlePath));
            return;
        }
        for (String seg : splitTableRows(tableText, maxSize)) {
            chunks.add(new Chunk(title, seg, List.of(), titlePath));
        }
    }

    /**
     * 超长文本按边界硬切：段落（\n）为单元贪心打包；超长段落按句（。！？；）拆；
     * 单句仍超长按字符硬切（兜底）
     */
    private List<String> splitByBoundaries(String text, int max) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) continue;
            List<String> pieces = para.length() <= max ? List.of(para) : splitLongPara(para, max);
            for (String piece : pieces) {
                if (cur.length() > 0 && cur.length() + piece.length() > max) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append("\n");
                cur.append(piece);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result;
    }

    /** 超长段落按句拆（保留标点），单句仍超长按字符硬切 */
    private List<String> splitLongPara(String para, int max) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : splitSentences(para)) {
            if (s.length() > max) {
                if (cur.length() > 0) {
                    pieces.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < s.length(); i += max) {
                    pieces.add(s.substring(i, Math.min(s.length(), i + max)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + s.length() > max) {
                pieces.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(s);
        }
        if (cur.length() > 0) pieces.add(cur.toString());
        return pieces;
    }

    /** 按句末标点拆句（保留标点）；[图片...] 标记内的标点不拆；无标点返回整段 */
    private List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inMarker = false;
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            cur.append(c);
            if (c == '[' && !inMarker) inMarker = true;
            else if (c == ']' && inMarker) inMarker = false;
            if (!inMarker && (c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';')) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.isEmpty() ? List.of(para) : out;
    }

    /** 超长表格按行拆：代码块表格（``` 包裹）重开/闭合围栏；Markdown 表格每段重复表头+分隔行 */
    private List<String> splitTableRows(String tableText, int max) {
        String[] lines = tableText.split("\n", -1);
        if (tableText.startsWith("```")) {
            List<String> rows = new ArrayList<>();
            for (int i = 1; i < lines.length - 1; i++) rows.add(lines[i]);
            List<String> result = new ArrayList<>();
            StringBuilder cur = new StringBuilder("```\n");
            for (String r : rows) {
                if (cur.length() > 4 && cur.length() + r.length() + 4 > max) {
                    cur.append("```");
                    result.add(cur.toString());
                    cur = new StringBuilder("```\n");
                }
                if (cur.length() > 4) cur.append("\n");
                cur.append(r);
            }
            if (cur.length() > 4) {
                cur.append("```");
                result.add(cur.toString());
            }
            return result;
        }
        // Markdown 表格：表头行 + 分隔行作为每段的重复前缀（保持每段都是合法表格）
        if (lines.length < 2) return List.of(tableText);
        String header = lines[0] + "\n" + lines[1] + "\n";
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder(header);
        for (int i = 2; i < lines.length; i++) {
            String r = lines[i];
            if (r.isBlank()) continue;
            if (cur.length() > header.length() && cur.length() + r.length() + 1 > max) {
                result.add(cur.toString());
                cur = new StringBuilder(header);
            }
            cur.append(r).append("\n");
        }
        if (cur.length() > header.length()) result.add(cur.toString());
        return result;
    }

    /** [图片] 占位按序替换为 [图片：描述]（join 并发描述结果） */
    private String resolveImagePlaceholders(String rawContent, List<SavedImage> currentImages) {
        if (currentImages.isEmpty()) return rawContent;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\[图片]").matcher(rawContent);
        int imgIdx = 0;
        while (m.find() && imgIdx < currentImages.size()) {
            SavedImage img = currentImages.get(imgIdx++);
            String desc = img.descFuture().join();
            // desc 可能为 null：队列满降级（completedFuture(null)）或视觉模型调用失败返回 null，均按"无描述"处理
            m.appendReplacement(out, desc == null || desc.isBlank()
                    ? "[图片]"
                    : "[图片：" + Matcher.quoteReplacement(sanitizeDesc(desc)) + "]");
        }
        m.appendTail(out);
        if (imgIdx != currentImages.size()) {
            log.warn("图片占位与图片数不一致: 占位{} 图片{}", imgIdx, currentImages.size());
        }
        return out.toString();
    }

    /**
     * 描述文本写入 [图片：…] 标记前的方括号清理：下游多处按"标记内不含 ]"正则解析
     * （RagService IMG_NUMBER_PATTERN、DocumentService IMG_PH/重叠尾巴、ImageFilterService、
     * QaLogService、RagService 历史剥离），描述含 ASCII [] 会让标记被截断错配，统一换全角。
     */
    static String sanitizeDesc(String desc) {
        if (desc == null || desc.isEmpty()) return desc;
        return desc.replace("[", "［").replace("]", "］");
    }

    /** WordprocessingML 命名空间（文本框 DOM 提取用） */
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /**
     * 段落内文本框（w:txbxContent）旁注文字：POI getText() 只覆盖 run 直挂文本；
     * WPS/Word 常用"文本框"承载图注/旁注，此前整段丢失。各内嵌段落按行拼接返回，
     * 由调用方追加到主文本后（文本框相对正文无强顺序语义，近似位置足够 RAG 用）。
     */
    private String textboxExtraText(XWPFParagraph p) {
        try {
            org.w3c.dom.Node node = p.getCTP().getDomNode();
            if (!(node instanceof org.w3c.dom.Element el)) return "";
            org.w3c.dom.NodeList boxes = el.getElementsByTagNameNS(W_NS, "txbxContent");
            if (boxes.getLength() == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int b = 0; b < boxes.getLength(); b++) {
                org.w3c.dom.NodeList paras = ((org.w3c.dom.Element) boxes.item(b)).getElementsByTagNameNS(W_NS, "p");
                for (int i = 0; i < paras.getLength(); i++) {
                    String pt = textBoxParagraphText((org.w3c.dom.Element) paras.item(i));
                    if (pt.isEmpty()) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(pt);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("文本框文本提取失败（忽略）: {}", e.getMessage());
            return "";
        }
    }

    /** 文本框内单个 w:p 的纯文本（按文档序拼接 w:t；近似处理：不建模 tab/br 布局） */
    private String textBoxParagraphText(org.w3c.dom.Element pEl) {
        org.w3c.dom.NodeList ts = pEl.getElementsByTagNameNS(W_NS, "t");
        if (ts.getLength() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ts.getLength(); i++) {
            sb.append(ts.item(i).getTextContent());
        }
        return sb.toString().trim();
    }

    /** 组装分块：净正文 + 图片 URL 列表 + 章节路径（路径不拼进正文，向量化/检索时再拼装） */
    private Chunk buildChunk(String title, String text, List<String> images, String titlePath) {
        return new Chunk(title, text, images, titlePath);
    }

    private List<String> imageUrls(List<SavedImage> currentImages) {
        return currentImages.stream().map(SavedImage::url).toList();
    }

    private int countOccurrences(String text, String sub) {
        if (text == null || sub.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 保存图片到 data/images/{docId}/{sha256}.{ext}（内容寻址），返回访问 URL
     * 同内容图片天然同路径：跨重解析 URL 稳定，复用块引用有效；变更图片自动落新文件
     */
    private String persistImage(byte[] bytes, String ext, String docId, String hash) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        Files.createDirectories(dir);
        String filename = hash + "." + ext;
        Files.write(dir.resolve(filename), bytes);
        return properties.getImages().getUrlPrefix() + "/" + docId + "/" + filename;
    }

    /** 图片字节 SHA-256（hex） */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // MessageDigest 正常不会失败；极端兜底保证文件名唯一
            return "img" + Long.toHexString(System.nanoTime());
        }
    }

    /** 标题样式名匹配（"heading 1" / "标题 1" / "Heading1"） */
    private static final Pattern HEADING_STYLE_NAME = Pattern.compile("(?i)(?:heading|标题)\\s*(\\d+)");

    /**
     * 解析文档样式表：styleId → 标题层级（1~9），目录样式（toc/目录）→ -1。
     * WPS/Word 生成的中文文档常把内置标题样式 ID 定义成数字（2=标题1、3=标题2、4=标题3...），
     * 直接按 ID 数字匹配（s.equals("2")）会整体错位/漏识别——必须按样式 name 或样式级 outlineLvl 建立映射。
     * 直接从 docx zip 读 word/styles.xml（POI XWPFStyles 无遍历 API，且各版本不一致）。
     */
    private Map<String, Integer> buildStyleLevels(java.nio.file.Path docxFile) {
        Map<String, Integer> map = new HashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(docxFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!"word/styles.xml".equals(entry.getName())) continue;
                byte[] xml = zis.readAllBytes();
                // 防 XXE：styles.xml 来自上传文件，禁用 DOCTYPE 与外部实体（恶意 docx 可借实体读取/探测内网）
                javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                try {
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                } catch (javax.xml.parsers.ParserConfigurationException ignored) {
                    // 上述特性不可用时由下方兜底开关再拦一层（老 JDK/自定义实现）
                    dbf.setXIncludeAware(false);
                    dbf.setExpandEntityReferences(false);
                }
                dbf.setXIncludeAware(false);
                dbf.setExpandEntityReferences(false);
                org.w3c.dom.Document d = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                org.w3c.dom.NodeList styleNodes = d.getElementsByTagName("w:style");
                for (int i = 0; i < styleNodes.getLength(); i++) {
                    org.w3c.dom.Element se = (org.w3c.dom.Element) styleNodes.item(i);
                    if (!"paragraph".equals(se.getAttribute("w:type"))) continue;
                    String id = se.getAttribute("w:styleId");
                    if (id == null || id.isEmpty()) continue;
                    String name = childText(se, "w:name");
                    if (name != null && !name.isBlank()) {
                        String nm = name.trim();
                        // 目录样式段落（含页码）→ 跳过，不混入知识块
                        if (nm.toLowerCase().startsWith("toc") || nm.startsWith("目录")) {
                            map.put(id, -1);
                            continue;
                        }
                        Matcher m = HEADING_STYLE_NAME.matcher(nm);
                        if (m.matches()) {
                            map.put(id, Integer.parseInt(m.group(1)));
                            continue;
                        }
                    }
                    // 样式级 outlineLvl（WPS/Word 标题样式在样式定义里带大纲级别，段落内联通常没有）
                    String lvlStr = childText(se, "w:pPr", "w:outlineLvl", "w:val");
                    if (lvlStr != null) {
                        try {
                            int lvl = Integer.parseInt(lvlStr.trim());
                            if (lvl >= 0 && lvl <= 8) map.put(id, lvl + 1);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                break;
            }
        } catch (Exception e) {
            // fail-loud：样式映射失败 → 标题识别退化，块会全用默认标题——必须 warn 可见（此前 debug 级静默吞掉难排查）
            log.warn("[FAIL-LOUD] 样式层级映射解析失败，标题识别将退化（块标题可能全部相同）: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 取直接子元素值（逐级 path 查找：w:name 或 w:pPr/w:outlineLvl/w:val）。
     * OOXML 的值通常承载在 w:val 属性而非元素文本（如 &lt;w:name w:val="heading 1"/&gt;），
     * 文本为空时回退读 w:val 属性——否则 name/outlineLvl 全取空，样式映射建立失败（标题全部识别为 0）。
     */
    private static String childText(org.w3c.dom.Element parent, String... path) {
        org.w3c.dom.Element cur = parent;
        for (String tag : path) {
            org.w3c.dom.Element next = null;
            for (org.w3c.dom.Node n = cur.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof org.w3c.dom.Element e && tag.equals(e.getTagName())) {
                    next = e;
                    break;
                }
            }
            if (next == null) return null;
            cur = next;
        }
        String text = cur.getTextContent();
        if (text != null && !text.isBlank()) return text.trim();
        String val = cur.getAttribute("w:val");
        return (val == null || val.isEmpty()) ? null : val;
    }

    /**
     * 检测段落标题层级：优先查样式映射（styleId → level，覆盖 WPS/Word 数字样式 ID）；
     * 映射未命中回退：样式名关键词 + 段落内联大纲级别。返回 -1 表示目录段落（调用方跳过）。
     */
    private int headingLevel(XWPFParagraph p, Map<String, Integer> styleLevels) {
        String style = p.getStyle();
        if (style != null && styleLevels.containsKey(style)) {
            return styleLevels.get(style);
        }
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("heading1") || s.contains("标题1")) return 1;
            if (s.contains("heading2") || s.contains("标题2")) return 2;
            if (s.contains("heading3") || s.contains("标题3")) return 3;
        }
        try {
            var pPr = p.getCTP().getPPr();
            if (pPr != null && pPr.isSetOutlineLvl()) {
                int outlineLvl = pPr.getOutlineLvl().getVal().intValue();
                if (outlineLvl >= 0 && outlineLvl <= 2) {
                    return outlineLvl + 1;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 已保存图片：URL + 异步生成的描述（flush 时 join） */
    private record SavedImage(String url, CompletableFuture<String> descFuture) {}
}
