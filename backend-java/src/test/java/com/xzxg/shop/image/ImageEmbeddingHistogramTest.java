package com.xzxg.shop.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageEmbeddingHistogramTest {

    @Test
    void opaqueRedFillsRedBucket() throws Exception {
        byte[] png = png(2, 2, 0xFFFF0000);

        float[] vector = ImageEmbeddingService.histogram(png);

        assertThat(vector).hasSize(64);
        assertThat(vector[48]).isEqualTo(1.0f);
        assertThat(sum(vector)).isEqualTo(1.0f);
    }

    @Test
    void transparentPixelsArePremultipliedToBlackBucket() throws Exception {
        byte[] png = png(2, 2, 0x00FF0000);

        float[] vector = ImageEmbeddingService.histogram(png);

        assertThat(vector[0]).isEqualTo(1.0f);
        assertThat(vector[48]).isZero();
    }

    @Test
    void mixedPixelsNormalizeByTotalCount() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0xFF00FF00);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        float[] vector = ImageEmbeddingService.histogram(out.toByteArray());

        assertThat(vector[48]).isEqualTo(0.5f);
        assertThat(vector[12]).isEqualTo(0.5f);
    }

    private static byte[] png(int width, int height, int argb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static float sum(float[] values) {
        float total = 0;
        for (float value : values) {
            total += value;
        }
        return total;
    }
}
