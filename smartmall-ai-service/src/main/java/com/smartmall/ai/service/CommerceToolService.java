package com.smartmall.ai.service;

import com.smartmall.ai.exception.AiUpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class CommerceToolService {

    private final RestClient productClient;
    private final RestClient orderClient;

    public CommerceToolService(
            @Value("${smartmall.ai.product-service-url}") String productServiceUrl,
            @Value("${smartmall.ai.order-service-url}") String orderServiceUrl) {
        this.productClient = RestClient.builder().baseUrl(productServiceUrl).build();
        this.orderClient = RestClient.builder().baseUrl(orderServiceUrl).build();
    }

    public String searchProducts(String name) {
        try {
            String response = productClient.get()
                    .uri(uriBuilder -> {
                        var uri = uriBuilder.path("/products")
                                .queryParam("status", 1)
                                .queryParam("page", 1)
                                .queryParam("size", 10);
                        if (name != null && !name.isBlank()) {
                            uri.queryParam("name", name.trim());
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(String.class);
            return requireBody(response, "商品服务返回了空响应");
        } catch (RestClientException exception) {
            throw new AiUpstreamException("商品服务暂时不可用，请稍后重试", exception);
        }
    }

    public String listMyOrders(String accessToken) {
        try {
            String response = orderClient.get()
                    .uri("/orders/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return requireBody(response, "订单服务返回了空响应");
        } catch (RestClientException exception) {
            throw new AiUpstreamException("订单服务暂时不可用，请稍后重试", exception);
        }
    }

    private String requireBody(String body, String message) {
        if (body == null || body.isBlank()) {
            throw new AiUpstreamException(message);
        }
        return body;
    }
}
