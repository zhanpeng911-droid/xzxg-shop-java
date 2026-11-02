package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnstructuredIngestResult(
        KnowledgeDocument document,
        boolean duplicate,
        @JsonProperty("text_runes") int textRunes) {
}
