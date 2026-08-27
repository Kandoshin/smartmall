package com.smartmall.order.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("订单不存在：" + orderId);
    }
}
