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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real business logic, mocked remote product lookup and persistence; never touches a database. */
class OrderCreationServiceTest {

    private final ProductClient productClient = mock(ProductClient.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final OrderService service = new OrderService(productClient, orderMapper, orderItemMapper);

    @ParameterizedTest
    @ValueSource(longs = {7L, 8L})
    void persistsTrustedUserIdWithItemsOnlyRequestAndCorrectTotalsAndSnapshots(long userId) {
        OrderCreateRequest request = request(item(1L, 2), item(2L, 3));
        when(productClient.getProductById(1L)).thenReturn(product(1L, "键盘", "12.50", 1));
        when(productClient.getProductById(2L)).thenReturn(product(2L, "配件", "0.10", 1));
        long generatedId = 9000L + userId;
        stubGeneratedId(generatedId);

        OrderDTO result = service.createOrder(userId, request);

        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(savedOrder.capture());
        assertEquals(userId, savedOrder.getValue().getUserId().longValue());
        assertEquals(new BigDecimal("25.30"), savedOrder.getValue().getTotalAmount());
        assertEquals("NORMAL", savedOrder.getValue().getStatus());

        ArgumentCaptor<OrderItem> savedItems = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(2)).insert(savedItems.capture());
        OrderItem first = savedItems.getAllValues().get(0);
        OrderItem second = savedItems.getAllValues().get(1);
        assertAll(
                () -> assertEquals(generatedId, first.getOrderId().longValue()),
                () -> assertEquals(1L, first.getProductId().longValue()),
                () -> assertEquals("键盘", first.getProductName()),
                () -> assertEquals(new BigDecimal("12.50"), first.getUnitPrice()),
                () -> assertEquals(2, first.getQuantity().intValue()),
                () -> assertEquals(new BigDecimal("25.00"), first.getSubtotal()),
                () -> assertEquals(generatedId, second.getOrderId().longValue()),
                () -> assertEquals(2L, second.getProductId().longValue()),
                () -> assertEquals("配件", second.getProductName()),
                () -> assertEquals(new BigDecimal("0.10"), second.getUnitPrice()),
                () -> assertEquals(3, second.getQuantity().intValue()),
                () -> assertEquals(new BigDecimal("0.30"), second.getSubtotal())
        );
        assertEquals(generatedId, result.getId().longValue());
        assertEquals(new BigDecimal("25.30"), result.getTotalAmount());
        assertEquals("NORMAL", result.getStatus());
        verify(productClient).getProductById(1L);
        verify(productClient).getProductById(2L);
        verifyNoMoreInteractions(productClient, orderMapper, orderItemMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0})
    void rejectsUnavailableProductBeforeWritingAnyOrderOrItem(Integer status) {
        OrderCreateRequest request = request(item(1L, 1), item(2L, 1));
        when(productClient.getProductById(1L)).thenReturn(product(1L, "键盘", "12.50", 1));
        when(productClient.getProductById(2L)).thenReturn(product(2L, "配件", "0.10", status));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createOrder(7L, request));

        assertTrue(error.getMessage().contains("商品未上架"));
        verifyNoInteractions(orderMapper, orderItemMapper);
    }

    @Test
    void rejectsMissingPriceBeforeWritingAnyOrderOrItem() {
        OrderCreateRequest request = request(item(1L, 1), item(2L, 1));
        when(productClient.getProductById(1L)).thenReturn(product(1L, "键盘", "12.50", 1));
        when(productClient.getProductById(2L)).thenReturn(product(2L, "配件", null, 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createOrder(7L, request));

        assertTrue(error.getMessage().contains("商品价格为空"));
        verifyNoInteractions(orderMapper, orderItemMapper);
    }

    @Test
    void productLookupFailureStopsBeforeWritingAnyOrderOrItem() {
        OrderCreateRequest request = request(item(1L, 1), item(2L, 1));
        when(productClient.getProductById(1L)).thenReturn(product(1L, "键盘", "12.50", 1));
        IllegalArgumentException failure = new IllegalArgumentException("商品服务返回了空数据");
        when(productClient.getProductById(2L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> service.createOrder(7L, request)));
        verifyNoInteractions(orderMapper, orderItemMapper);
    }

    private void stubGeneratedId(long id) {
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(id);
            return 1;
        });
    }

    private static OrderCreateRequest request(OrderItemCreateRequest... items) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setItems(List.of(items));
        return request;
    }

    private static OrderItemCreateRequest item(long productId, int quantity) {
        OrderItemCreateRequest item = new OrderItemCreateRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private static ProductInfoResponse product(long id, String name, String price, Integer status) {
        return new ProductInfoResponse(id, name, price == null ? null : new BigDecimal(price), status);
    }
}
