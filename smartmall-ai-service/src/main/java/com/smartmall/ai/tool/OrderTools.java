package com.smartmall.ai.tool;

import com.smartmall.ai.service.CommerceToolService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {

    private static final String ACCESS_TOKEN_KEY = "accessToken";

    private final CommerceToolService commerceToolService;

    public OrderTools(CommerceToolService commerceToolService) {
        this.commerceToolService = commerceToolService;
    }

    @Tool("查询当前登录用户自己的订单")
    public String listMyOrders(InvocationParameters parameters) {
        String accessToken = parameters.get(ACCESS_TOKEN_KEY);

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("缺少当前用户的登录凭证");
        }

        return commerceToolService.listMyOrders(accessToken);
    }
}
