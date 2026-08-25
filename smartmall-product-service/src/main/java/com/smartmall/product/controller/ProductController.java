package com.smartmall.product.controller;

import com.smartmall.common.PageResult;
import com.smartmall.common.Result;
import com.smartmall.product.dto.ProductCreateRequest;
import com.smartmall.product.dto.ProductDTO;
import com.smartmall.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public Result<ProductDTO> create(
            @Valid @RequestBody ProductCreateRequest request) {
        return Result.success(productService.createProduct(request));
    }

    @GetMapping
    public Result<PageResult<ProductDTO>> list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false)
            @Min(value = 0, message = "商品状态只能是0或1")
            @Max(value = 1, message = "商品状态只能是0或1")
            Integer status,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码必须大于等于1")
            int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页大小必须大于等于1")
            @Max(value = 100, message = "每页大小不能超过100")
            int size) {
        return Result.success(productService.getProducts(name, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<ProductDTO> getById(@PathVariable long id) {
        return Result.success(productService.getProductById(id));
    }

    @PutMapping("/{id}")
    public Result<Void> updateById(
            @PathVariable long id,
            @Valid @RequestBody ProductCreateRequest request) {
        productService.updateProductById(id, request);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteById(@PathVariable long id) {
        productService.deleteProductById(id);
        return Result.success(null);
    }
}
