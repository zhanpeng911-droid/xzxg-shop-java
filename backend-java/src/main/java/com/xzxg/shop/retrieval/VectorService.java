package com.xzxg.shop.retrieval;

import com.xzxg.shop.config.ShopProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Hybrid retrieval bridge: failures degrade to the MySQL keyword path. */
@Service
public class VectorService {

    private static final Logger log = LoggerFactory.getLogger(VectorService.class);

    private final MilvusClient milvus;
    private final EmbeddingClient embeddings;
    private final ShopProperties.Retrieval config;

    public VectorService(MilvusClient milvus, EmbeddingClient embeddings, ShopProperties properties) {
        this.milvus = milvus;
        this.embeddings = embeddings;
        this.config = properties.getRetrieval();
    }

    public boolean enabled() {
        return milvus.enabled() && embeddings.configured();
    }

    public List<String> searchKnowledgeIds(String query, int topN) {
        if (!enabled()) {
            return List.of();
        }
        try {
            float[] vector = embeddings.embed(List.of(query)).get(0);
            List<MilvusClient.SearchHit> hits = milvus.search(
                    config.getVector().getKnowledgeCollection(), vector, "chunk_id",
                    List.of("chunk_id", "source"), topN);
            List<String> ids = new ArrayList<>(hits.size());
            for (MilvusClient.SearchHit hit : hits) {
                ids.add(hit.id());
            }
            return ids;
        } catch (Exception ex) {
            log.debug("knowledge vector search failed, falling back to keyword: {}", ex.getMessage());
            return List.of();
        }
    }

    public List<String> searchImageProductIds(float[] vector, int topN) {
        if (!milvus.enabled()) {
            return List.of();
        }
        try {
            List<MilvusClient.SearchHit> hits = milvus.search(
                    config.getVector().getImageCollection(), vector, "image_vector_id",
                    List.of("image_vector_id", "product_id", "image_url", "image_type"), topN);
            List<String> productIds = new ArrayList<>();
            for (MilvusClient.SearchHit hit : hits) {
                String productId = hit.fields().get("product_id") == null
                        ? "" : hit.fields().get("product_id").asString();
                if (!productId.isEmpty() && !productIds.contains(productId)) {
                    productIds.add(productId);
                }
            }
            return productIds;
        } catch (Exception ex) {
            log.debug("image vector search failed: {}", ex.getMessage());
            return List.of();
        }
    }
}
