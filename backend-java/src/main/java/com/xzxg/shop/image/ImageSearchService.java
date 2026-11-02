package com.xzxg.shop.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.catalog.CatalogRepository;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.StoredFile;
import com.xzxg.shop.files.ObjectStorage;
import com.xzxg.shop.files.StoredFileRepository;
import com.xzxg.shop.retrieval.VectorService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ImageSearchService {

    private static final int MAX_IMAGE_BYTES = 10 << 20;
    private static final String FILE_URL_MARKER = "/api/v1/files/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final StoredFileRepository files;
    private final ObjectStorage storage;
    private final ImageEmbeddingService embedder;
    private final VectorService vectors;
    private final CatalogRepository catalog;

    public ImageSearchService(StoredFileRepository files, ObjectStorage storage,
                              ImageEmbeddingService embedder, VectorService vectors,
                              CatalogRepository catalog) {
        this.files = files;
        this.storage = storage;
        this.embedder = embedder;
        this.vectors = vectors;
        this.catalog = catalog;
    }

    public record ImageSearchResponse(
            @JsonProperty("relevance_status") String relevanceStatus,
            @JsonProperty("match_status") String matchStatus,
            List<ProductCard> items,
            Map<String, Long> durations) {
    }

    public ImageSearchResponse search(String rawFileId, String rawObjectKey, String rawImageUrl,
                                      int topK) {
        long startedAt = System.nanoTime();
        String fileId = trim(rawFileId);
        String objectKey = trim(rawObjectKey);
        String imageUrl = trim(rawImageUrl);
        if (fileId.isEmpty() && !imageUrl.isEmpty()) {
            String parsed = fileIdFromApiFileUrl(imageUrl);
            if (!parsed.isEmpty()) {
                fileId = parsed;
                imageUrl = "";
            }
        }

        long downloadStartedAt = System.nanoTime();
        byte[] content;
        if (!fileId.isEmpty()) {
            StoredFile meta = files.find(fileId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "file_not_found", "文件不存在"));
            objectKey = meta.objectKey();
            content = downloadObject(objectKey);
        } else if (!objectKey.isEmpty()) {
            content = downloadObject(objectKey);
        } else if (!imageUrl.isEmpty()) {
            content = fetchSource(imageUrl);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_image",
                    "file_id、object_key 或 image_url 至少传一个");
        }
        long downloadMs = elapsedMs(downloadStartedAt);

        long embeddingStartedAt = System.nanoTime();
        float[] vector;
        try {
            vector = embedder.embed(content);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "image_embedding_failed", "图片解析失败");
        }
        long embeddingMs = elapsedMs(embeddingStartedAt);

        long searchStartedAt = System.nanoTime();
        List<String> productIds = vectors.searchImageProductIds(vector, topK);
        long vectorSearchMs = elapsedMs(searchStartedAt);
        List<ProductCard> items = productIds.isEmpty()
                ? List.of() : catalog.productCardsByIds(productIds);

        String status = "matched";
        String matchStatus = "ok";
        if (!vectors.enabled()) {
            status = "no_match";
            matchStatus = "image_vector_index_unavailable";
        } else if (items.isEmpty()) {
            status = "no_match";
            matchStatus = "no_vector_hit";
        }
        Map<String, Long> durations = new LinkedHashMap<>();
        durations.put("total_ms", elapsedMs(startedAt));
        durations.put("download_ms", downloadMs);
        durations.put("embedding_ms", embeddingMs);
        durations.put("vector_search_ms", vectorSearchMs);
        durations.put("rerank_ms", 0L);
        return new ImageSearchResponse(status, matchStatus, items, durations);
    }

    private byte[] downloadObject(String objectKey) {
        try (InputStream stream = storage.get(objectKey)) {
            byte[] data = stream.readNBytes(MAX_IMAGE_BYTES + 1);
            if (data.length > MAX_IMAGE_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "image_embedding_failed", "图片解析失败");
            }
            return data;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "object_not_found", "对象存储文件不存在");
        }
    }

    private static byte[] fetchSource(String source) {
        try {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = HTTP.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("image status " + response.statusCode());
                }
                if (response.body().length > MAX_IMAGE_BYTES) {
                    throw new IOException("image too large");
                }
                return response.body();
            }
            Path path = Paths.get(source);
            if (!Files.exists(path) && !path.isAbsolute()) {
                path = Paths.get("..", source);
            }
            return Files.readAllBytes(path);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "image_embedding_failed", "图片解析失败");
        }
    }

    public static String fileIdFromApiFileUrl(String raw) {
        String value = trim(raw);
        int index = value.indexOf(FILE_URL_MARKER);
        if (index < 0) {
            return "";
        }
        String rest = value.substring(index + FILE_URL_MARKER.length());
        int cut = firstIndexOfAny(rest, "?#/");
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        rest = rest.trim();
        return rest.startsWith("file_") ? rest : "";
    }

    private static int firstIndexOfAny(String value, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int index = value.indexOf(chars.charAt(i));
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
