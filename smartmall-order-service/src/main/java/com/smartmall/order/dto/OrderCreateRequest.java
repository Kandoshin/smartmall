package com.smartmall.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class OrderCreateRequest {

    @NotEmpty(message = "订单至少需要一个商品")
    @Valid
    private List<OrderItemCreateRequest> items;
}
