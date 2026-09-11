package com.smartmall.order.controller;

import com.smartmall.common.Result;
import com.smartmall.order.dto.OrderCreateRequest;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.dto.OrderDetailDTO;
import com.smartmall.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public Result<OrderDTO> create(
        @Valid @RequestBody OrderCreateRequest request){
        return Result.success(orderService.createOrder(request));
    }

    @GetMapping("/orders/{id}")
    public Result<OrderDetailDTO> get(@PathVariable long id){
        return Result.success(orderService.getOrderDetailById(id));
    }

    @GetMapping("/orders/me")
    public Result<List<OrderDTO>> list(@AuthenticationPrincipal Jwt jwt){
        long userId = Long.parseLong(jwt.getSubject());
        return Result.success(orderService.getOrdersByUserId(userId));
    }

    @PatchMapping("/orders/{id}/cancel")
    public Result<OrderDTO> cancel(@PathVariable Long id) {
        return Result.success(orderService.cancelOrder(id));
    }

}
