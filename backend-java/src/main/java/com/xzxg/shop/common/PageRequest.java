package com.xzxg.shop.common;

/**
 * Pagination mirrors the Go helpers: page defaults to 1, page_size to 10, and
 * page_size is capped at 100.
 */
public record PageRequest(int page, int pageSize) {

    public static PageRequest of(Integer page, Integer pageSize) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
        return new PageRequest(normalizedPage, normalizedSize);
    }

    public int offset() {
        return (page - 1) * pageSize;
    }
}
