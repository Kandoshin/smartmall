package com.smartmall.order.client;

import com.smartmall.common.Result;
import com.smartmall.order.dto.ProductInfoResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("http://localhost:8081")
                .build();
    }

    public ProductInfoResponse getProductById(long productId){
        Result<ProductInfoResponse> result = restClient.get()
                .uri("/products/{id}", productId)
                .retrieve()
                .body(new ParameterizedTypeReference<Result<ProductInfoResponse>>() {
                });

        if (result == null || result.getData() == null) {
            throw new IllegalArgumentException("商品服务返回了空数据");
        }

        return result.getData();
    }
}
