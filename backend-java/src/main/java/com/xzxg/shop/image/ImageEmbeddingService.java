package com.xzxg.shop.image;

import com.xzxg.shop.config.ShopProperties;
import com.xzxg.shop.files.FileTypes;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Two image strategies preserved from the Go backend: the local 64-dim RGB
 * histogram and DashScope multimodal embeddings.
 */
@Service
public class ImageEmbeddingService {

    public static final int HISTOGRAM_DIMENSION = 64;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ShopProperties.ImageEmbedding config;
    private final ObjectMapper objectMapper;

    public ImageEmbeddingService(ShopProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getRetrieval().getImageEmbedding();
        this.objectMapper = objectMapper;
    }

    public int dimension() {
        return dashScopeEnabled() ? Math.max(config.getDimension(), 1) : HISTOGRAM_DIMENSION;
    }

    public float[] embed(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("empty image");
        }
        if (dashScopeEnabled()) {
            return embedDashScope(data);
        }
        return histogram(data);
    }

    public static float[] histogram(byte[] data) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IllegalArgumentException("unsupported or corrupt image");
        }
        float[] vector = new float[HISTOGRAM_DIMENSION];
        long total = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                // Go's color.RGBA() returns alpha-premultiplied 16-bit channels.
                int red16 = premultiply(red, alpha);
                int green16 = premultiply(green, alpha);
                int blue16 = premultiply(blue, alpha);
                int index = (red16 >> 8) / 64 * 16 + (green16 >> 8) / 64 * 4
                        + (blue16 >> 8) / 64;
                if (index >= 0 && index < vector.length) {
                    vector[index] += 1;
                    total++;
                }
            }
        }
        if (total == 0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / total;
        }
        return vector;
    }

    private static int premultiply(int channel, int alpha) {
        long channel16 = (long) channel * 257;
        long alpha16 = (long) alpha * 257;
        return (int) ((channel16 * alpha16) / 65535);
    }

    private boolean dashScopeEnabled() {
        return "dashscope".equalsIgnoreCase(config.getProvider())
                && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private float[] embedDashScope(byte[] data) throws Exception {
        String mimeType = FileTypes.detect(data);
        String image = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(data);
        Map<String, Object> payload = Map.of(
                "model", config.getModel(),
                "input", Map.of("contents", List.of(Map.of("image", image))),
                "parameters", Map.of("dimension", config.getDimension(), "output_type", "dense"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        config.getBaseUrl().replaceAll("/+$", "")
                                + "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("image embedding status " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode embeddings = root.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) {
            throw new IllegalStateException("empty image embedding");
        }
        JsonNode embedding = embeddings.get(0).path("embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        if (vector.length == 0) {
            throw new IllegalStateException("empty image embedding");
        }
        return vector;
    }
}
