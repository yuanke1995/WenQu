package com.wenqu.ai.parser;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Notes;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PPT 解析器（POI SlideShowFactory 按文件内容自动识别 .ppt(HSLF)/.pptx(XSLF)，扩展名改名不影响解析）
 * 每页一个分块：页标题作分块标题，正文按形状树抽取（文本框/占位符/表格/组合形状递归），
 * 备注页文本以 [备注] 段并页尾（讲稿备注常承载正文没有的细节）。
 * 页内图片不入块（与 Excel 解析器同口径：无视觉描述链路，图占位只会产生噪声）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PptParser implements DocumentParser {

    private final AppProperties properties;

    /** 配置读取点：分块粒度必须走这里，知识库 parse_params 的 chunk.maxSize 覆盖经线程局部生效 */
    private final ConfigService configService;

    @Override
    public boolean supports(String ext) {
        return "ppt".equalsIgnoreCase(ext) || "pptx".equalsIgnoreCase(ext);
    }

    @Override
    public Set<String> supportedExts() {
        return Set.of("ppt", "pptx");
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws Exception {
        int maxSize = maxChunkSize();
        List<Chunk> chunks = new ArrayList<>();

        try (SlideShow<?, ?> ppt = SlideShowFactory.create(java.nio.file.Files.newInputStream(file))) {
            Set<String> masterDefaults = notesMasterDefaults(ppt);
            List<? extends Slide<?, ?>> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                Slide<?, ?> slide = slides.get(i);
                String slideTitle = clean(slide.getTitle());
                StringBuilder buf = new StringBuilder();
                appendShapes(slide.getShapes(), buf, Set.of());
                appendNotes(slide, buf, masterDefaults);
                String text = buf.toString().trim();
                if (text.isEmpty() && slideTitle.isEmpty()) {
                    continue; // 纯图页/空页：无文字可检索，跳过
                }
                String title = "第 " + (i + 1) + " 页" + (slideTitle.isEmpty() ? "" : "：" + slideTitle);
                if (text.isEmpty()) {
                    // 仅剩标题的页（如封面）：标题即内容
                    chunks.add(new Chunk(title, slideTitle, List.of()));
                } else if (text.length() <= maxSize) {
                    chunks.add(new Chunk(title, text, List.of()));
                } else {
                    // 单页超长（整页文字/长讲稿）：按上限硬切并标序号，保持全局分块口径
                    int parts = (text.length() + maxSize - 1) / maxSize;
                    for (int off = 0, part = 1; off < text.length(); off += maxSize, part++) {
                        chunks.add(new Chunk(title + "（" + part + "/" + parts + "）",
                                text.substring(off, Math.min(text.length(), off + maxSize)), List.of()));
                    }
                }
            }
        }
        log.info("[PPT] {} 解析出 {} 个分块", fileName, chunks.size());
        return chunks;
    }

    /** 递归抽取形状树文本：文本框直取、表格按「列 | 列」行、组合形状下钻；skipTexts 用于备注里剔除母版默认文本 */
    private void appendShapes(List<? extends Shape<?, ?>> shapes, StringBuilder buf, Set<String> skipTexts) {
        for (Shape<?, ?> shape : shapes) {
            if (shape instanceof GroupShape<?, ?> group) {
                appendShapes(group.getShapes(), buf, skipTexts);
            } else if (shape instanceof TableShape<?, ?> table) {
                for (int r = 0; r < table.getNumberOfRows(); r++) {
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < table.getNumberOfColumns(); c++) {
                        String v = cleanText(table.getCell(r, c));
                        if (!v.isEmpty()) {
                            if (line.length() > 0) line.append(" | ");
                            line.append(v);
                        }
                    }
                    if (line.length() > 0) buf.append(line).append("\n");
                }
            } else if (shape instanceof TextShape<?, ?> ts) {
                String t = clean(ts.getText());
                if (!t.isEmpty() && !skipTexts.contains(t)) buf.append(t).append("\n");
            }
        }
    }

    /** 备注页文本（讲稿）：跳过与母版默认文本相同的占位符（空占位会继承出「单击此处编辑母版文本样式」类噪声） */
    private void appendNotes(Slide<?, ?> slide, StringBuilder buf, Set<String> masterDefaults) {
        Notes<?, ?> notes = slide.getNotes();
        if (notes == null) return;
        StringBuilder nb = new StringBuilder();
        appendShapes(notes.getShapes(), nb, masterDefaults);
        String t = nb.toString().trim();
        if (!t.isEmpty()) buf.append("[备注]\n").append(t).append("\n");
    }

    /**
     * 备注母版的默认文本集合：XSLF 的空占位符会原样继承母版文本（样式提示/页码/动态日期），
     * 与母版文本做等值比对即可整段剔除；HSLF 无此继承行为，取不到母版就空集放行。
     */
    private Set<String> notesMasterDefaults(SlideShow<?, ?> ppt) {
        try {
            if (ppt instanceof org.apache.poi.xslf.usermodel.XMLSlideShow xs) {
                var master = xs.getNotesMaster();
                if (master == null) return Set.of();
                return collectTexts(master.getShapes());
            }
        } catch (Exception e) {
            log.debug("[PPT] 读取备注母版默认文本失败（忽略）: {}", e.getMessage());
        }
        return Set.of();
    }

    private Set<String> collectTexts(List<? extends Shape<?, ?>> shapes) {
        Set<String> out = new java.util.HashSet<>();
        for (Shape<?, ?> shape : shapes) {
            if (shape instanceof GroupShape<?, ?> group) {
                out.addAll(collectTexts(group.getShapes()));
            } else if (shape instanceof TextShape<?, ?> ts) {
                String t = clean(ts.getText());
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** getCell 返回的是形状基类型，文字能力按 TextShape 运行时判定 */
    private String cleanText(Shape<?, ?> shape) {
        return shape instanceof TextShape<?, ?> ts ? clean(ts.getText()) : "";
    }

    private String clean(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private int maxChunkSize() {
        return configService.getInt("chunk.maxSize", properties.getChunk().getMaxSize());
    }
}
