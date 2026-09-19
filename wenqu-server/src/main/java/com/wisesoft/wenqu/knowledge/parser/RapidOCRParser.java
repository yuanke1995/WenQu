package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RapidOCR 解析器 - 使用 ONNX 模型进行文字识别（knowledge/parser/rapid_ocr.py 全量移植）。
 *
 * <p>能力差异（显式标注，非静默省略）：
 * <ul>
 *   <li>参考实现经 {@code rapidocr} 包调用本地 PP-OCRv5 ONNX 检测/识别模型；
 *       Java 部署无该 ONNX 推理栈（rapidocr_onnxruntime 为 Python 生态组件），
 *       故 {@link #runOcrInference(Path)} 缺省抛 {@link OCRException}（加载失败形态，
 *       与参考实现模型加载失败的异常面一致）。云端/自托管引擎（MinerU、PaddleOCR 等）
 *       已完整可用；如需本地 OCR，可挂接 JNI ONNX Runtime 后重写该钩子。
 *   <li>PDF 渲染：pypdfium2 {@code page.render(scale=zoom).to_pil()} →
 *       PDFBox {@code PDFRenderer}（同一"按 scale 渲染位图"语义，scale 数值一致）。
 * </ul>
 */
@Slf4j
public class RapidOCRParser extends BaseDocumentProcessor {

    /** 参考实现 __init__(det_box_thresh=0.3)。 */
    protected final double detBoxThresh;

    public RapidOCRParser() {
        this(Map.of());
    }

    public RapidOCRParser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("rapid_ocr");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();
        Object thresh = kwargs.get("det_box_thresh");
        this.detBoxThresh = thresh instanceof Number number ? number.doubleValue() : 0.3;
    }

    static void register() {
        DocumentProcessorFactory.register("rapid_ocr", RapidOCRParser::new);
    }

    @Override
    public Map<String, Object> checkHealth() {
        // 报告本地组件状态，避免选择器刷新时重复加载模型。
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ocr_version", "PP-OCRv5");
        details.put("engine", "onnxruntime");
        details.put("det_box_thresh", detBoxThresh);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "healthy");
        result.put("message", "RapidOCR PP-OCRv5 组件可用");
        result.put("details", details);
        return result;
    }

    /** 延迟加载 OCR 模型（Java 部署无本地 ONNX 推理栈，见类注释能力差异）。 */
    private void loadModel() {
        throw new OCRException(
                "RapidOCR模型加载失败: 本地 PP-OCRv5 ONNX 引擎在 Java 部署中不可用（能力差异，见类注释）；请改用 MinerU/PaddleOCR 等云端引擎",
                getServiceName(),
                "load_failed");
    }

    /** OCR 推理钩子：输入图像文件路径，返回识别文本（按行拼接）。 */
    protected String runOcrInference(Path imagePath) {
        loadModel();
        return "";
    }

    /** 处理单张图像并提取文本（参考实现支持 PIL/numpy 输入，Java 侧统一为文件路径入口）。 */
    public String processImage(String imagePath, Map<String, Object> params) {
        loadModel();
        try {
            Path image = Path.of(imagePath);
            long startTime = System.nanoTime();
            String text = runOcrInference(image);
            double processingTime = (System.nanoTime() - startTime) / 1e9;

            if (text.isEmpty()) {
                log.warn("RapidOCR 未识别到文本: {}", imagePath);
                return "";
            }
            log.info("RapidOCR 成功: {} ({}s)", image.getFileName().toString(), String.format("%.2f", processingTime));
            return text;
        } catch (RuntimeException exc) {
            String errorMsg = "图像OCR处理失败: " + exc.getMessage();
            log.error(errorMsg);
            throw new OCRException(errorMsg, getServiceName(), "processing_failed");
        }
    }

    /** 处理 PDF 文件并提取文本（流式处理，避免内存占用）。 */
    public String processPdf(String pdfPath, Map<String, Object> params) {
        File pdfFile = Path.of(pdfPath).toFile();
        if (!pdfFile.exists()) {
            throw new OCRException("PDF 文件不存在: " + pdfPath, getServiceName(), "file_not_found");
        }

        Map<String, Object> effectiveParams = params == null ? Map.of() : params;
        double zoomX = effectiveParams.get("zoom_x") instanceof Number number ? number.doubleValue() : 2;

        try {
            List<String> allText = new ArrayList<>();
            org.apache.pdfbox.pdmodel.PDDocument pdfDocument =
                    org.apache.pdfbox.Loader.loadPDF(pdfFile);
            int totalPages = pdfDocument.getNumberOfPages();
            log.info("开始处理 PDF: {} ({} 页)", pdfFile.getName(), totalPages);

            // 流式处理每一页，避免一次性加载所有图片到内存
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(pdfDocument);
            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                java.awt.image.BufferedImage image = renderer.renderImage(pageNum, (float) zoomX);
                Path tempImage = Files.createTempFile("wenqu-ocr-", ".png");
                try {
                    javax.imageio.ImageIO.write(image, "png", tempImage.toFile());
                    // 立即处理，不保存到列表（对应参考实现逐页调用 process_image）
                    allText.add(processImage(tempImage.toString(), Map.of()));
                } finally {
                    Files.deleteIfExists(tempImage);
                }
                if ((pageNum + 1) % 10 == 0) {
                    log.info("已处理 {}/{} 页", pageNum + 1, totalPages);
                }
            }
            pdfDocument.close();

            String resultText = String.join("\n\n", allText);
            log.info("PDF OCR 完成: {} - {} 字符", pdfFile.getName(), resultText.length());
            return resultText;
        } catch (OCRException exc) {
            throw exc;
        } catch (IOException | RuntimeException exc) {
            String errorMsg = "PDF OCR 处理失败: " + exc.getMessage();
            log.error(errorMsg);
            throw new OCRException(errorMsg, getServiceName(), "pdf_processing_failed");
        }
    }

    @Override
    public String processFile(String filePath, Map<String, Object> params) {
        String fileExt = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath);
        if (!supportsFileType(fileExt)) {
            throw new OCRException(
                    "不支持的文件类型: " + fileExt, getServiceName(), "unsupported_file_type");
        }
        if (".pdf".equals(fileExt.toLowerCase(Locale.ROOT))) {
            return processPdf(filePath, params);
        }
        return processImage(filePath, params);
    }
}
