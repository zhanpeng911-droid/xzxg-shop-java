package com.xzxg.shop.common;

import com.xzxg.shop.domain.ProductAttribute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON column helpers. Decode failures fall back to empty collections, matching the
 * Go helpers that ignore unmarshal errors.
 */
@Component
public class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    public List<ProductAttribute> attributes(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProductAttribute>>() {
            });
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    public Map<String, String> stringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    public <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }
}
