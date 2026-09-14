package com.smartmall.order.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartmall.order.client.ProductClient;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.entity.Order;
import com.smartmall.order.exception.OrderNotFoundException;
import com.smartmall.order.mapper.OrderItemMapper;
import com.smartmall.order.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
        order.setUserId(7L);
        order.setTotalAmount(new BigDecimal("599.80"));
        order.setStatus("PENDING_PAYMENT");

        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        OrderDTO result = orderService.cancelOrder(7L, 1L);

        assertEquals(1L, result.getId());
        assertEquals(new BigDecimal("599.80"), result.getTotalAmount());
        assertEquals("CANCELLED", result.getStatus());
        assertEquals("CANCELLED", order.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<Order>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(orderMapper).update(isNull(), updateCaptor.capture());
        String whereSql = updateCaptor.getValue().getSqlSegment();
        String setSql = updateCaptor.getValue().getSqlSet();
        assertTrue(whereSql.contains("id"));
        assertTrue(whereSql.contains("user_id"));
        assertTrue(whereSql.contains("status"));
        assertTrue(setSql.startsWith("status="));
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
        order.setUserId(7L);
        order.setStatus("CANCELLED");

        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(
                IllegalArgumentException.class,
                () -> orderService.cancelOrder(7L, 1L)
        );

        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void shouldHideAnOrderOwnedByAnotherUser() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = new OrderService(
                mock(ProductClient.class), orderMapper, mock(OrderItemMapper.class));
        Order order = new Order();
        order.setId(1L);
        order.setUserId(8L);
        order.setStatus("PENDING_PAYMENT");
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder(7L, 1L));
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectCancellationWhenOrderDoesNotExist() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = new OrderService(
                mock(ProductClient.class), orderMapper, mock(OrderItemMapper.class));
        when(orderMapper.selectById(1L)).thenReturn(null);

        assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder(7L, 1L));
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void shouldNotReportSuccessWhenConditionalUpdateMisses() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = new OrderService(
                mock(ProductClient.class), orderMapper, mock(OrderItemMapper.class));
        Order order = new Order();
        order.setId(1L);
        order.setUserId(7L);
        order.setStatus("PENDING_PAYMENT");
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> orderService.cancelOrder(7L, 1L));
        assertEquals("PENDING_PAYMENT", order.getStatus());
    }
}
