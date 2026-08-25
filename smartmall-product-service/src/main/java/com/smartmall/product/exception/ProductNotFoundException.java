package com.smartmall.product.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long id) {
        super("商品不存在，id = " + id);
    }
}
