package com.xzxg.shop.image;

import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImageSearchController {

    private final ImageSearchService images;

    public ImageSearchController(ImageSearchService images) {
        this.images = images;
    }

    public record ImageSearchRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("file_id") String fileId,
            @com.fasterxml.jackson.annotation.JsonProperty("object_key") String objectKey,
            @com.fasterxml.jackson.annotation.JsonProperty("image_url") String imageUrl,
            @com.fasterxml.jackson.annotation.JsonProperty("top_k") Integer topK) {
    }

    @PostMapping("/api/v1/search/image")
    public ImageSearchService.ImageSearchResponse search(
            @RequestBody(required = false) ImageSearchRequest body,
            HttpServletRequest request) {
        if (RequestContext.account(request) == null) {
            throw ApiException.unauthorized();
        }
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
        }
        return images.search(body.fileId(), body.objectKey(), body.imageUrl(),
                body.topK() == null ? 0 : body.topK());
    }
}
