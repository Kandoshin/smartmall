package com.smartmall.product.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Getter
@Setter
@NoArgsConstructor
public class ProductCreateRequest {

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.00", message = "商品价格不能小于0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能小于0")
    private Integer stock;

    @NotNull(message = "商品状态不能为空")
    @Min(value = 0,message = "商品状态只能是0或1")
    @Max(value = 1, message = "商品状态只能是0或1")
    private Integer status;
}
