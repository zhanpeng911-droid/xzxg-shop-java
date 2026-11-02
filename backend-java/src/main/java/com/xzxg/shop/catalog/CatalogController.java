package com.xzxg.shop.catalog;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.Category;
import com.xzxg.shop.domain.Merchant;
import com.xzxg.shop.domain.ProductCard;
import com.xzxg.shop.domain.ProductDetail;
import com.xzxg.shop.domain.ProductReview;
import com.xzxg.shop.domain.ProductSKU;
import com.xzxg.shop.domain.ProductUpsertInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record StatusRequest(String status) {
    }

    @GetMapping("/api/v1/categories/tree")
    public Map<String, Object> categories() {
        return Map.of("items", catalog.categories());
    }

    @GetMapping("/api/v1/merchants")
    public PageResponse<Merchant> merchants(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        return catalog.merchants(PageRequest.of(page, pageSize));
    }

    @GetMapping("/api/v1/products")
    public PageResponse<ProductCard> products(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category_id", required = false) String categoryId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        return catalog.products(keyword, categoryId, PageRequest.of(page, pageSize));
    }

    @GetMapping("/api/v1/products/{productId}")
    public ProductDetail product(@PathVariable String productId) {
        return catalog.product(productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "product_not_found", "商品不存在"));
    }

    @GetMapping("/api/v1/products/{productId}/{action}")
    public Object productAction(@PathVariable String productId, @PathVariable String action,
                                @RequestParam(value = "page", required = false) Integer page,
                                @RequestParam(value = "page_size", required = false) Integer pageSize) {
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        if ("skus".equals(action)) {
            return catalog.skus(productId, pageRequest);
        }
        if ("reviews".equals(action)) {
            return catalog.reviews(productId, pageRequest);
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "接口不存在");
    }

    @PostMapping("/api/v1/merchant/products")
    public ProductDetail createProduct(@RequestBody(required = false) ProductUpsertInput body,
                                       HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_name", "商品名称不能为空");
        }
        return catalog.createProduct(withMerchant(body, merchant.merchantId()));
    }

    @PatchMapping("/api/v1/merchant/products/{productId}")
    public ProductDetail updateProduct(@PathVariable String productId,
                                       @RequestBody(required = false) ProductUpsertInput body,
                                       HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (body.name() == null || body.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_name", "商品名称不能为空");
        }
        return catalog.updateProduct(productId, withMerchant(body, merchant.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "product_not_found", "商品不存在或不属于当前商家"));
    }

    @DeleteMapping("/api/v1/merchant/products/{productId}")
    public Map<String, String> deleteProduct(@PathVariable String productId,
                                             HttpServletRequest request) {
        Account merchant = requireMerchant(request);
        catalog.updateProductStatus(merchant.merchantId(), productId, "deleted")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "product_not_found", "商品不存在或不属于当前商家"));
        return Map.of("product_id", productId, "status", "deleted");
    }

    @GetMapping("/api/v1/admin/products")
    public PageResponse<ProductCard> adminProducts(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return catalog.allProducts(PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/admin/products/{productId}")
    public ProductDetail adminUpdateProduct(@PathVariable String productId,
                                            @RequestBody(required = false) StatusRequest body,
                                            HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedProductStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "商品状态不合法");
        }
        return catalog.updateProductStatus("", productId, body.status())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "product_not_found", "商品不存在"));
    }

    @GetMapping("/api/v1/admin/merchants")
    public PageResponse<Merchant> adminMerchants(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize,
            HttpServletRequest request) {
        requireAdmin(request);
        return catalog.allMerchants(PageRequest.of(page, pageSize));
    }

    @PatchMapping("/api/v1/admin/merchants/{merchantId}")
    public Merchant adminUpdateMerchant(@PathVariable String merchantId,
                                        @RequestBody(required = false) StatusRequest body,
                                        HttpServletRequest request) {
        requireAdmin(request);
        if (body == null) {
            throw badRequestBody();
        }
        if (!allowedMerchantStatus(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_status", "商家状态不合法");
        }
        return catalog.updateMerchantStatus(merchantId, body.status())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "merchant_not_found", "商家不存在"));
    }

    private static Account requireMerchant(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"merchant".equals(account.role()) || account.merchantId() == null
                || account.merchantId().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是商家");
        }
        return account;
    }

    private static Account requireAdmin(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        if (!"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "当前账号不是管理员");
        }
        return account;
    }

    private static ProductUpsertInput withMerchant(ProductUpsertInput input, String merchantId) {
        return new ProductUpsertInput(input.productId(), merchantId, input.name(), input.brand(),
                input.categoryId(), input.imageUrl(), input.price(), input.marketPrice(),
                input.stockQuantity(), input.stockStatus(), input.tags(), input.sellingPoints(),
                input.recommendReason(), input.riskNotes(), input.description());
    }

    private static boolean allowedProductStatus(String status) {
        return "active".equals(status) || "inactive".equals(status)
                || "deleted".equals(status) || "risk".equals(status);
    }

    private static boolean allowedMerchantStatus(String status) {
        return "active".equals(status) || "inactive".equals(status) || "risk".equals(status);
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }
}
