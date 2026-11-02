package com.xzxg.shop.catalog;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.Category;
import com.xzxg.shop.domain.Merchant;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.ProductDetail;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductSKU;
import com.xzxg.shop.domain.ProductUpsertInput;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    private final CatalogRepository catalog;

    public CatalogService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public List<Category> categories() {
        return catalog.listCategories();
    }

    public PageResponse<Merchant> merchants(PageRequest page) {
        return catalog.listMerchants(page, false);
    }

    public PageResponse<Merchant> allMerchants(PageRequest page) {
        return catalog.listMerchants(page, true);
    }

    public PageResponse<ProductCard> products(String keyword, String categoryId, PageRequest page) {
        return catalog.listProducts(keyword, categoryId, page, false);
    }

    public PageResponse<ProductCard> allProducts(PageRequest page) {
        return catalog.listProducts(null, null, page, true);
    }

    public Optional<ProductDetail> product(String productId) {
        return catalog.findProduct(productId);
    }

    public PageResponse<ProductSKU> skus(String productId, PageRequest page) {
        return paginate(catalog.listProductSkus(productId), page);
    }

    public PageResponse<ProductReview> reviews(String productId, PageRequest page) {
        return catalog.listProductReviews(productId, page);
    }

    public ProductDetail createProduct(ProductUpsertInput input) {
        return catalog.createProduct(input);
    }

    public Optional<ProductDetail> updateProduct(String productId, ProductUpsertInput input) {
        return catalog.updateProduct(productId, input);
    }

    public Optional<ProductDetail> updateProductStatus(String merchantId, String productId, String status) {
        return catalog.updateProductStatus(merchantId, productId, status);
    }

    public Optional<Merchant> updateMerchantStatus(String merchantId, String status) {
        return catalog.updateMerchantStatus(merchantId, status);
    }

    private static <T> PageResponse<T> paginate(List<T> all, PageRequest page) {
        int total = all.size();
        int start = page.offset();
        if (start >= total) {
            return new PageResponse<>(List.of(), page.page(), page.pageSize(), total);
        }
        int end = Math.min(start + page.pageSize(), total);
        return new PageResponse<>(all.subList(start, end), page.page(), page.pageSize(), total);
    }
}
