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
import com.smartmall.order.exception.OrderNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartmall.order.dto.OrderDetailDTO;
import com.smartmall.order.dto.OrderItemDTO;

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

    public OrderDetailDTO getOrderDetailById(long orderId){
        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }

        LambdaQueryWrapper<OrderItem> queryWrapper =
                new LambdaQueryWrapper<>();

        queryWrapper.eq(OrderItem::getOrderId, orderId);

        List<OrderItem> orderItems = orderItemMapper.selectList(queryWrapper);

        List<OrderItemDTO> orderItemDTOS = orderItems.stream()
                .map(item -> new OrderItemDTO(
                        item.getProductId(),
                        item.getProductName(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        item.getSubtotal()
                ))
                .toList();

        return new OrderDetailDTO(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt(),
                orderItemDTOS
        );
    }

    public List<OrderDTO> getOrdersByUserId(Long userId){
        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();

        queryWrapper.eq(Order::getUserId,userId);
        queryWrapper.orderByDesc(Order::getCreatedAt);

        List<Order> orders = orderMapper.selectList(queryWrapper);

        return orders.stream()
                .map(order -> new OrderDTO(
                        order.getId(),
                        order.getTotalAmount(),
                        order.getStatus()
                ))
                .toList();

    }

    @Transactional
    public OrderDTO cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new IllegalArgumentException("订单状态异常");
        }

        order.setStatus("CANCELLED");
        orderMapper.updateById(order);

        return new OrderDTO(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus()
        );

    }
}
