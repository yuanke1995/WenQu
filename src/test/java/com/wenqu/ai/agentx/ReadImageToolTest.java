package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读图工具的图片类型判定：优先魔数、退回扩展名、非图片返回 null。
 * 不依赖文件系统与模型，纯函数验证「什么会被当成图片送去模型」。
 *
 * @author yuanke
 */
class ReadImageToolTest {

    @Test
    @DisplayName("PNG 魔数识别，忽略扩展名")
    void pngMagic() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        assertThat(ReadImageTool.mimeOf("weird.bin", png)).isEqualTo("image/png");
    }

    @Test
    @DisplayName("JPEG / GIF / WebP / BMP 魔数识别")
    void otherMagic() {
        assertThat(ReadImageTool.mimeOf("x", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isEqualTo("image/jpeg");
        assertThat(ReadImageTool.mimeOf("x", "GIF89a".getBytes())).isEqualTo("image/gif");
        assertThat(ReadImageTool.mimeOf("x", "RIFFxxxxWEBP".getBytes())).isEqualTo("image/webp");
        assertThat(ReadImageTool.mimeOf("x", "BM....".getBytes())).isEqualTo("image/bmp");
    }

    @Test
    @DisplayName("无魔数时按扩展名兜底")
    void byExtension() {
        assertThat(ReadImageTool.mimeOf("photo.JPG", new byte[]{1, 2, 3})).isEqualTo("image/jpeg");
        assertThat(ReadImageTool.mimeOf("shot.png", new byte[]{1, 2, 3})).isEqualTo("image/png");
    }

    @Test
    @DisplayName("非图片返回 null（既非图片魔数也非图片扩展名）")
    void notImage() {
        assertThat(ReadImageTool.mimeOf("notes.txt", "hello".getBytes())).isNull();
        assertThat(ReadImageTool.mimeOf("data", new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8})).isNull();
    }
}
