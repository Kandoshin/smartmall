package com.smartmall.order.service;

import com.smartmall.order.client.ProductClient;
import com.smartmall.order.dto.OrderCreateRequest;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.dto.OrderItemCreateRequest;
import com.smartmall.order.dto.ProductInfoResponse;
import com.smartmall.order.entity.Order;
import com.smartmall.order.entity.OrderItem;
import com.smartmall.order.mapper.OrderItemMapper;
import com.smartmall.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {
    private final ProductClient productClient;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public OrderService(
            ProductClient productClient,
            OrderMapper orderMapper,
            OrderItemMapper orderItemMapper){
        this.productClient = productClient;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Transactional
    public OrderDTO createOrder(OrderCreateRequest request) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        for (OrderItemCreateRequest itemRequest : request.getItems()) {
            ProductInfoResponse product =
                    productClient.getProductById(itemRequest.getProductId());

            if (product.getStatus() == null || product.getStatus() != 1) {
                throw new IllegalArgumentException(
                        "商品未上架，不能下单：" + itemRequest.getProductId());
            }

            if (product.getPrice() == null) {
                throw new IllegalArgumentException(
                        "商品价格为空：" + itemRequest.getProductId());
            }

            BigDecimal subtotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setSubtotal(subtotal);

            orderItems.add(orderItem);
            totalAmount = totalAmount.add(subtotal);
        }

        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setTotalAmount(totalAmount);
        order.setStatus("PENDING_PAYMENT");

        orderMapper.insert(order);

        for (OrderItem orderItem : orderItems) {
            orderItem.setOrderId(order.getId());
            orderItemMapper.insert(orderItem);
        }

        return new OrderDTO(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus()
        );

    }
}
