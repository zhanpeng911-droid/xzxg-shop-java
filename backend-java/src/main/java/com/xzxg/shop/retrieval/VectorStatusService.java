package com.xzxg.shop.retrieval;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.config.ShopProperties;
import com.xzxg.shop.domain.VectorCollectionStatus;
import com.xzxg.shop.domain.VectorIndexStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class VectorStatusService {

    private final MilvusClient milvus;
    private final ShopProperties.Vector config;

    public VectorStatusService(MilvusClient milvus, ShopProperties properties) {
        this.milvus = milvus;
        this.config = properties.getRetrieval().getVector();
    }

    public VectorIndexStatus status() {
        LocalDateTime now = LocalDateTime.now();
        if (!milvus.enabled()) {
            return new VectorIndexStatus(false, false, config.getMilvusAddress(), List.of(), "",
                    GoTime.format(now));
        }
        List<VectorCollectionStatus> collections = new ArrayList<>();
        String error = "";
        boolean ready = false;
        String[][] targets = {
                {config.getProductCollection(), "products", "product_id"},
                {config.getKnowledgeCollection(), "knowledge", "chunk_id"},
                {config.getImageCollection(), "product_images", "image_vector_id"},
        };
        for (String[] target : targets) {
            try {
                VectorCollectionStatus status = describe(target[0], target[1], target[2]);
                collections.add(status);
                ready = ready || status.rowCount() > 0;
            } catch (Exception ex) {
                error = ex.getMessage();
            }
        }
        return new VectorIndexStatus(true, ready, config.getMilvusAddress(), collections, error,
                GoTime.format(now));
    }

    private VectorCollectionStatus describe(String collection, String kind, String primaryKey)
            throws Exception {
        JsonNode describe = milvus.describe(collection).path("data");
        String load = describe.path("load").asString("");
        String vectorKey = "embedding";
        int dimension = 0;
        JsonNode fields = describe.path("fields");
        for (JsonNode field : fields) {
            if ("FloatVector".equals(field.path("type").asString())) {
                vectorKey = field.path("name").asString("embedding");
                for (JsonNode param : field.path("params")) {
                    if ("dim".equals(param.path("key").asString())) {
                        dimension = parseInt(param.path("value").asString("0"));
                    }
                }
            }
        }
        String metric = "COSINE";
        for (JsonNode index : describe.path("indexes")) {
            if (vectorKey.equals(index.path("fieldName").asString())
                    && !index.path("metricType").asString("").isEmpty()) {
                metric = index.path("metricType").asString();
            }
        }
        long rowCount = 0;
        try {
            rowCount = milvus.stats(collection).path("data").path("rowCount").asLong(0);
        } catch (Exception ignored) {
            // keep zero
        }
        return new VectorCollectionStatus(collection, kind, primaryKey, vectorKey, metric,
                dimension, rowCount, load);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
