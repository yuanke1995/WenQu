package com.wenqu.ai.service;

import com.wenqu.ai.model.Knowledge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识块展开图片必须带签名。
 * <p>回归症状：知识块预览里展开任一块，图片全 401（控制台 {@code /proxy/images/... 401 Unauthorized}）。
 * 原因是 {@code /knowledge/list} 把库里的原始 URL 直出，而 {@code /images/**} 的
 * {@code ImageAuthInterceptor} 只认 {@code expire}+{@code sig}，不看登录态。
 * <p>这里不 mock 签名结果，而是用真的 {@link ImageUrlSigner} 签完再 {@link ImageUrlSigner#verify} 验，
 * 确保出参里的 URL 能真正通过拦截器那道校验。
 */
class KnowledgeChunkAssemblerTest {

    private static final String DOC_DIR = "/ai/images/fd02341cdd637d4b41cd90f018006c8d";
    private static final String URL_A = DOC_DIR + "/acf9179e.png";
    private static final String URL_B = DOC_DIR + "/3a3feed8.png";

    private final AuthService authService = mock(AuthService.class);
    private ImageUrlSigner signer;
    private KnowledgeChunkAssembler assembler;

    @BeforeEach
    void setUp() {
        when(authService.secret()).thenReturn("unit-test-secret-0123456789abcdef");
        signer = new ImageUrlSigner(authService);
        assembler = new KnowledgeChunkAssembler(signer);
    }

    private static Knowledge chunk(String imagesJson) {
        Knowledge k = new Knowledge();
        k.setId("k-44");
        k.setDocId("fd02341cdd637d4b41cd90f018006c8d");
        k.setTitle("全局设置");
        k.setChunkIndex(44);
        k.setStatus(0);
        k.setImages(imagesJson);
        return k;
    }

    @Test
    void listRowSignsEveryImageSoInterceptorAcceptsIt() {
        Map<String, Object> row = assembler.listRow(chunk("[\"" + URL_A + "\",\"" + URL_B + "\"]"), 3);

        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) row.get("images");
        assertThat(images).hasSize(2);
        for (String signed : images) {
            // 带齐 expire 与 sig，且真的能验过 —— 等于拦截器 preHandle 会放行
            assertThat(signed).contains("?expire=").contains("&sig=");
            String path = signed.substring(0, signed.indexOf('?'));
            long expire = Long.parseLong(signed.replaceAll(".*[?&]expire=(\\d+).*", "$1"));
            String sig = signed.replaceAll(".*[?&]sig=([0-9a-f]+).*", "$1");
            assertThat(signer.verify(path, expire, sig))
                    .as("签名后的图片地址必须能通过 /images/** 鉴权：%s", signed)
                    .isTrue();
        }
        // 其他字段照旧透出（列表渲染依赖）
        assertThat(row).containsEntry("chunkIndex", 44).containsEntry("qaCount", 3).containsEntry("status", 0);
        assertThat(row.get("content")).isNull();
    }

    @Test
    void blankOrMissingImagesYieldEmptyList() {
        assertThat(assembler.signedImages(chunk(null))).isEmpty();
        assertThat(assembler.signedImages(chunk(""))).isEmpty();
        assertThat(assembler.signedImages(chunk("  "))).isEmpty();
        assertThat(assembler.signedImages(chunk("[]"))).isEmpty();
    }

    @Test
    void malformedImagesDegradeToEmptyInsteadOfBreakingWholeList() {
        // 坏 JSON 不能让整页知识块预览打不开
        assertThat(assembler.signedImages(chunk("[{\"not\":\"a string\"}]"))).isEmpty();
        Map<String, Object> row = assembler.listRow(chunk("{oops"), 0);
        assertThat(row.get("images")).asList().isEmpty();
    }

    @Test
    void signingIsIdempotentOnAlreadySignedUrls() {
        List<String> once = assembler.signedImages(chunk("[\"" + URL_A + "\"]"));
        List<String> twice = assembler.signedImages(chunk("[\"" + once.get(0) + "\"]"));
        // 换发时先剥旧签名再签，query 不会堆成 expire=..&sig=..&expire=..&sig=..
        assertThat(twice.get(0)).containsOnlyOnce("expire=").containsOnlyOnce("sig=");
    }
}
