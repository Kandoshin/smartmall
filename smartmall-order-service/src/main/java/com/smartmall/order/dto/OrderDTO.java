package com.smartmall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class OrderDTO {

    private Long id;
    private BigDecimal totalAmount;
    private String status;

}
