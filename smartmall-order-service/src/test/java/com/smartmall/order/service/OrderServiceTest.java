package com.smartmall.order.service;

import com.smartmall.order.client.ProductClient;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.entity.Order;
import com.smartmall.order.mapper.OrderItemMapper;
import com.smartmall.order.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    @Test
    void shouldCancelPendingPaymentOrder() {
        ProductClient productClient = mock(ProductClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        OrderService orderService = new OrderService(
                productClient,
                orderMapper,
                orderItemMapper
        );

        Order order = new Order();
        order.setId(1L);
        order.setTotalAmount(new BigDecimal("599.80"));
        order.setStatus("PENDING_PAYMENT");

        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.updateById(order)).thenReturn(1);

        OrderDTO result = orderService.cancelOrder(1L);

        assertEquals(1L, result.getId());
        assertEquals(new BigDecimal("599.80"), result.getTotalAmount());
        assertEquals("CANCELLED", result.getStatus());
        assertEquals("CANCELLED", order.getStatus());
        verify(orderMapper).updateById(order);
    }

    @Test
    void shouldRejectCancellationWhenOrderIsNotPending() {
        ProductClient productClient = mock(ProductClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        OrderService orderService = new OrderService(
                productClient,
                orderMapper,
                orderItemMapper
        );

        Order order = new Order();
        order.setId(1L);
        order.setStatus("CANCELLED");

        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.cancelOrder(1L)
        );

        verify(orderMapper, never()).updateById(any(Order.class));
    }
}
