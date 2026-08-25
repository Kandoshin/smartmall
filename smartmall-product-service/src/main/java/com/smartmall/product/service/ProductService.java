package com.smartmall.product.service;

import com.smartmall.product.dto.ProductCreateRequest;
import com.smartmall.product.dto.ProductDTO;
import com.smartmall.common.PageResult;
import com.smartmall.product.entity.Product;
import com.smartmall.product.mapper.ProductMapper;
import com.smartmall.product.exception.ProductNotFoundException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public ProductDTO createProduct(ProductCreateRequest request){
        Product product = new Product();

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setStatus(request.getStatus());

        productMapper.insert(product);

        return new ProductDTO(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus()
        );
    }

    public PageResult<ProductDTO> getProducts(
            String name,
            Integer status,
            int page,
            int size) {
        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();

        if (name != null && !name.isBlank()) {
            queryWrapper.like(Product::getName, name);
        }
        if (status != null) {
            queryWrapper.eq(Product::getStatus, status);
        }

        queryWrapper.orderByAsc(Product::getId);

        Page<Product> productPage = new Page<>(page, size);
        Page<Product> resultPage = productMapper.selectPage(productPage, queryWrapper);

        List<ProductDTO> records = resultPage.getRecords().stream()
                .map(product -> new ProductDTO(
                        product.getId(),
                        product.getName(),
                        product.getDescription(),
                        product.getPrice(),
                        product.getStock(),
                        product.getStatus()
                ))
                .toList();

        return new PageResult<>(
                records,
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal(),
                resultPage.getPages()
        );
    }

    public ProductDTO getProductById(long id) {
        Product product = productMapper.selectById(id);

        if (product == null) {
            throw new ProductNotFoundException(id);
        }

        return new ProductDTO(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus()
        );
    }

    public void updateProductById(long id, ProductCreateRequest request) {
        Product product = new Product();
        product.setId(id);
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setStatus(request.getStatus());

        int affectedRows = productMapper.updateById(product);

        if (affectedRows == 0) {
            throw new ProductNotFoundException(id);
        }
    }

    public void deleteProductById(long id) {
        int affectedRows = productMapper.deleteById(id);

        if (affectedRows == 0) {
            throw new ProductNotFoundException(id);
        }
    }
}
