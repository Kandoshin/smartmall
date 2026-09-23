package com.smartmall.ai.service;

import com.smartmall.ai.tool.OrderTools;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingGraphServiceTest {

    private final ProductProbeAssistant assistant = mock(ProductProbeAssistant.class);

    @Test
    void forwardsOnlyTheCurrentInvocationToken() throws Exception {
        ShoppingGraphService graph = new ShoppingGraphService(assistant);
        when(assistant.chat(eq("查看我的订单"), any(InvocationParameters.class)))
                .thenAnswer(invocation -> {
                    InvocationParameters parameters = invocation.getArgument(1);
                    return parameters.get(OrderTools.ACCESS_TOKEN_KEY);
                });

        assertEquals("first-token", graph.run("查看我的订单", "first-token"));
        assertEquals("second-token", graph.run("查看我的订单", "second-token"));
    }

    @Test
    void anonymousProductCallHasNoCredentialsInInvocationParameters() throws Exception {
        ShoppingGraphService graph = new ShoppingGraphService(assistant);
        when(assistant.chat(eq("推荐一把键盘"), any(InvocationParameters.class)))
                .thenAnswer(invocation -> {
                    InvocationParameters parameters = invocation.getArgument(1);
                    assertTrue(parameters.asMap().isEmpty());
                    return "商品结果";
                });

        assertEquals("商品结果", graph.run("推荐一把键盘", null));
        assertEquals("商品结果", graph.run("推荐一把键盘", " "));
    }
}
