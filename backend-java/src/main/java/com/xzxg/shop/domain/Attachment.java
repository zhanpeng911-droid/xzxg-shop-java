package com.xzxg.shop.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Attachment(
        @JsonProperty("attachment_id") String attachmentId,
        @JsonProperty("file_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String fileId,
        String type,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String url,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String name,
        @JsonProperty("object_key") @JsonInclude(JsonInclude.Include.NON_EMPTY) String objectKey) {
}
